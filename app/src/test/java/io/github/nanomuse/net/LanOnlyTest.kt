package io.github.nanomuse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanOnlyTest {
    @Test fun `plain http is fine on the local network`() {
        for (u in listOf(
            "http://127.0.0.1:8787", "http://localhost:11434/v1", "http://10.0.2.2:8080", "http://192.168.1.20:1234/v1",
            "http://172.20.0.5", "http://100.101.102.103:8787", "http://169.254.3.4", "http://desktop:8080", "http://nas.local:5000",
            "http://[::1]:8787", "http://[fd12:3456::1]:8787", "http://[fe80::1%25wlan0]:80", "http://box.home.arpa",
        )) assertNull(u, LanOnly.problem(u))
    }

    @Test fun `plain http across the Internet is refused, https is not`() {
        for (u in listOf("http://api.example.com/v1", "http://8.8.8.8", "http://172.32.0.1", "http://100.128.0.1", "http://[2001:db8::1]")) {
            assertNotNull(u, LanOnly.problem(u))
        }
        assertNull(LanOnly.problem("https://api.example.com/v1"))
        assertNull(LanOnly.problem(""))
        assertNull(LanOnly.problem("not a url"))
        assertEquals(true, LanOnly.problem("http://api.example.com")!!.contains("https://"))
    }

    @Test fun `the refused host is handed to the screens for the localized footer`() {
        assertEquals("api.example.com", LanOnly.refusedHost("http://API.example.com/v1"))
        assertEquals("8.8.8.8", LanOnly.refusedHost("http://8.8.8.8:80"))
        assertEquals("2001:db8::1", LanOnly.refusedHost("http://[2001:db8::1]"))
        assertEquals("", LanOnly.refusedHost("http:/v1"))
        assertNull(LanOnly.refusedHost("http://192.168.1.20:1234/v1"))
        assertNull(LanOnly.refusedHost("https://api.example.com/v1"))
        assertNull(LanOnly.refusedHost("   "))
    }

    @Test fun `the host test on its own`() {
        assertTrue(LanOnly.isLocal("192.168.0.1"))
        assertTrue(LanOnly.isLocal("printer"))
        assertTrue(LanOnly.isLocal("Printer.LAN"))
        assertFalse(LanOnly.isLocal("example.com"))
        assertFalse(LanOnly.isLocal("192.168.0.300"))
        assertFalse(LanOnly.isLocal("1.1.1.1"))
        // a public name that starts like a private address is a name, not an address
        assertFalse(LanOnly.isLocal("10.foo.example.com"))
        assertFalse(LanOnly.isLocal("127.example.org"))
        // Tailscale's MagicDNS names, as the sign-in screen already allowed them
        assertTrue(LanOnly.isLocal("box.tail1234.ts.net"))
        assertFalse(LanOnly.isLocal("ts.net.example.org"))
        assertNull(LanOnly.problem("http://box.tail1234.ts.net:11434/v1"))
    }
}
