package com.dev.Tvivo.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The login screen has one server field because a second numeric field is unusable on
 * TVs whose IME ignores `KeyboardType.Number`. That only works if this parser accepts
 * every shape credentials actually arrive in.
 */
class ServerAddressTest {

    @Test
    fun `host and port`() {
        val parsed = ServerAddress.parse("panel.example.com:8080")!!
        assertEquals("panel.example.com", parsed.host)
        assertEquals(8080, parsed.port)
        assertEquals(false, parsed.useHttps)
    }

    @Test
    fun `bare host leaves port null for server_info to fill`() {
        val parsed = ServerAddress.parse("panel.example.com")!!
        assertEquals("panel.example.com", parsed.host)
        assertNull(parsed.port)
    }

    @Test
    fun `http scheme is stripped`() {
        val parsed = ServerAddress.parse("http://panel.example.com:8080")!!
        assertEquals("panel.example.com", parsed.host)
        assertEquals(8080, parsed.port)
        assertEquals(false, parsed.useHttps)
    }

    @Test
    fun `https scheme is remembered`() {
        val parsed = ServerAddress.parse("https://panel.example.com:8443")!!
        assertEquals("panel.example.com", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals(true, parsed.useHttps)
    }

    @Test
    fun `trailing path and query are discarded`() {
        val parsed = ServerAddress.parse("http://panel.example.com:8080/player_api.php?x=1")!!
        assertEquals("panel.example.com", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val parsed = ServerAddress.parse("  panel.example.com:8080  ")!!
        assertEquals("panel.example.com", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun `bracketed ipv6 keeps host without brackets and reads the port`() {
        val parsed = ServerAddress.parse("[2001:db8::1]:8080")!!
        assertEquals("2001:db8::1", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun `bare ipv6 is not mistaken for host plus port`() {
        val parsed = ServerAddress.parse("2001:db8::1")!!
        assertEquals("2001:db8::1", parsed.host)
        assertNull(parsed.port)
    }

    @Test
    fun `ipv4 with port`() {
        val parsed = ServerAddress.parse("192.168.1.10:2095")!!
        assertEquals("192.168.1.10", parsed.host)
        assertEquals(2095, parsed.port)
    }

    @Test
    fun `rejects empty input`() {
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
    }

    @Test
    fun `rejects non-numeric port`() {
        assertNull(ServerAddress.parse("panel.example.com:abc"))
    }

    @Test
    fun `rejects out of range port`() {
        assertNull(ServerAddress.parse("panel.example.com:70000"))
        assertNull(ServerAddress.parse("panel.example.com:0"))
    }
}
