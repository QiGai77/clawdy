package io.github.nanomuse.cloud

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * #373: a Claude sign-in on a build without `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` (the public
 * build) refuses the send with one sentence instead of throwing out of the chat's send path,
 * and the build does not offer the sign-in in the first place.
 */
class ClaudeSignInTest {
    private val shipped: List<CatalogueProvider> by lazy {
        val f = listOf("src/main/assets/nanomuse/providers.json", "app/src/main/assets/nanomuse/providers.json").map(::File).first { it.exists() }
        ProviderCatalogue.parse(f.readText())
    }

    @Test fun `the ways-on card lists the Claude sign-in only when the build can run it`() {
        val all = Ways.resolve(null, shipped, mainland = false).signIns.map { it.name }
        assertTrue("the catalogue has a Claude plan: $all", "Claude" in all)
        val without = Ways.resolve(null, shipped, mainland = false, claudeSignIn = false).signIns.map { it.name }
        assertFalse("Claude" in without)
        assertEquals(all.filter { it != "Claude" }, without)
    }

    @Test fun `the relay's Claude plan is left out the same way`() {
        val guidance = Guidance.parse(
            """{"version": 1, "region": "global", "docs": "",
              "providers": [
                {"id": "deepseek", "name": "DeepSeek", "protocol": "openai", "base_url": "https://api.deepseek.com/v1",
                 "key_url": "https://platform.deepseek.com/api_keys", "auth": ["key"], "regions": ["global"], "covers": ["chat"]}
              ],
              "plans": [
                {"id": "chatgpt", "provider": "openai", "name": "ChatGPT", "auth": "oauth-chatgpt", "clients": ["android"], "covers": ["chat"]},
                {"id": "claude", "provider": "anthropic", "name": "Claude", "auth": "oauth-claude", "clients": ["android"], "covers": ["chat"]}
              ],
              "local": []}""",
        )
        requireNotNull(guidance) { "the fixture parses" }
        assertEquals(listOf("ChatGPT", "Claude"), Ways.resolve(guidance, shipped, mainland = false).signIns.map { it.name })
        assertEquals(listOf("ChatGPT"), Ways.resolve(guidance, shipped, mainland = false, claudeSignIn = false).signIns.map { it.name })
    }

    @Test fun `an OAuth send on a build without the identifier is refused with the sentence, not a crash`() = runBlocking {
        assumeFalse("this build carries the identifier; the refusal is for builds without it", ClaudeSignIn.available)
        val server = MockWebServer()
        server.start()
        try {
            val provider = AnthropicProvider(
                apiKey = "test-oauth-token",
                model = LLMModel.claudeSonnet5,
                basePath = server.url("/").toString().trimEnd('/'),
                isOAuth = true,
            )
            try {
                provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
                fail("the send must be refused")
            } catch (e: LLMError.ProviderError) {
                assertEquals(ClaudeSignIn.MISSING, e.detail)
            }
            assertEquals("nothing left the phone", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
