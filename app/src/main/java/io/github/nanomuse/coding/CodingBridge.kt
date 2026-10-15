package io.github.nanomuse.coding

import io.github.nanomuse.hub.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The coding agents on another computer of the account, over the hub: the `coding.*` actions
 * the runtime there answers (docs/coding-agents.md). Blocking hub calls are moved off the main
 * thread; a `coding.send` is followed step by step until the agent is done.
 */
object CodingBridge {
    /** A coding agent as the computer reports it. */
    data class Agent(val id: String, val name: String, val installed: Boolean, val version: String, val running: Int)

    /** One of the agent's sessions on that computer; `id` is empty for one not started yet. */
    data class Session(
        val agent: String,
        val id: String,
        val title: String,
        val workspace: String,
        val updatedAt: Long,
        val messages: Int,
        val status: String,
        val lastUser: String,
        val lastAssistant: String,
        val resumable: Boolean,
    )

    data class Message(val role: String, val text: String)

    /** A message in flight: what was sent and what has come back so far. */
    data class LiveRun(
        val text: String,
        val id: String = "",
        val sessionId: String = "",
        val status: String = "running",
        /** Finished paragraphs. */
        val output: String = "",
        /** The paragraph being written (Cursor streams deltas, then sends the whole message). */
        val current: String = "",
        val lastTool: String = "",
        val tools: Int = 0,
        val error: String = "",
    )

    private const val LIST_TIMEOUT = 30_000L
    private const val SEND_TIMEOUT = 30 * 60_000L

    suspend fun agents(device: String): List<Agent> = withContext(Dispatchers.IO) {
        val arr = Hub.call(device, "coding.agents", JSONObject(), LIST_TIMEOUT).optJSONArray("agents") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Agent(
                id = it.optString("id"),
                name = it.optString("name").ifBlank { it.optString("id") },
                installed = it.optBoolean("installed"),
                version = it.optString("version"),
                running = it.optInt("running"),
            )
        }
    }

    suspend fun sessions(device: String, agent: String?, limit: Int = 40): List<Session> = withContext(Dispatchers.IO) {
        val args = JSONObject().put("limit", limit)
        if (!agent.isNullOrBlank()) args.put("agent", agent)
        val arr = Hub.call(device, "coding.sessions", args, LIST_TIMEOUT).optJSONArray("sessions") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::session)
    }

    private fun session(o: JSONObject) = Session(
        agent = o.optString("agent"),
        id = o.optString("id"),
        title = o.optString("title"),
        workspace = o.optString("workspace"),
        updatedAt = o.optDouble("updated_at", 0.0).toLong(),
        messages = o.optInt("messages"),
        status = o.optString("status", "idle"),
        lastUser = o.optString("last_user"),
        lastAssistant = o.optString("last_assistant"),
        resumable = o.optBoolean("resumable", true),
    )

    suspend fun transcript(device: String, agent: String, sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        val args = JSONObject().put("agent", agent).put("session_id", sessionId)
        val arr = Hub.call(device, "coding.session", args, LIST_TIMEOUT).optJSONArray("transcript") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Message(role = it.optString("role"), text = it.optString("text")) }
            .filter { it.text.isNotBlank() }
    }

    /**
     * Send [text] to the agent and follow the run. [onUpdate] is called with every step (any
     * thread); the returned run is the final state.
     */
    suspend fun send(
        device: String,
        agent: String,
        text: String,
        sessionId: String,
        workspace: String,
        onUpdate: (LiveRun) -> Unit,
    ): LiveRun = withContext(Dispatchers.IO) {
        var run = LiveRun(text = text, sessionId = sessionId)
        val args = JSONObject()
            .put("agent", agent)
            .put("text", text)
            .put("session_id", sessionId)
            .put("workspace", workspace)
            .put("wait", true)
        val final = Hub.call(device, "coding.send", args, SEND_TIMEOUT) { ev ->
            run = when (ev.optString("kind")) {
                "started" -> run.copy(id = ev.optString("run", run.id), sessionId = ev.optString("session_id").ifBlank { run.sessionId })
                "text" -> {
                    val t = ev.optString("text")
                    if (ev.optBoolean("partial", false)) {
                        run.copy(current = run.current + t)
                    } else {
                        run.copy(output = listOf(run.output, t).filter { it.isNotBlank() }.joinToString("\n"), current = "")
                    }
                }
                "tool" -> run.copy(tools = run.tools + 1, lastTool = ev.optString("text"))
                else -> run
            }
            onUpdate(run)
        }
        val status = final.optString("status", "done")
        val output = final.optString("output")
        run.copy(
            id = final.optString("id", run.id),
            sessionId = final.optString("session_id").ifBlank { run.sessionId },
            status = status,
            output = if (output.isNotBlank()) output else listOf(run.output, run.current).filter { it.isNotBlank() }.joinToString("\n"),
            current = "",
            tools = final.optInt("tools", run.tools),
            error = final.optString("error"),
        )
    }

    suspend fun stop(device: String, runId: String): Boolean = withContext(Dispatchers.IO) {
        Hub.call(device, "coding.stop", JSONObject().put("run", runId), LIST_TIMEOUT).optBoolean("stopped")
    }
}
