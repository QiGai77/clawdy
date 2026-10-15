package io.github.nanomuse.cloud

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The sign-in screen's "Use a different server": what counts as an address, when http passes, what /healthz says. */
class RelayAddressTest {

    @Test fun `an address is normalised to scheme, host, port and prefix`() {
        assertEquals("https://relay.example.org", RelayAddress.normalize("relay.example.org"))
        assertEquals("https://relay.example.org", RelayAddress.normalize("  https://relay.example.org/  "))
        assertEquals("https://relay.example.org:8790", RelayAddress.normalize("relay.example.org:8790"))
        assertEquals("http://192.168.1.20:8790", RelayAddress.normalize("http://192.168.1.20:8790/"))
        assertEquals("https://box.example.org/nanomuse", RelayAddress.normalize("https://box.example.org/nanomuse/"))
        assertNull(RelayAddress.normalize(""))
        assertNull(RelayAddress.normalize("   "))
        assertNull(RelayAddress.normalize("ftp://relay.example.org"))
        assertNull(RelayAddress.normalize("not a url at all"))
    }

    @Test fun `http is for private hosts only`() {
        assertNull(RelayAddress.problem("https://relay.example.org"))
        assertNull(RelayAddress.problem("http://192.168.1.20:8790"))
        assertNull(RelayAddress.problem("http://10.0.0.5"))
        assertNull(RelayAddress.problem("http://172.16.0.9"))
        assertNull(RelayAddress.problem("http://172.31.255.1"))
        assertNull(RelayAddress.problem("http://localhost:8790"))
        assertNull(RelayAddress.problem("http://127.0.0.1:8790"))
        assertNull(RelayAddress.problem("http://box.local"))
        assertNull(RelayAddress.problem("http://box.tail1234.ts.net"))
        assertEquals(RelayAddress.Problem.HTTP_PUBLIC, RelayAddress.problem("http://relay.example.org"))
        assertEquals(RelayAddress.Problem.HTTP_PUBLIC, RelayAddress.problem("http://172.32.0.1"))
        assertEquals(RelayAddress.Problem.HTTP_PUBLIC, RelayAddress.problem("http://172.15.0.1"))
        assertEquals(RelayAddress.Problem.HTTP_PUBLIC, RelayAddress.problem("http://1.2.3.4"))
        assertEquals(RelayAddress.Problem.EMPTY, RelayAddress.problem(""))
        assertEquals(RelayAddress.Problem.NOT_A_URL, RelayAddress.problem("ftp://x"))
    }

    @Test fun `the private ranges are read exactly`() {
        assertTrue(RelayAddress.isPrivateHost("10.1.2.3"))
        assertTrue(RelayAddress.isPrivateHost("192.168.0.1"))
        assertTrue(RelayAddress.isPrivateHost("172.16.0.1"))
        assertTrue(RelayAddress.isPrivateHost("172.31.0.1"))
        assertTrue(RelayAddress.isPrivateHost("LOCALHOST"))
        assertTrue(RelayAddress.isPrivateHost("[::1]"))
        assertTrue(RelayAddress.isPrivateHost("nas.local"))
        assertTrue(RelayAddress.isPrivateHost("nas.tailnet.ts.net"))
        assertFalse(RelayAddress.isPrivateHost("172.15.0.1"))
        assertFalse(RelayAddress.isPrivateHost("172.32.0.1"))
        assertFalse(RelayAddress.isPrivateHost("192.169.0.1"))
        assertFalse(RelayAddress.isPrivateHost("ts.net.example.org"))
        assertFalse(RelayAddress.isPrivateHost(""))
    }

    @Test fun `the relay address follows the same rule as a model server`() {
        // one rule for both screens (LanOnly): parsed addresses, not string prefixes
        assertFalse(RelayAddress.isPrivateHost("10.foo.example.com"))
        assertFalse(RelayAddress.isPrivateHost("192.168.example.org"))
        assertEquals(RelayAddress.Problem.HTTP_PUBLIC, RelayAddress.problem("http://10.foo.example.com"))
        // the carrier-grade range Tailscale hands out, link-local and ULA are one's own network too
        assertTrue(RelayAddress.isPrivateHost("100.64.0.1"))
        assertTrue(RelayAddress.isPrivateHost("100.101.102.103"))
        assertFalse(RelayAddress.isPrivateHost("100.128.0.1"))
        assertTrue(RelayAddress.isPrivateHost("169.254.3.4"))
        assertTrue(RelayAddress.isPrivateHost("[fd00::1]"))
        assertTrue(RelayAddress.isPrivateHost("[fe80::1]"))
        assertFalse(RelayAddress.isPrivateHost("[2001:db8::1]"))
        assertNull(RelayAddress.problem("http://[fd00::1]:8790"))
        // a name without a dot and the other local suffixes
        assertTrue(RelayAddress.isPrivateHost("desktop"))
        assertTrue(RelayAddress.isPrivateHost("nas.lan"))
        assertTrue(RelayAddress.isPrivateHost("nas.home.arpa"))
        assertNull(RelayAddress.problem("http://desktop:8790"))
    }

    @Test fun `the account page shows host and port`() {
        assertEquals("cloud.nanomuse.cn", RelayAddress.display("https://cloud.nanomuse.cn"))
        assertEquals("192.168.1.20:8790", RelayAddress.display("http://192.168.1.20:8790"))
        assertEquals("box.example.org", RelayAddress.display("https://box.example.org/nanomuse"))
        assertTrue(RelayAddress.isDefault(NanoMuseCloud.DEFAULT_BASE))
        assertTrue(RelayAddress.isDefault(NanoMuseCloud.DEFAULT_BASE + "/"))
        assertFalse(RelayAddress.isDefault("https://relay.example.org"))
    }

    @Test fun `healthz says whether a relay is there`() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val http = OkHttpClient()
            server.enqueue(MockResponse().setBody("""{"ok":true,"version":"0.20","models":["a","b"]}"""))
            val h = RelayAddress.check(base, http)
            assertEquals(RelayAddress.Health("0.20", 2), h)
            assertEquals("/healthz", server.takeRequest().path)

            // a web server that is not a relay
            server.enqueue(MockResponse().setBody("<html>hello</html>"))
            assertTrue(runCatching { RelayAddress.check(base, http) }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(runCatching { RelayAddress.check(base, http) }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setBody("""{"ok":false}"""))
            assertTrue(runCatching { RelayAddress.check(base, http) }.exceptionOrNull() is IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `no server is an IOException, not a crash`() {
        assertTrue(runCatching { RelayAddress.check("http://127.0.0.1:1", OkHttpClient()) }.exceptionOrNull() is IOException)
    }
}
