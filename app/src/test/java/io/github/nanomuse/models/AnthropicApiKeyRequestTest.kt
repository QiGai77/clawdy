package io.github.nanomuse.models

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * An own Anthropic API key on the public build (#333): the provider must not touch the Claude Code
 * OAuth identifier, which is empty there and throws when read, so a request has to leave the phone
 * with or without a system prompt.
 */
class AnthropicApiKeyRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueReply() {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
    }

    @Test
    fun `an API key with no system prompt sends one request and no system field`() = runBlocking {
        enqueueReply()

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 256)

        assertEquals("ok", response.text)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = JSONObject(request.body.readUtf8())
        assertFalse("an API-key request without a prompt carries no system field", body.has("system"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an API key sends the system prompt as the ordinary prompt`() = runBlocking {
        enqueueReply()

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), "You are concise.", 256)

        val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        val system = body.getJSONArray("system")
        assertEquals(1, system.length())
        assertEquals("You are concise.", system.getJSONObject(0).getString("text"))
        assertEquals(1, server.requestCount)
    }
}
