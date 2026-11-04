package dev.emwl.host.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Token bucket used to bound write traffic per site. Capacity is the burst size;
 * tokens refill continuously so a quiet site never accumulates an unbounded budget.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var tokens = capacity.toDouble()
    private var lastRefill = clock()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val elapsedSeconds = (now - lastRefill).coerceAtLeast(0) / 1000.0
        if (elapsedSeconds > 0) {
            tokens = (tokens + elapsedSeconds * refillPerSecond).coerceAtMost(capacity.toDouble())
            lastRefill = now
        }
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    @Synchronized
    fun retryAfterSeconds(): Int {
        if (tokens >= 1.0) return 0
        val missing = 1.0 - tokens
        return kotlin.math.ceil(missing / refillPerSecond).toInt().coerceAtLeast(1)
    }
}

class WriteLimiter(
    private val capacity: Int = 30,
    private val refillPerSecond: Double = 1.0,
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    fun allow(siteId: String): Boolean = bucket(siteId).tryAcquire()

    fun retryAfter(siteId: String): Int = bucket(siteId).retryAfterSeconds()

    private fun bucket(siteId: String): TokenBucket =
        buckets.getOrPut(siteId) { TokenBucket(capacity, refillPerSecond) }
}

/**
 * Guards against DNS rebinding and cross-origin calls on the loopback control plane.
 */
object RequestGuards {

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    fun isLoopbackHost(hostHeader: String?): Boolean {
        val host = hostHeader?.trim()?.lowercase() ?: return false
        if (host.isEmpty()) return false
        val bare = when {
            host.startsWith("[") -> host.substringBefore(']') + "]"
            else -> host.substringBefore(':')
        }
        return bare in LOOPBACK_HOSTS
    }

    /** A missing Origin is acceptable (same-origin navigation); a present one must be loopback. */
    fun isAllowedOrigin(origin: String?): Boolean {
        val value = origin?.trim() ?: return true
        if (value.isEmpty() || value == "null") return false
        val withoutScheme = value.removePrefix("http://").removePrefix("https://")
        if (withoutScheme == value) return false
        return isLoopbackHost(withoutScheme)
    }
}
