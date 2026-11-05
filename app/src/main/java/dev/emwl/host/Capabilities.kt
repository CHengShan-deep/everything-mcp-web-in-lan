package dev.emwl.host

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * One-shot device probes reported in /diag. The 80-port answer decides whether the gateway can
 * live inside the app or must stay an external reverse proxy, so it is measured, not assumed.
 */
object Capabilities {

    @Volatile var port80Bindable: Boolean? = null
        private set

    @Volatile var port80Error: String? = null
        private set

    @Volatile var unprivilegedPortStart: Int? = null
        private set

    fun probe() {
        unprivilegedPortStart = runCatching {
            File("/proc/sys/net/ipv4/ip_unprivileged_port_start").readText().trim().toIntOrNull()
        }.getOrNull()

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", 80))
            port80Bindable = true
            port80Error = null
        } catch (t: Throwable) {
            port80Bindable = false
            port80Error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { socket.close() }
        }
    }
}
