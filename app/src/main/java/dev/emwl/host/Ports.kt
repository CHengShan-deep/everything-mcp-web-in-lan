package dev.emwl.host

import android.os.Debug
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Fixed port contract for the v1 on-device host. Ports are deliberately high and
 * uncommon so they do not collide with agents spinning up servers on common ports.
 */
object Ports {
    const val CONTROL = 47100
    const val CONTROL_FALLBACK_BASE = 48100
    const val CONTROL_FALLBACK_COUNT = 8
    const val HUB = 47101
    const val SITE_BASE = 47200
    const val MAX_SITES = 32

    fun sitePort(index: Int): Int {
        require(index in 0 until MAX_SITES) { "site index out of range: $index" }
        return SITE_BASE + index
    }

    fun controlCandidates(): List<Int> =
        listOf(CONTROL) + (0 until CONTROL_FALLBACK_COUNT).map { CONTROL_FALLBACK_BASE + it }
}

object Metrics {
    fun threadCount(): Int =
        runCatching {
            File("/proc/self/status").readLines()
                .first { it.startsWith("Threads:") }
                .substringAfter(':').trim().toInt()
        }.getOrDefault(Thread.activeCount())

    fun pssKb(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss.toLong()
    }.getOrDefault(-1L)

    fun fileDescriptors(): Int =
        runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)

    fun lanIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
