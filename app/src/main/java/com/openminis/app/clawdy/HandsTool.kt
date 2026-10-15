package com.openminis.app.clawdy

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.tools.ToolExecutionResult
import io.github.nanomuse.hands.HandsOffloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `phone_hands` — a first-class agent tool for operating the phone's screen (accessibility
 * service + screen model). Upstream nanoMuse only reached the hands through the PRoot sandbox
 * shell (`nanomuse-hands run …` offloaded to [HandsOffloadHandler]); with the sandbox taken
 * out of the agent's tool list Clawdy calls the same handler directly.
 */
object HandsTool {
    const val NAME = "phone_hands"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Operate this phone's screen like a person would (tap, type, scroll inside other apps) through Clawdy Hands. " +
            "action=status reports whether Hands is switched on and ready; action=apps lists launchable apps; " +
            "action=run performs ONE clear task end to end (blocks until done) and returns a JSON summary; action=stop stops a running task. " +
            "Sensitive steps (sending messages, paying, deleting) are confirmed by the user; never type passwords or verification codes. " +
            "If it reports it is off or not ready, tell the user to enable it in 工具连接 and stop.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user, in the user's language."),
            "action" to AgentToolParam("string", "One of: status, apps, run, stop."),
            "task" to AgentToolParam("string", "For run: the one task to perform, written as a clear instruction."),
            "app" to AgentToolParam("string", "For run: optional app name to open first."),
            "max_steps" to AgentToolParam("integer", "For run: optional step cap."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "task", "app", "max_steps"),
    )

    suspend fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", NAME)
        val action = args.optString("action", "status").ifBlank { "status" }
        val argv = mutableListOf("nanomuse-hands", action)
        if (action == "run") {
            val task = args.optString("task", "")
            if (task.isBlank()) return ToolExecutionResult("Error: 'task' is required for run", false, toolTitle = title)
            argv += listOf("--task", task)
            args.optString("app", "").takeIf { it.isNotBlank() }?.let { argv += listOf("--app", it) }
            if (args.has("max_steps")) argv += listOf("--max-steps", args.optInt("max_steps").toString())
        }
        return withContext(Dispatchers.IO) {
            val r = runCatching {
                HandsOffloadHandler(context.applicationContext).handle(
                    NativeOffloadRequest(pid = 0, argv = argv, env = emptyMap(), cwd = "/", sessionId = sessionId)
                )
            }.getOrElse { e -> return@withContext ToolExecutionResult("Hands failed: ${e.message}", false, toolTitle = title) }
            ToolExecutionResult("${r.output.trimEnd()}\n(exit code: ${r.exitCode})", r.exitCode == 0, toolTitle = title)
        }
    }
}
