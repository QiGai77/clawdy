package io.github.nanomuse.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

class OwnProviderProxyTest {
    private val routed = setOf("chatgpt.com", "auth.openai.com", "api.openai.com", "dashscope.aliyuncs.com")
    private val on = OwnProviderProxy.Config(enabled = true, host = "127.0.0.1", port = 7890)

    @Test fun `a configuration is usable only on, with a host and a port`() {
        assertFalse(OwnProviderProxy.Config().usable)
        assertFalse(on.copy(enabled = false).usable)
        assertFalse(on.copy(host = " ").usable)
        assertFalse(on.copy(port = 0).usable)
        assertFalse(on.copy(port = 70000).usable)
        assertTrue(on.usable)
        assertEquals("127.0.0.1:7890", on.address)
        assertEquals("[::1]:7890", on.copy(host = "::1").address)
        assertFalse(on.hasCredentials)
        assertTrue(on.copy(user = "me").hasCredentials)
        assertEquals(Proxy.Type.HTTP, on.proxy().type())
    }

    @Test fun `only the provider hosts are routed, never the relay, the LAN or anything else`() {
        OwnProviderProxy.setForTests(on, routed, relay = "nanomuse.cn")
        assertTrue(OwnProviderProxy.routes("chatgpt.com"))
        assertTrue(OwnProviderProxy.routes("CHATGPT.com"))
        assertTrue(OwnProviderProxy.routes("api.openai.com"))
        assertTrue(OwnProviderProxy.routes("sub.dashscope.aliyuncs.com"))
        assertFalse(OwnProviderProxy.routes("nanomuse.cn"))
        assertFalse(OwnProviderProxy.routes("github.com"))
        assertFalse(OwnProviderProxy.routes("192.168.1.20"))
        assertFalse(OwnProviderProxy.routes("localhost"))
        assertFalse(OwnProviderProxy.routes(null))
        assertFalse(OwnProviderProxy.routes(""))
    }

    @Test fun `off, nothing is routed`() {
        OwnProviderProxy.setForTests(on.copy(enabled = false), routed)
        assertFalse(OwnProviderProxy.routes("chatgpt.com"))
        OwnProviderProxy.setForTests(on.copy(port = 0), routed)
        assertFalse(OwnProviderProxy.routes("chatgpt.com"))
    }

    @Test fun `the selector answers the proxy for a provider and the system's answer for the rest`() {
        OwnProviderProxy.setForTests(on, routed, relay = "nanomuse.cn")
        val system = object : ProxySelector() {
            override fun select(uri: URI?) = mutableListOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        }
        val selector = OwnProviderProxy.Selector(system)
        val viaProxy = selector.select(URI("https://chatgpt.com/backend-api/codex/responses"))
        assertEquals(1, viaProxy.size)
        assertEquals(Proxy.Type.HTTP, viaProxy[0].type())
        assertEquals("127.0.0.1:7890", (viaProxy[0].address() as java.net.InetSocketAddress).let { "${it.hostString}:${it.port}" })
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://nanomuse.cn/v1/hub")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://192.168.1.20:11434/v1/models")))
        // without a system selector there is still an answer
        assertEquals(listOf(Proxy.NO_PROXY), OwnProviderProxy.Selector(null).select(URI("https://example.com/")))
    }

    @Test fun `the authenticator answers a 407 once, with the credentials, and never without them`() {
        val request = Request.Builder().url("https://chatgpt.com/").build()
        fun challenge(req: Request) = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required")
            .header("Proxy-Authenticate", "Basic realm=\"proxy\"").build()
        OwnProviderProxy.setForTests(on, routed)
        assertNull(OwnProviderProxy.authenticator.authenticate(null, challenge(request)))
        OwnProviderProxy.setForTests(on.copy(user = "me", password = "secret"), routed)
        val answered = OwnProviderProxy.authenticator.authenticate(null, challenge(request))
        assertNotNull(answered)
        assertEquals(okhttp3.Credentials.basic("me", "secret"), answered!!.header("Proxy-Authorization"))
        // the same challenge again, already answered: give up rather than loop
        assertNull(OwnProviderProxy.authenticator.authenticate(null, challenge(answered)))
    }

    @Test fun `cancelling the probe hangs up on the proxy at once`() = runBlocking {
        HoldingServer().use { server ->
            val cfg = OwnProviderProxy.Config(enabled = true, host = "127.0.0.1", port = server.port)
            // on another thread: the latch waits below block this one
            val job = launch(Dispatchers.Default) { OwnProviderProxy.probe(cfg, "http://provider.invalid/") }
            assertTrue("the probe never reached the proxy", server.accepted.await(5, TimeUnit.SECONDS))
            job.cancel()
            // Before #336 the socket stayed open until the 10 s read timeout.
            assertTrue("the proxy connection stayed open after the cancel", server.closedByClient.await(1, TimeUnit.SECONDS))
            job.join()
        }
    }

    @Test fun `the host of a URL`() {
        assertEquals("chatgpt.com", OwnProviderProxy.hostOf("https://chatgpt.com/backend-api"))
        assertEquals("dashscope.aliyuncs.com", OwnProviderProxy.hostOf(" https://dashscope.aliyuncs.com/compatible-mode/v1 "))
        assertEquals("", OwnProviderProxy.hostOf("not a url"))
    }

    @Test fun `cancelling the probe while peeking a 403 body closes the call`() = runBlocking {
        BodyHoldingServer(403).use { server ->
            val cfg = OwnProviderProxy.Config(enabled = true, host = "127.0.0.1", port = server.port)
            val job = launch(Dispatchers.Default) { OwnProviderProxy.probe(cfg, "http://provider.invalid/") }
            try {
                assertTrue("the proxy body reader was not entered", server.awaitBodyReader("OwnProviderProxy.kt", "peekBody"))
                job.cancel()
                assertTrue("the proxy body stayed open after cancel", server.closedByClient.await(1, TimeUnit.SECONDS))
                withTimeout(1_000) { job.join() }
            } finally {
                job.cancel()
                server.close()
                withTimeout(5_000) { job.join() }
            }
        }
    }
}
