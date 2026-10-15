package io.github.nanomuse.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** The relay's `/v1/sync` routes (contract C7). Every call blocks; callers stay off the main thread. */
interface SyncApi {
    @Throws(SyncException::class)
    fun state(): SyncState

    @Throws(SyncException::class)
    fun setEnabled(on: Boolean): SyncState

    /**
     * Changes after [since]. [scope] `all` or `main` (C9: the main conversation only); [tail]
     * with `since=0` asks for the newest K messages and their conversations instead of
     * everything — a fresh device's first pull. 0 = no tail.
     */
    @Throws(SyncException::class)
    fun changes(since: Long, limit: Int = 500, scope: String = SCOPE_ALL, tail: Int = 0): Changes

    @Throws(SyncException::class)
    fun push(device: String, conversations: List<OutConversation>, messages: List<OutMessage>): PushResult

    /**
     * Presence (C9): this device started ([working] true) or finished a turn on [cid]. Best
     * effort — the caller never retries and never blocks on it.
     */
    @Throws(SyncException::class)
    fun working(device: String, cid: String, working: Boolean)

    @Throws(SyncException::class)
    fun deleteConversation(cid: String)

    /** Wipes the account's store; the switch stays as it is. */
    @Throws(SyncException::class)
    fun wipe()

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_MAIN = "main"
    }
}

/** The wire shapes, apart so the parsing can be tested without a socket. */
object SyncJson {
    fun changes(o: JSONObject): Changes {
        val convs = o.optJSONArray("conversations") ?: JSONArray()
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        return Changes(
            cursor = o.optLong("cursor", 0),
            more = o.optBoolean("more", false),
            skipped = o.optInt("skipped", 0),
            conversations = (0 until convs.length()).mapNotNull { convs.optJSONObject(it) }.map {
                RemoteConversation(
                    cid = it.optString("cid"),
                    kind = it.optString("kind", "side"),
                    title = it.optString("title").takeIf { t -> t.isNotBlank() && !it.isNull("title") },
                    device = it.optString("device"),
                    deviceName = it.optString("device_name"),
                    createdAt = it.optLong("created_at"),
                    updatedAt = it.optLong("updated_at"),
                    deleted = it.optBoolean("deleted", false),
                    seq = it.optLong("seq"),
                )
            }.filter { it.cid.isNotBlank() },
            messages = (0 until msgs.length()).mapNotNull { msgs.optJSONObject(it) }.map {
                RemoteMessage(
                    mid = it.optString("mid"),
                    cid = it.optString("cid"),
                    seq = it.optLong("seq"),
                    device = it.optString("device"),
                    role = it.optString("role"),
                    text = if (it.isNull("text")) "" else it.optString("text"),
                    truncated = it.optBoolean("truncated", false),
                    attachments = attachments(it.optJSONArray("attachments")),
                    createdAt = it.optLong("created_at"),
                    deleted = it.optBoolean("deleted", false),
                    deviceName = it.optString("device_name"),
                )
            }.filter { it.mid.isNotBlank() },
        )
    }

    fun attachments(arr: JSONArray?): List<Attachment> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.map {
            Attachment(it.optString("name"), it.optString("mime"), it.optLong("size", 0))
        }.filter { it.name.isNotBlank() }

    fun attachments(list: List<Attachment>): JSONArray = JSONArray().also { arr ->
        for (a in list) arr.put(JSONObject().put("name", a.name).put("mime", a.mime).put("size", a.size))
    }

    fun pushBody(device: String, conversations: List<OutConversation>, messages: List<OutMessage>): JSONObject {
        val convs = JSONArray()
        for (c in conversations) {
            convs.put(
                JSONObject()
                    .put("cid", c.cid)
                    .put("kind", c.kind)
                    .put("title", c.title ?: JSONObject.NULL)
                    .put("created_at", c.createdAt)
                    .put("updated_at", c.updatedAt)
                    .apply { if (c.deleted) put("deleted", true) },
            )
        }
        val msgs = JSONArray()
        for (m in messages) {
            msgs.put(
                JSONObject()
                    .put("mid", m.mid)
                    .put("cid", m.cid)
                    .put("role", m.role)
                    .put("text", m.text)
                    .put("created_at", m.createdAt)
                    .apply {
                        if (m.attachments.isNotEmpty()) put("attachments", attachments(m.attachments))
                        if (m.deleted) put("deleted", true)
                    },
            )
        }
        return JSONObject().put("device", device).put("conversations", convs).put("messages", msgs)
    }

    fun pushResult(o: JSONObject): PushResult {
        val rej = o.optJSONArray("rejected") ?: JSONArray()
        return PushResult(
            cursor = o.optLong("cursor", 0),
            accepted = o.optInt("accepted", 0),
            rejected = (0 until rej.length()).mapNotNull { rej.optJSONObject(it) }.map {
                Rejection(
                    mid = it.optString("mid").takeIf { s -> s.isNotBlank() },
                    cid = it.optString("cid").takeIf { s -> s.isNotBlank() },
                    reason = it.optString("reason"),
                    cidMain = it.optString("cid_main").takeIf { s -> s.isNotBlank() },
                )
            },
        )
    }

    fun state(o: JSONObject): SyncState {
        val counts = o.optJSONObject("counts") ?: JSONObject()
        return SyncState(
            enabled = o.optBoolean("enabled", true),
            cursor = o.optLong("cursor", 0),
            conversations = counts.optInt("conversations", 0),
            messages = counts.optInt("messages", 0),
            working = working(o.optJSONArray("working")),
        )
    }

    /** `working[]` of the state, or one hub `working` frame's body ([working] with `working: false` gives null). */
    fun working(arr: JSONArray?): List<WorkingPresence> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.mapNotNull { working(it) }

    fun working(o: JSONObject): WorkingPresence? {
        if (o.has("working") && !o.optBoolean("working", true)) return null
        val cid = o.optString("cid")
        val from = o.optString("from")
        if (cid.isBlank() || from.isBlank()) return null
        return WorkingPresence(cid, from, o.optString("device_name"), o.optLong("at", 0))
    }
}

/**
 * The routes over HTTP, with the account's Bearer token — the same way [io.github.nanomuse.cloud.NanoMuseCloud]
 * talks to the relay. [baseUrl] without a trailing slash.
 */
class RelaySyncApi(
    private val baseUrl: String,
    private val token: String,
    private val userAgent: String,
    private val http: OkHttpClient = defaultClient,
) : SyncApi {
    override fun state(): SyncState = SyncJson.state(call("GET", "/v1/sync/state"))

    override fun setEnabled(on: Boolean): SyncState =
        SyncJson.state(call("PUT", "/v1/sync/state", JSONObject().put("enabled", on)))

    override fun changes(since: Long, limit: Int, scope: String, tail: Int): Changes =
        SyncJson.changes(call("GET", changesPath(since, limit, scope, tail)))

    override fun push(device: String, conversations: List<OutConversation>, messages: List<OutMessage>): PushResult =
        SyncJson.pushResult(call("POST", "/v1/sync/changes", SyncJson.pushBody(device, conversations, messages)))

    override fun working(device: String, cid: String, working: Boolean) {
        call("POST", "/v1/sync/working", JSONObject().put("cid", cid).put("working", working).put("device", device))
    }

    override fun deleteConversation(cid: String) {
        call("DELETE", "/v1/sync/conversations/$cid")
    }

    override fun wipe() {
        call("DELETE", "/v1/sync/changes")
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body?.toString() ?: "{}").toRequestBody(json))
        }
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw SyncException(0, "unreachable", e.message ?: "unreachable")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) {
                return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            }
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            // the relay says `{"error": "sync_off"}` here; its other routes say `{"error": {"code", "message"}}`
            val code = parsed?.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
                ?: parsed?.optString("error")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: "http_${r.code}"
            throw SyncException(r.code, code, parsed?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}")
        }
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** The query as the relay reads it; `scope` and `tail` only when they say something (an older relay ignores them anyway). */
        fun changesPath(since: Long, limit: Int, scope: String, tail: Int): String = buildString {
            append("/v1/sync/changes?since=").append(since).append("&limit=").append(limit)
            if (scope != SyncApi.SCOPE_ALL) append("&scope=").append(scope)
            if (since == 0L && tail > 0) append("&tail=").append(tail)
        }
    }
}
