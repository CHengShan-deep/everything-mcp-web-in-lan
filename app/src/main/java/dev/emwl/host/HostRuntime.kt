package dev.emwl.host

import android.content.Context
import dev.emwl.host.domain.HostRouting
import dev.emwl.host.service.SiteService
import dev.emwl.host.service.respondStatic
import dev.emwl.host.service.siteRoutes
import dev.emwl.host.web.ControlTokens
import dev.emwl.host.web.controlModule
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Owns the three listener classes of the v1 host: the loopback control plane,
 * the LAN hub, and one listener per registered site.
 */
class HostRuntime(private val ctx: Context) {

    val startedAt = System.currentTimeMillis()
    val service = SiteService(ctx)
    val tokens = ControlTokens(ctx)

    private val engines = mutableListOf<EmbeddedServer<*, *>>()
    private val errors = JSONArray()

    @Volatile var controlPort = -1
        private set

    @Volatile var controlError: String? = null
        private set

    @Synchronized
    fun start() {
        Capabilities.probe()
        for (candidate in Ports.controlCandidates()) {
            if (!portFree("127.0.0.1", candidate)) {
                controlError = "port $candidate busy"
                continue
            }
            val engine = tryStart("127.0.0.1", candidate) {
                controlModule(ctx, service, tokens) { controlPort }
            } ?: continue
            engines += engine
            controlPort = candidate
            controlError = null
            break
        }

        tryStart("0.0.0.0", Ports.HUB) { hubModule() }?.let { engines += it }
        service.listSites().forEach { site ->
            runCatching { service.startListener(site) }
                .onFailure {
                    errors.put(
                        JSONObject()
                            .put("site", site.slug)
                            .put("error", "${it.javaClass.simpleName}: ${it.message}")
                    )
                }
        }
    }

    @Synchronized
    fun stop() {
        service.shutdown()
        engines.forEach { runCatching { it.stop(300, 800) } }
        engines.clear()
    }

    fun restartSite(site: dev.emwl.host.data.SiteRecord) {
        service.restartListener(site)
    }

    fun listenersJson(): JSONArray = JSONArray().apply {
        service.listSites().forEach { site ->
            put(
                JSONObject()
                    .put("site", site.slug)
                    .put("port", site.port)
                    .put("exposure", site.exposure)
            )
        }
    }

    fun diagJson(): String = JSONObject()
        .put("pid", android.os.Process.myPid())
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("uptimeMs", System.currentTimeMillis() - startedAt)
        .put("controlPort", controlPort)
        .put("controlError", controlError)
        .put("hubPort", Ports.HUB)
        .put("sites", listenersJson())
        .put("siteErrors", errors)
        .put("threads", Metrics.threadCount())
        .put("fds", Metrics.fileDescriptors())
        .put("pssKb", Metrics.pssKb())
        .put("lanIpv4", Metrics.lanIpv4())
        .toString()

    private fun tryStart(host: String, port: Int, module: io.ktor.server.application.Application.() -> Unit): EmbeddedServer<*, *>? =
        try {
            embeddedServer(CIO, host = host, port = port, module = module).start(wait = false)
        } catch (t: Throwable) {
            errors.put(
                JSONObject()
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

    private fun io.ktor.server.application.Application.hubModule() {
        routing {
            get("/") {
                val site = hostSite(call)
                if (site != null) {
                    respondStatic(call, ctx, site.id, service)
                    return@get
                }
                val sites = service.listSites()
                val rows = sites.joinToString("") { site ->
                    "<li><b>${site.name}</b> — <code>http://${site.slug}.${service.baseDomain}</code> " +
                        "(port ${site.port}, ${site.exposure})</li>"
                }
                call.respondText(
                    """
                    <!doctype html>
                    <html><head><meta charset="utf-8"><title>PocketServe</title></head>
                    <body style="font-family:system-ui;margin:2rem">
                    <h1>Hosted sites</h1>
                    <ul>$rows</ul>
                    <p>Requests are routed by the <code>Host</code> header, so one port can serve every
                    name; point a reverse proxy or DNS wildcard at <code>http://&lt;name&gt;.${service.baseDomain}</code>.</p>
                    <p>Control plane: <code>http://127.0.0.1:${controlPort}</code> (loopback only)</p>
                    </body></html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
            }
            get("/health") {
                val site = hostSite(call)
                if (site != null) {
                    call.respondText(
                        JSONObject()
                            .put("ok", true)
                            .put("site", site.slug)
                            .put("deployed", service.manifest(site) != null)
                            .put("port", site.port)
                            .toString(),
                        ContentType.Application.Json
                    )
                    return@get
                }
                call.respondText(
                    JSONObject()
                        .put("ok", true)
                        .put("sites", service.listSites().size)
                        .toString(),
                    ContentType.Application.Json
                )
            }
        }

        // The same site routes serve the hub, with the site taken from the Host header.
        siteRoutes(ctx, service, includeHealth = false) { call -> hostSite(call)?.id }
    }

    /**
     * The hub is a LAN entry point, so local-only sites are not resolvable through it even
     * though the hub itself listens on all interfaces.
     */
    private fun hostSite(call: io.ktor.server.application.ApplicationCall): dev.emwl.host.data.SiteRecord? =
        HostRouting.slugForHost(call.request.headers["Host"], service.baseDomain)
            ?.let { slug -> service.repository().findBySlug(slug) }
            ?.takeIf { it.isLanExposed && it.enabled }
}
