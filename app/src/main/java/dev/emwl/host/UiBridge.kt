package dev.emwl.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Machine-readable view of the on-device UI plus a command channel for it, so an
 * agent can read the current screen (ids, text, bounds) and act on it without screenshots.
 */
object UiBridge {

    data class Node(
        val id: String,
        val role: String,
        val text: String,
        val enabled: Boolean,
        val bounds: List<Int>,
        val actions: List<String>,
    )

    @Volatile private var ctx: Context? = null
    private val nodes = ConcurrentHashMap<String, Node>()
    private val handlers = ConcurrentHashMap<String, () -> Unit>()

    fun attach(context: Context) {
        ctx = context.applicationContext
    }

    fun update(node: Node) {
        nodes[node.id] = node
    }

    fun register(id: String, handler: () -> Unit) {
        handlers[id] = handler
    }

    fun snapshot(): JSONObject {
        val arr = JSONArray()
        nodes.values.sortedBy { it.id }.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("role", n.role)
                    .put("text", n.text)
                    .put("enabled", n.enabled)
                    .put("bounds", JSONArray(n.bounds))
                    .put("actions", JSONArray(n.actions))
            )
        }
        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return JSONObject()
            .put("screen", "home")
            .put("nodes", arr)
            .put(
                "state", JSONObject()
                    .put("running", HostState.running)
                    .put("sites", HostState.siteCount)
                    .put("controlPort", HostState.runtime?.controlPort ?: -1)
                    .put("lanIpv4", Metrics.lanIpv4())
                    .put("batteryExempt", pm?.isIgnoringBatteryOptimizations(ctx?.packageName ?: "") ?: false)
                    .put("serviceError", HostState.lastServiceError)
            )
    }

    fun perform(body: String): JSONObject = try {
        val request = JSONObject(body)
        when (val action = request.optString("action")) {
            "press" -> {
                val id = request.optString("id")
                val handler = handlers[id]
                if (handler == null) result(false, "no-mounted-node: $id")
                else {
                    handler()
                    result(true, "pressed: $id")
                }
            }
            "start" -> withContext { HostService.start(it) }.let { result(true, "started") }
            "stop" -> withContext { HostService.stop(it) }.let { result(true, "stopped") }
            "battery_exemption" -> withContext {
                it.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${it.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.let { result(true, "requested") }
            else -> result(false, "unknown-action: $action")
        }
    } catch (t: Throwable) {
        result(false, "${t.javaClass.simpleName}: ${t.message}")
    }

    private inline fun withContext(block: (Context) -> Unit) {
        ctx?.let(block)
    }

    private fun result(ok: Boolean, detail: String) =
        JSONObject().put("ok", ok).put("detail", detail)
}
