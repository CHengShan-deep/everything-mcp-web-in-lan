package dev.emwl.host.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {

    @Test
    fun `token bucket allows the burst then refills over time`() {
        var now = 0L
        val bucket = TokenBucket(capacity = 3, refillPerSecond = 1.0, clock = { now })
        assertTrue(bucket.tryAcquire())
        assertTrue(bucket.tryAcquire())
        assertTrue(bucket.tryAcquire())
        assertFalse(bucket.tryAcquire())
        assertTrue(bucket.retryAfterSeconds() >= 1)

        now += 1_000
        assertTrue(bucket.tryAcquire())
        assertFalse(bucket.tryAcquire())
    }

    @Test
    fun `limiter isolates buckets per site`() {
        val limiter = WriteLimiter(capacity = 1, refillPerSecond = 0.0)
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
    }

    @Test
    fun `loopback hosts are recognised`() {
        assertTrue(RequestGuards.isLoopbackHost("127.0.0.1:47100"))
        assertTrue(RequestGuards.isLoopbackHost("localhost"))
        assertTrue(RequestGuards.isLoopbackHost("LOCALHOST:47100"))
        assertTrue(RequestGuards.isLoopbackHost("[::1]:47100"))
        assertFalse(RequestGuards.isLoopbackHost("evil.example"))
        assertFalse(RequestGuards.isLoopbackHost("127.0.0.1.evil.example"))
        assertFalse(RequestGuards.isLoopbackHost(null))
        assertFalse(RequestGuards.isLoopbackHost(""))
    }

    @Test
    fun `origins must be loopback when present`() {
        assertTrue(RequestGuards.isAllowedOrigin(null))
        assertTrue(RequestGuards.isAllowedOrigin("http://127.0.0.1:47100"))
        assertTrue(RequestGuards.isAllowedOrigin("http://localhost:47100"))
        assertFalse(RequestGuards.isAllowedOrigin("https://evil.example"))
        assertFalse(RequestGuards.isAllowedOrigin("null"))
        assertFalse(RequestGuards.isAllowedOrigin("file://x"))
    }
}
