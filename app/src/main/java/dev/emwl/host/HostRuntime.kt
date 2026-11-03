package dev.emwl.host

import android.content.Context
import android.os.PowerManager
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Brings up the three listener classes of the v1 host:
 * the loopback control plane, the LAN hub, and one listener per site.
 */
class HostRuntime(private val ctx: Context, val siteCount: Int) {

    val startedAt = System.currentTimeMillis()
    val registry = SiteRegistry(siteCount)

    private val engines = mutableListOf<EmbeddedServer<*, *>>()
    private val listeners = JSONArray()
    private val errors = JSONArray()

    @Volatile var controlPort = -1
        private set

    @Volatile var controlError: String? = null
        private set

    @Synchronized
    fun start() {
        for (candidate in Ports.controlCandidates()) {
            if (!portFree("127.0.0.1", candidate)) {
                controlError = "port $candidate busy"
                continue
            }
            val engine = tryStart("127.0.0.1", candidate, CONTROL) ?: continue
            engines += engine
            controlPort = candidate
            controlError = null
            break
        }

        tryStart("0.0.0.0", Ports.HUB, HUB)?.let { engines += it }

        registry.siteNames().forEachIndexed { index, name ->
            tryStart("0.0.0.0", Ports.sitePort(index), "site:$name")?.let { engines += it }
        }
    }

    @Synchronized
    fun stop() {
        engines.forEach { runCatching { it.stop(300, 800) } }
        engines.clear()
    }

    private fun tryStart(host: String, port: Int, kind: String): EmbeddedServer<*, *>? = try {
        embeddedServer(CIO, host = host, port = port) { hostModule(kind) }
            .start(wait = false)
            .also {
                listeners.put(JSONObject().put("kind", kind).put("host", host).put("port", port))
            }
    } catch (t: Throwable) {
        errors.put(
            JSONObject()
                .put("kind", kind)
                .put("host", host)
                .put("port", port)
                .put("error", "${t.javaClass.simpleName}: ${t.message}")
        )
        null
    }

    private fun portFree(host: String, port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port))
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun healthJson(kind: String): String = JSONObject()
        .put("ok", true)
        .put("kind", kind)
        .put("pid", android.os.Process.myPid())
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("uptimeMs", System.currentTimeMillis() - startedAt)
        .put("lanIpv4", Metrics.lanIpv4())
        .put("sites", siteCount)
        .toString()

    fun diagJson(): String {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("pid", android.os.Process.myPid())
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("uptimeMs", System.currentTimeMillis() - startedAt)
            .put("sites", registry.toJson())
            .put("controlPort", controlPort)
            .put("controlError", controlError)
            .put("listeners", listeners)
            .put("listenErrors", errors)
            .put(
                "memory", JSONObject()
                    .put("pssKb", Metrics.pssKb())
                    .put("threads", Metrics.threadCount())
                    .put("fds", Metrics.fileDescriptors())
            )
            .put("doze", power.isDeviceIdleMode)
            .put("batteryExempt", power.isIgnoringBatteryOptimizations(ctx.packageName))
            .put("lanIpv4", Metrics.lanIpv4())
            .toString()
    }

    private fun hubHtml(): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>everything-mcp-web in LAN</title></head>
        <body style="font-family:system-ui;margin:2rem">
        <h1>Hosted sites</h1>
        <ul>
          ${registry.siteNames().joinToString("") { name ->
        "<li>${name} — port ${registry.portOf(name)}</li>"
    }}
        </ul>
        <p>Control plane: <code>http://127.0.0.1:$controlPort</code> (loopback only)</p>
        </body></html>
    """.trimIndent()

    private fun siteHtml(name: String, port: Int): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>$name</title></head>
        <body style="font-family:system-ui;margin:2rem">
        <h1>$name</h1>
        <p>Site listener is up on port $port. Dynamic content arrives in v1 milestone P1.</p>
        </body></html>
    """.trimIndent()

    private fun adminHtml(): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>Admin</title></head>
        <body style="font-family:system-ui;margin:2rem">
        <h1>Admin</h1>
        <p>Control plane placeholder. The management console lands in milestone P1.</p>
        <p><a href="/diag">/diag</a> · <a href="/ui">/ui</a> · <a href="/sites">/sites</a></p>
        </body></html>
    """.trimIndent()

    private fun Application.hostModule(kind: String) {
        routing {
            get("/") {
                when {
                    kind == CONTROL -> call.respondText(adminHtml(), ContentType.Text.Html)
                    kind == HUB -> call.respondText(hubHtml(), ContentType.Text.Html)
                    else -> call.respondText(siteHtml(kind.removePrefix("site:"), 0), ContentType.Text.Html)
                }
            }

            get("/health") { call.respondText(healthJson(kind), ContentType.Application.Json) }

            get("/diag") { call.respondText(diagJson(), ContentType.Application.Json) }

            get("/sites") {
                if (kind != CONTROL) {
                    call.respondText("""{"error":"control-plane-only"}""", ContentType.Application.Json)
                    return@get
                }
                call.respondText(registry.toJson().toString(), ContentType.Application.Json)
            }

            get("/ui") {
                if (kind != CONTROL) {
                    call.respondText("""{"error":"control-plane-only"}""", ContentType.Application.Json)
                    return@get
                }
                call.respondText(UiBridge.snapshot().toString(), ContentType.Application.Json)
            }

            post("/ui/action") {
                if (kind != CONTROL) {
                    call.respondText("""{"error":"control-plane-only"}""", ContentType.Application.Json)
                    return@post
                }
                val body = call.receiveText()
                val response = withContext(Dispatchers.Main) { UiBridge.perform(body) }
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }
    }

    companion object {
        const val CONTROL = "control"
        const val HUB = "hub"
    }
}
