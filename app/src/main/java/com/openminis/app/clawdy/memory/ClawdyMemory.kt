package com.openminis.app.clawdy.memory

import android.content.Context
import com.openminis.app.clawdy.ClawdyPrefs
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Clawdy 0.3.0 memory: ONE file, `MEMORY.md`, in `minis-global/memory/` (the folder SOUL.md and the
 * old GLOBAL.md / daily logs live in). It is injected into every conversation's system prompt
 * (through [com.openminis.app.data.repository.MemoryRepository], whose global file is now MEMORY.md)
 * and kept current automatically: after each completed assistant turn — debounced, so a burst of
 * turns makes one update — a background call to the conversation's own model reads the new part of
 * the conversation plus the current MEMORY.md and returns the merged file.
 *
 * Secrets never go in: the update prompt forbids them, values filled from the 密码箱 are redacted
 * from the conversation before it is sent, and the returned file is filtered line by line
 * ([scrub]) before it is written. Vault values never reach the conversation in the first place
 * (vault_fill fills on-device), so this is a second and third fence, not the only one.
 *
 * Migration (once): GLOBAL.md, USER.md and the dated daily logs are merged into MEMORY.md and the
 * originals moved to `memory/_archive-0.2/`.
 */
object ClawdyMemory {
    private const val TAG = "ClawdyMemory"
    const val FILE = "MEMORY.md"
    const val KEY_AUTO = "auto_memory"            // default on
    private const val DEBOUNCE_MS = 6_000L
    private const val MAX_FILE_CHARS = 8_000
    private const val MAX_TRANSCRIPT_CHARS = 12_000
    private const val NO_CHANGE = "NO_CHANGE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()
    /** How many messages of a session were already folded into memory. */
    private val processed = ConcurrentHashMap<String, Int>()

    private val _lastUpdate = MutableStateFlow<Long?>(null)
    val lastUpdate: StateFlow<Long?> = _lastUpdate.asStateFlow()

    fun dir(context: Context) = File(context.filesDir, "minis-global/memory").apply { mkdirs() }
    fun file(context: Context) = File(dir(context), FILE)

    fun read(context: Context): String = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun write(context: Context, text: String) {
        file(context).writeText(scrub(text))
        _lastUpdate.value = System.currentTimeMillis()
    }

    fun clear(context: Context) {
        file(context).writeText(TEMPLATE)
        _lastUpdate.value = System.currentTimeMillis()
    }

    fun autoEnabled(context: Context) = ClawdyPrefs.bool(context, KEY_AUTO, true)
    fun setAutoEnabled(context: Context, on: Boolean) = ClawdyPrefs.setBool(context, KEY_AUTO, on)

    val TEMPLATE = """
        # MEMORY.md

        ## 基本信息

        ## 习惯与偏好

        ## 爱好

        ## 人际关系

        ## 正在做的事

        ## 随手记
    """.trimIndent() + "\n"

    // ── migration ──────────────────────────────────────────────────────────

    private val DAILY = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

    /** Merges the 0.2 memory files into MEMORY.md once. Safe to call on every start. */
    fun migrate(context: Context) {
        runCatching {
            val d = dir(context)
            val target = File(d, FILE)
            val global = File(d, "GLOBAL.md")
            val user = File(d, "USER.md")
            val dailies = d.listFiles()?.filter { it.isFile && DAILY.matches(it.name) }?.sortedByDescending { it.name }.orEmpty()
            if (!global.exists() && !user.exists() && dailies.isEmpty()) {
                if (!target.exists()) target.writeText(TEMPLATE)
                return
            }
            val sb = StringBuilder(if (target.exists()) target.readText().trimEnd() + "\n\n" else TEMPLATE.trimEnd() + "\n\n")
            sb.append("## 从旧版记忆合并（").append(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())).append("）\n\n")
            user.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append("### 关于我（USER.md）\n").append(it).append("\n\n") }
            global.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append("### 长期记忆（GLOBAL.md）\n").append(it).append("\n\n") }
            dailies.take(30).forEach { f ->
                val body = f.readText().replace(Regex("<!--.*?-->"), "").trim()
                if (body.isNotEmpty()) sb.append("### ").append(f.name.removeSuffix(".md")).append("\n").append(body.take(2000)).append("\n\n")
            }
            target.writeText(scrub(sb.toString()))
            val archive = File(d, "_archive-0.2").apply { mkdirs() }
            (listOf(global, user) + dailies).filter { it.exists() }.forEach { f -> f.renameTo(File(archive, f.name)) }
            AppLogger.info(TAG, "migrated ${dailies.size} daily logs + GLOBAL/USER into MEMORY.md")
        }.onFailure { AppLogger.warning(TAG, "migration failed: ${it.message}") }
    }

    // ── automatic update ───────────────────────────────────────────────────

    /**
     * Called after a completed assistant turn. [messages] is the whole conversation as (role, text);
     * only the part not yet folded in is sent, with a little earlier context.
     */
    fun afterTurn(context: Context, sessionId: String, provider: LLMProvider?, messages: List<Pair<String, String>>) {
        val app = context.applicationContext
        if (provider == null || !autoEnabled(app) || !MemoryGlobalPrefs.isGlobalEnabled(app)) return
        if (messages.none { it.first == "user" }) return
        pending.remove(sessionId)?.cancel()
        pending[sessionId] = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { update(app, sessionId, provider, messages) }
                .onFailure { AppLogger.warning(TAG, "memory update failed: ${it.javaClass.simpleName} ${it.message}") }
            pending.remove(sessionId)
        }
    }

    private suspend fun update(context: Context, sessionId: String, provider: LLMProvider, messages: List<Pair<String, String>>) {
        val done = processed[sessionId] ?: 0
        if (messages.size <= done) return
        val from = (done - 2).coerceAtLeast(0)
        val redact = com.openminis.app.clawdy.vault.VaultTools.redactions()
        val transcript = buildString {
            messages.drop(from).forEach { (role, text) ->
                var t = text
                redact.forEach { v -> if (v.isNotEmpty()) t = t.replace(v, "[已遮盖]") }
                append(if (role == "user") "用户：" else "Clawdy：").append(t.take(3000)).append("\n\n")
            }
        }.takeLast(MAX_TRANSCRIPT_CHARS)
        val current = read(context).ifBlank { TEMPLATE }
        val prompt = "当前 MEMORY.md：\n```markdown\n$current\n```\n\n新的对话片段：\n```\n$transcript\n```\n\n" +
            "请输出更新后的完整 MEMORY.md（只输出文件内容本身，不要代码围栏）；如果没有值得记住的新信息，只输出 $NO_CHANGE。"
        val reply = withTimeout(90_000) {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, content = prompt)), SYSTEM, maxTokens = 3000, temperature = 0.2, thinkingLevel = ThinkingLevel.OFF).text
        }.trim()
        processed[sessionId] = messages.size
        if (reply.isEmpty() || reply.startsWith(NO_CHANGE)) return
        var body = reply.removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
        if (!body.startsWith("#")) return // not a file; ignore rather than overwrite
        if (body.length > MAX_FILE_CHARS) body = body.take(MAX_FILE_CHARS)
        write(context, body + "\n")
        AppLogger.info(TAG, "MEMORY.md updated (${body.length} chars) after session ${sessionId.take(8)}")
    }

    private val SYSTEM = """
        你负责维护 Clawdy 的长期记忆文件 MEMORY.md。它记录关于用户本人的、以后的对话还用得上的事实。
        分区：## 基本信息（称呼、所在城市、职业、学校、时区、语言）、## 习惯与偏好（作息、饮食、沟通方式、喜欢/讨厌的做法）、## 爱好、## 人际关系（家人、朋友、同事：称呼 + 关系 + 关键事实）、## 正在做的事（项目、计划、目标，写清楚截至何时）、## 随手记（其他值得记住的）。
        规则：
        - 只记录用户明确说过或清楚体现的、长期有效的信息；一次性的任务细节、闲聊内容、Clawdy 自己做了什么，都不要记。
        - 合并去重：已有的信息更新而不是重复；与新信息冲突的旧信息改成新的；不再成立的删掉。
        - 每条一行，用「- 」开头，简短具体，可以带日期。保留用户原有的其他内容和分区。
        - 绝对不要写入：密码、验证码、API Key、Token、银行卡号、CVV、身份证号、密码箱里的任何内容、别人的隐私细节。
        - 总长度控制在 6000 字以内。
        - 不需要更新时只输出 NO_CHANGE。
    """.trimIndent()

    // ── the secret fence ──────────────────────────────────────────────────

    private val SECRET_LINE = listOf(
        Regex("""(?i)\b(sk|pk|ghp|gho|github_pat|xox[abp]|AKIA)[-_A-Za-z0-9]{10,}"""),
        Regex("""\b\d{13,19}\b"""),                                  // card / id numbers
        Regex("""\b\d{17}[\dXx]\b"""),                                // 身份证
        Regex("""(?i)(password|passwd|密码|口令|验证码|cvv|安全码|api[ _-]?key|token|secret)\s*[:：=是为]\s*\S+"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9._-]{16,}"""),
    )

    /** Drops any line that looks like it carries a secret, and any 密码箱 value filled this run. */
    fun scrub(text: String): String {
        val redact = runCatching { com.openminis.app.clawdy.vault.VaultTools.redactions() }.getOrDefault(emptyList()).filter { it.length >= 4 }
        return text.lines().filterNot { line ->
            SECRET_LINE.any { it.containsMatchIn(line) } || redact.any { line.contains(it) }
        }.joinToString("\n")
    }
}
