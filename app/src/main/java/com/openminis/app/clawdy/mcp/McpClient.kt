package com.openminis.app.clawdy.mcp

import com.openminis.app.logging.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clawdy 0.3.0: a small native MCP client (JSON-RPC 2.0), so MCP works without the removed PRoot
 * sandbox and its `minis-mcp-cli`.
 *
 * Transports:
 *  - **Streamable HTTP** (MCP 2025-03-26+): every message is a POST to the server URL; the reply is
 *    either `application/json` or a `text/event-stream` carrying the response; the server may hand
 *    out an `Mcp-Session-Id` that later requests echo.
 *  - **HTTP+SSE** (2024-11-05, legacy): GET the `/sse` URL, the first `endpoint` event names the POST
 *    URL, and replies arrive on the open stream. Used when the URL ends in `/sse` or when the server
 *    rejects a Streamable POST with 404/405.
 *
 * Blocking calls; run on Dispatchers.IO. One instance per configured server ([McpManager] caches).
 */
class McpClient(
    private val url: String,
    private val headers: () -> Map<String, String>,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    class McpError(message: String, val httpCode: Int = 0) : IOException(message)

    data class Tool(val name: String, val description: String, val inputSchema: JSONObject)

    private val ids = AtomicInteger(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String = PROTOCOL
    @Volatile private var initialized = false
    @Volatile private var sse: SseChannel? = null
    var serverName: String? = null; private set

    private val legacy = url.trimEnd('/').endsWith("/sse")

    @Synchronized
    fun initialize() {
        if (initialized) return
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Clawdy").put("version", "0.3.0"))
        val res = try {
            request("initialize", params)
        } catch (e: McpError) {
            if (!legacy && sse == null && (e.httpCode == 404 || e.httpCode == 405)) {
                AppLogger.info(TAG, "Streamable POST got ${e.httpCode}; trying legacy SSE at $url")
                openSse(url)
                request("initialize", params)
            } else throw e
        }
        protocolVersion = res.optString("protocolVersion", PROTOCOL)
        serverName = res.optJSONObject("serverInfo")?.optString("name")
        notify("notifications/initialized")
        initialized = true
    }

    fun listTools(): List<Tool> {
        initialize()
        val out = mutableListOf<Tool>()
        var cursor: String? = null
        var pages = 0
        do {
            val p = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val r = request("tools/list", p)
            val arr = r.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                out += Tool(t.optString("name"), t.optString("description"), t.optJSONObject("inputSchema") ?: JSONObject())
            }
            cursor = r.optString("nextCursor", "").ifBlank { null }
        } while (cursor != null && ++pages < 10)
        return out
    }

    /** Returns the `result` object of tools/call (content array + isError). */
    fun callTool(name: String, arguments: JSONObject): JSONObject {
        initialize()
        return request("tools/call", JSONObject().put("name", name).put("arguments", arguments), timeoutMs = 180_000)
    }

    fun close() {
        runCatching {
            if (!legacy && sse == null && sessionId != null) {
                http.newCall(base(url).delete().build()).execute().close()
            }
        }
        sse?.close(); sse = null
        initialized = false; sessionId = null
    }

    // ── JSON-RPC ──────────────────────────────────────────────────────────

    private fun request(method: String, params: JSONObject, timeoutMs: Long = 60_000): JSONObject {
        val id = ids.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val reply = if (legacy || sse != null) sseRequest(msg, id, timeoutMs) else streamableRequest(msg, id)
        reply.optJSONObject("error")?.let { err ->
            throw McpError("MCP error ${err.optInt("code")}: ${err.optString("message")}")
        }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private fun notify(method: String) {
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
        runCatching {
            if (legacy || sse != null) postToSse(msg)
            else http.newCall(post(url, msg)).execute().close()
        }
    }

    private fun base(u: String): Request.Builder {
        val b = Request.Builder().url(u)
        headers().forEach { (k, v) -> if (k.isNotBlank()) b.header(k, v) }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        if (initialized) b.header("MCP-Protocol-Version", protocolVersion)
        return b
    }

    private fun post(u: String, msg: JSONObject): Request =
        base(u).header("Accept", "application/json, text/event-stream")
            .post(msg.toString().toRequestBody(JSON))
            .build()

    // ── Streamable HTTP ───────────────────────────────────────────────────

    private fun streamableRequest(msg: JSONObject, id: Int): JSONObject {
        http.newCall(post(url, msg)).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (resp.code == 404 && sessionId != null && msg.optString("method") != "initialize") {
                    // session expired: start over once
                    sessionId = null; initialized = false
                    throw McpError("session expired (404); reconnect", 404)
                }
                throw McpError("HTTP ${resp.code}${authHint(resp)}: ${body.take(300)}", resp.code)
            }
            val type = resp.header("Content-Type").orEmpty()
            return if (type.contains("text/event-stream")) readSseUntil(resp, id)
            else {
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) throw McpError("empty response")
                val v = text.trim()
                if (v.startsWith("[")) {
                    val arr = JSONArray(v)
                    (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optInt("id", -1) == id }
                        ?: throw McpError("no response with id $id")
                } else JSONObject(v)
            }
        }
    }

    private fun authHint(resp: Response): String =
        if (resp.code == 401 || resp.code == 403) " (未授权：检查 Token / 重新登录)" else ""

    private fun readSseUntil(resp: Response, id: Int): JSONObject {
        val reader = resp.body?.charStream()?.buffered() ?: throw McpError("empty stream")
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data:")) data.append(line.removePrefix("data:").trimStart()).append('\n')
            else if (line.isEmpty() && data.isNotEmpty()) {
                val payload = data.toString().trim(); data.setLength(0)
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (obj.optInt("id", -1) == id && (obj.has("result") || obj.has("error"))) return obj
            }
        }
        throw McpError("stream ended without a response to request $id")
    }

    // ── legacy HTTP+SSE ───────────────────────────────────────────────────

    private inner class SseChannel(val call: okhttp3.Call, val endpoint: String) {
        val waiting = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
        fun close() = runCatching { call.cancel() }
    }

    private fun openSse(sseUrl: String): SseChannel {
        sse?.let { return it }
        val req = base(sseUrl).header("Accept", "text/event-stream").get().build()
        val call = STREAM_HTTP.newCall(req)
        val resp = call.execute()
        if (!resp.isSuccessful) { resp.close(); throw McpError("SSE HTTP ${resp.code}${authHint(resp)}", resp.code) }
        val reader: BufferedReader = resp.body?.charStream()?.buffered() ?: throw McpError("empty SSE stream")
        val endpointQ = LinkedBlockingQueue<String>(1)
        lateinit var ch: SseChannel
        val holder = arrayOfNulls<SseChannel>(1)
        Thread({
            var event = "message"
            val data = StringBuilder()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                        line.isEmpty() -> {
                            val payload = data.toString().trim(); data.setLength(0)
                            if (event == "endpoint") endpointQ.offer(payload)
                            else if (payload.isNotEmpty()) {
                                val obj = runCatching { JSONObject(payload) }.getOrNull()
                                val rid = obj?.optInt("id", -1) ?: -1
                                if (obj != null && rid >= 0) holder[0]?.waiting?.get(rid)?.offer(obj)
                            }
                            event = "message"
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally { runCatching { resp.close() } }
        }, "mcp-sse").apply { isDaemon = true }.start()
        val ep = endpointQ.poll(20, TimeUnit.SECONDS) ?: run { call.cancel(); throw McpError("SSE server sent no endpoint event") }
        val endpoint = resolve(sseUrl, ep)
        ch = SseChannel(call, endpoint)
        holder[0] = ch
        sse = ch
        return ch
    }

    private fun sseRequest(msg: JSONObject, id: Int, timeoutMs: Long): JSONObject {
        val ch = sse ?: openSse(url)
        val q = LinkedBlockingQueue<JSONObject>(1)
        ch.waiting[id] = q
        try {
            postToSse(msg)
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw McpError("no reply within ${timeoutMs / 1000}s")
        } finally { ch.waiting.remove(id) }
    }

    private fun postToSse(msg: JSONObject) {
        val ch = sse ?: openSse(url)
        http.newCall(base(ch.endpoint).post(msg.toString().toRequestBody(JSON)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw McpError("POST ${resp.code}${authHint(resp)}: ${resp.body?.string()?.take(200)}", resp.code)
        }
    }

    private fun resolve(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val u = java.net.URI(baseUrl)
        return u.resolve(ref).toString()
    }

    companion object {
        private const val TAG = "McpClient"
        const val PROTOCOL = "2025-06-18"
        private val JSON = "application/json".toMediaType()
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(190, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        private val STREAM_HTTP: OkHttpClient = DEFAULT_HTTP.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

        /** The text parts of a tools/call result, for the model. */
        fun resultText(result: JSONObject, maxChars: Int = 30_000): String {
            val sb = StringBuilder()
            val content = result.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> sb.append(part.optString("text")).append('\n')
                    "image" -> sb.append("[image ${part.optString("mimeType")}, ${part.optString("data").length} base64 chars]\n")
                    "resource" -> part.optJSONObject("resource")?.let { r -> sb.append(r.optString("text").ifBlank { r.optString("uri") }).append('\n') }
                    "resource_link" -> sb.append("[resource ${part.optString("uri")}]\n")
                    else -> sb.append(part.toString()).append('\n')
                }
            }
            result.optJSONObject("structuredContent")?.let { if (sb.isEmpty()) sb.append(it.toString()) }
            var s = sb.toString().trimEnd()
            if (s.length > maxChars) s = s.take(maxChars / 2) + "\n…[truncated]…\n" + s.takeLast(maxChars / 2)
            return s
        }
    }
}
