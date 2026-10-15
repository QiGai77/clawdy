package io.github.nanomuse.debug

import com.openminis.app.debug.LLMRequestLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The debug request log keeps its own masked copy of the request headers (#347).
 * BuildConfig.DEBUG is true under testDebugUnitTest, so add() records.
 */
class LLMRequestLogTest {
    private val credentialNames = listOf(
        "Authorization", "PROXY-AUTHORIZATION", "x-api-key", "Api-Key", "apikey",
        "X-Goog-Api-Key", "cookie", "Set-Cookie", "X-Access-Token",
    )

    private fun entry(headers: Map<String, String>, body: String = "{\"model\":\"m\"}") =
        LLMRequestLog.Entry(
            provider = "test",
            requestURL = "https://api.example.com/v1/chat/completions",
            requestHeaders = headers,
            requestBody = body,
        )

    private fun headersOf(json: JSONObject, index: Int): JSONObject =
        json.getJSONArray("requests").getJSONObject(index).getJSONObject("requestHeaders")

    private fun assertMasked(headers: Map<String, String>) {
        for (name in credentialNames) assertEquals(name, "***", headers[name])
        assertEquals("application/json", headers["Content-Type"])
        assertFalse(headers.values.any { it.contains("secret") })
    }

    private fun assertMasked(headers: JSONObject) {
        for (name in credentialNames) assertEquals(name, "***", headers.getString(name))
        assertEquals("application/json", headers.getString("Content-Type"))
        for (key in headers.keys()) assertFalse(key, headers.getString(key).contains("secret"))
    }

    @Before fun clear() = LLMRequestLog.clear()

    @Test fun `credential headers are masked everywhere the log is read`() {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        for (name in credentialNames) headers[name] = "secret-$name"
        LLMRequestLog.add(entry(headers))

        assertMasked(LLMRequestLog.getAll().single().requestHeaders)
        assertMasked(LLMRequestLog.getLast(1).single().requestHeaders)
        assertMasked(headersOf(LLMRequestLog.toJSON(), 0))
        assertMasked(headersOf(LLMRequestLog.toJSON(1), 0))
        assertEquals(listOf("Content-Type") + credentialNames, LLMRequestLog.getAll().single().requestHeaders.keys.toList())
    }

    @Test fun `the recorded entry is independent of the caller's map`() {
        val headers = mutableMapOf("Content-Type" to "application/json", "X-Trace" to "a")
        LLMRequestLog.add(entry(headers))
        headers["X-Trace"] = "b"
        headers["X-API-Key"] = "late-secret"
        headers.remove("Content-Type")

        val recorded = LLMRequestLog.getAll().single().requestHeaders
        assertEquals("a", recorded["X-Trace"])
        assertEquals("application/json", recorded["Content-Type"])
        assertFalse(recorded.containsKey("X-API-Key"))
        val json = headersOf(LLMRequestLog.toJSON(), 0)
        assertEquals("a", json.getString("X-Trace"))
        assertFalse(json.has("X-API-Key"))
    }

    @Test fun `a long body is still truncated and the headers still masked`() {
        val body = "x".repeat(64 * 1024 + 100)
        LLMRequestLog.add(entry(mapOf("Authorization" to "Bearer secret", "Content-Type" to "application/json"), body))

        val recorded = LLMRequestLog.getAll().single()
        assertTrue(recorded.requestBody.endsWith("[truncated 100 chars]"))
        assertEquals(64 * 1024, recorded.requestBody.indexOf('\n'))
        assertEquals("***", recorded.requestHeaders["Authorization"])
        assertEquals("application/json", recorded.requestHeaders["Content-Type"])
    }

    @Test fun `the ring keeps the newest twenty in order`() {
        for (i in 1..21) LLMRequestLog.add(entry(mapOf("X-N" to "$i"), body = "{\"n\":$i}"))

        val all = LLMRequestLog.getAll()
        assertEquals(20, all.size)
        assertEquals((2..21).map { "$it" }, all.map { it.requestHeaders["X-N"] })
        assertEquals("21", LLMRequestLog.getLast(1).single().requestHeaders["X-N"])
        assertEquals(20, LLMRequestLog.toJSON().getInt("count"))
    }
}
