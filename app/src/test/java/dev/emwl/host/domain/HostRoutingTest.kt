package dev.emwl.host.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostRoutingTest {

    @Test
    fun `a site name under the base domain resolves to its slug`() {
        assertEquals("revtest", HostRouting.slugForHost("revtest.lan", "lan"))
        assertEquals("revtest", HostRouting.slugForHost("revtest.lan:47101", "lan"))
        assertEquals("revtest", HostRouting.slugForHost("RevTest.LAN", "lan"))
        assertEquals("revtest", HostRouting.slugForHost("revtest.lan.", "lan"))
    }

    @Test
    fun `names outside the base domain do not resolve`() {
        assertNull(HostRouting.slugForHost("revtest.example", "lan"))
        assertNull(HostRouting.slugForHost("192.168.1.5:47101", "lan"))
        assertNull(HostRouting.slugForHost("lan", "lan"))
        assertNull(HostRouting.slugForHost("", "lan"))
        assertNull(HostRouting.slugForHost(null, "lan"))
    }

    @Test
    fun `nested subdomains and empty slugs do not resolve`() {
        assertNull(HostRouting.slugForHost("a.revtest.lan", "lan"))
        assertNull(HostRouting.slugForHost(".lan", "lan"))
    }

    @Test
    fun `a multi-label base domain still works`() {
        assertEquals("demo", HostRouting.slugForHost("demo.home.arpa:80", "home.arpa"))
        assertNull(HostRouting.slugForHost("demo.lan", "home.arpa"))
    }

    @Test
    fun `an empty base domain disables routing`() {
        assertNull(HostRouting.slugForHost("revtest.lan", ""))
    }
}
