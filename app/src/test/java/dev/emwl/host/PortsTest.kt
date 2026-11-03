package dev.emwl.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsTest {

    @Test
    fun `site ports stay inside the reserved block`() {
        val ports = (0 until Ports.MAX_SITES).map { Ports.sitePort(it) }
        assertEquals(Ports.MAX_SITES, ports.distinct().size)
        assertEquals(Ports.SITE_BASE, ports.first())
        assertEquals(Ports.SITE_BASE + Ports.MAX_SITES - 1, ports.last())
        assertTrue(ports.all { it in Ports.SITE_BASE until Ports.SITE_BASE + Ports.MAX_SITES })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `site index above the cap is rejected`() {
        Ports.sitePort(Ports.MAX_SITES)
    }

    @Test
    fun `control candidates are distinct and fall back away from the default port`() {
        val candidates = Ports.controlCandidates()
        assertEquals(Ports.CONTROL, candidates.first())
        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.drop(1).all { it >= Ports.CONTROL_FALLBACK_BASE })
        assertTrue(candidates.none { it in Ports.SITE_BASE until Ports.SITE_BASE + Ports.MAX_SITES })
    }
}
