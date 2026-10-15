package io.github.nanomuse.oauth

import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handing a pasted callback to the local sign-in server (#441). */
class LoopbackCallbackTest {
    private val openAI = LoopbackCallback.target(ProviderType.openAI)!!

    @Test fun `every loopback provider has a target and Kimi has none`() {
        assertEquals(LoopbackCallback.Target(1455, "/auth/callback"), LoopbackCallback.target(ProviderType.openAI))
        assertEquals(LoopbackCallback.Target(54545, "/callback"), LoopbackCallback.target(ProviderType.anthropic))
        assertEquals(LoopbackCallback.Target(56121, "/callback"), LoopbackCallback.target(ProviderType.xAI))
        assertEquals(LoopbackCallback.Target(3000, "/callback"), LoopbackCallback.target(ProviderType.openRouter))
        assertNull(LoopbackCallback.target(ProviderType.kimiCode))
    }

    @Test fun `the forward goes to 127 dot 0 dot 0 dot 1 with the code re-encoded`() {
        val cb = CallbackUrlParser.Callback(code = "a/b=c", state = "s t", port = null, path = null)
        assertEquals("http://127.0.0.1:1455/auth/callback?code=a%2Fb%3Dc&state=s+t", LoopbackCallback.forwardUrl(openAI, cb))
    }

    @Test fun `a port and path from the paste win over the provider's defaults`() {
        val cb = CallbackUrlParser.Callback(code = "k", state = null, port = 3001, path = "/callback")
        assertEquals("http://127.0.0.1:3001/callback?code=k", LoopbackCallback.forwardUrl(LoopbackCallback.target(ProviderType.openRouter)!!, cb))
    }

    @Test fun `the waiting server gets the request and a 2xx counts as delivered`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>ok</html>"))
        server.start()
        try {
            val target = LoopbackCallback.Target(server.port, "/auth/callback")
            val cb = CallbackUrlParser.Callback(code = "abc", state = "xyz", port = null, path = null)
            assertTrue(LoopbackCallback.deliver(target, cb))
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/auth/callback?code=abc&state=xyz", recorded.path)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `nobody listening is reported as not delivered`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val port = server.port
        server.shutdown()
        val cb = CallbackUrlParser.Callback(code = "abc", state = null, port = null, path = null)
        assertFalse(LoopbackCallback.deliver(LoopbackCallback.Target(port, "/callback"), cb))
    }
}
