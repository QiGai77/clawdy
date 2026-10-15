package com.openminis.app.ui.clawdy

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ToolBlockStatus
import org.json.JSONObject

/** One rendered row of the Clawdy conversation. */
sealed class TurnItem {
    abstract val key: String
    data class User(val message: ChatMessage) : TurnItem() { override val key get() = "u_" + message.id }
    data class Assistant(
        val messages: List<ChatMessage>,
        val steps: List<Step>,
        val text: String,
        val streaming: Boolean,
        val error: String?,
        /** The user message this turn answers (for 重试). */
        val userMessageId: String?,
    ) : TurnItem() {
        override val key get() = "a_" + messages.first().id
        val lastAssistantId get() = messages.last().id
    }
}

enum class StepState { DONE, RUNNING, PENDING, FAILED, STOPPED }

data class Step(
    val block: AssistantBlock,
    val messageId: String,
    val state: StepState,
    val title: String,
    val toolLabel: String,
    val command: String?,
    val exitCode: Int?,
    val workspace: String?,
) {
    val isTermux get() = block.toolName == "termux_run"
    val isThinking get() = block.kind == "thinking"
}

enum class CardState { RUNNING, WAITING, DONE, FAILED, STOPPED }

object Turns {
    private val EXIT_RE = Regex("""exit code[:\s]+(-?\d+)""", RegexOption.IGNORE_CASE)

    fun build(messages: List<ChatMessage>): List<TurnItem> {
        val out = ArrayList<TurnItem>()
        var pending = ArrayList<ChatMessage>()
        var lastUser: String? = null
        fun flush() {
            if (pending.isEmpty()) return
            out += assistantTurn(pending, lastUser)
            pending = ArrayList()
        }
        for (m in messages) {
            when {
                m.role == "user" -> { flush(); out += TurnItem.User(m); lastUser = m.id }
                m.role == "assistant" && !m.isInternalBridge -> pending += m
                else -> {}
            }
        }
        flush()
        return out
    }

    private fun assistantTurn(msgs: List<ChatMessage>, userId: String?): TurnItem.Assistant {
        val steps = ArrayList<Step>()
        val text = StringBuilder()
        val streaming = msgs.any { it.isStreaming || it.isAwaitingModelResponse }
        for (m in msgs) {
            val textBlocks = m.toolBlocks.filter { it.isText }
            val body = if (m.content.isNotBlank()) m.content else textBlocks.joinToString("\n\n") { it.content }
            if (body.isNotBlank()) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(body.trim())
            }
            for (b in m.toolBlocks) {
                if (b.kind == "tool_use" || (b.kind == "thinking" && b.content.isNotBlank())) {
                    steps += step(b, m.id, streaming)
                }
            }
        }
        return TurnItem.Assistant(msgs, steps, text.toString(), streaming, msgs.lastOrNull { it.error != null }?.error, userId)
    }

    private fun step(b: AssistantBlock, messageId: String, streaming: Boolean): Step {
        val args = runCatching { JSONObject(b.toolArgs) }.getOrNull()
        val state = when (b.toolStatus) {
            ToolBlockStatus.SUCCESS -> StepState.DONE
            ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> StepState.FAILED
            ToolBlockStatus.CANCELLED -> StepState.STOPPED
            ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING -> if (streaming) StepState.RUNNING else StepState.STOPPED
            ToolBlockStatus.PENDING -> if (streaming) StepState.PENDING else StepState.STOPPED
            null -> if (b.kind == "thinking") StepState.DONE else if (streaming) StepState.RUNNING else StepState.DONE
        }
        val command = args?.optString("command")?.takeIf { it.isNotBlank() }
            ?: args?.optString("task")?.takeIf { it.isNotBlank() }
            ?: args?.optString("url")?.takeIf { it.isNotBlank() }
            ?: args?.optString("path")?.takeIf { it.isNotBlank() }
        val exit = EXIT_RE.findAll(b.content).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val title = when {
            b.kind == "thinking" -> "思考"
            b.toolTitle.isNotBlank() -> b.toolTitle
            args?.optString("tool_title")?.isNotBlank() == true -> args.optString("tool_title")
            else -> toolLabel(b.toolName)
        }
        return Step(
            block = b, messageId = messageId, state = state, title = title,
            toolLabel = if (b.kind == "thinking") "思考" else toolLabel(b.toolName),
            command = command, exitCode = exit,
            workspace = args?.optString("workspace")?.takeIf { it.isNotBlank() }?.let { "~/clawdy/$it" }
                ?: if (b.toolName == "termux_run") "~/clawdy/default" else null,
        )
    }

    fun toolLabel(name: String): String = when (name) {
        "termux_run" -> "Termux"
        "phone_hands" -> "Hands"
        "shell_execute" -> "沙箱"
        "browser_use" -> "联网"
        "file_read" -> "读取文件"
        "file_write" -> "写入文件"
        "file_edit" -> "编辑文件"
        "read_image" -> "看图"
        "memory_write", "memory_get" -> "记忆"
        "web_search" -> "搜索"
        else -> name
    }

    fun cardState(turn: TurnItem.Assistant, waiting: Boolean): CardState = when {
        waiting && turn.streaming -> CardState.WAITING
        turn.streaming -> CardState.RUNNING
        turn.steps.any { it.state == StepState.FAILED } && turn.text.isBlank() -> CardState.FAILED
        turn.error != null && turn.text.isBlank() -> CardState.FAILED
        turn.steps.any { it.state == StepState.STOPPED } -> CardState.STOPPED
        else -> CardState.DONE
    }

    /** Wall time of the turn in ms (first step start → last step end), or null when unknown. */
    fun elapsedMs(steps: List<Step>, now: Long, running: Boolean): Long? {
        val starts = steps.mapNotNull { it.block.startTimeMs.takeIf { t -> t > 0 } }
        val first = starts.minOrNull() ?: return null
        if (running) return now - first
        val end = steps.maxOfOrNull { s ->
            if (s.block.startTimeMs > 0) s.block.startTimeMs + s.block.durationMs else 0L
        } ?: return null
        return (end - first).coerceAtLeast(0)
    }

    fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}秒" else "${s / 60}分${s % 60}秒"
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ── produced files ──
    private val PATH_RE = Regex(
        """(/storage/emulated/0/|/sdcard/|~/storage/shared/)[^\s)\]'"`<>，。]+\.(docx|pptx|xlsx|pdf|png|jpe?g|webp|md|txt|csv|html?|zip|mp4|mp3|m4a|wav|json)""",
        RegexOption.IGNORE_CASE,
    )

    fun producedFiles(text: String): List<String> =
        PATH_RE.findAll(text).map { m ->
            m.value.replace("~/storage/shared/", "/storage/emulated/0/").replace("/sdcard/", "/storage/emulated/0/")
        }.distinct().toList()
}
