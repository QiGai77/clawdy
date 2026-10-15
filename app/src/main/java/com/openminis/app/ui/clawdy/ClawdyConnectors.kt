package com.openminis.app.ui.clawdy

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.clawdy.mcp.McpManager
import com.openminis.app.clawdy.mcp.McpOAuth
import com.openminis.app.clawdy.mcp.McpPresets
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.theme.Clawdy
import kotlinx.coroutines.launch

// ───────────────────────── 连接器（MCP） ─────────────────────────

private fun presetFor(id: String) = McpPresets.byId(id)

@Composable
fun ConnectorsScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val repo = remember { runCatching { (ctx.applicationContext as MinisApp).mcpRepository }.getOrNull() }
    if (repo == null) { ClawdyPage("连接器", onBack) { Note("连接器功能没有启动。") }; return }
    val servers by repo.servers.collectAsState()
    ResumeEffect { repo.reloadFromDisk() }
    ClawdyPage("连接器", onBack) {
        Hint("连接器就是 MCP 服务器：给 Clawdy 接上飞书、GitHub、地图等外部服务。远程的直接联网；本地的在 Termux 里运行（第一次用会自动下载，稍慢）。密钥加密存在本机的环境变量里。")
        SectionLabel("已添加")
        if (servers.isEmpty()) Note("还没有连接器。从下面选一个，或添加自定义的。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            servers.sortedByDescending { it.createdAt }.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                val p = presetFor(s.id)
                ClawdyRow(
                    p?.name ?: s.id,
                    subtitle = (if (s.isStdio) "本地 · Termux" else "远程") + (if (p != null && p.name != s.id) " · ${s.id}" else ""),
                    icon = p?.icon ?: ClawdyIcons.Server, iconTint = if (s.enabled) c.accent else c.text2,
                    onClick = { onEdit(s.id) },
                    trailing = { ClawdySwitch(s.enabled, { on -> repo.setEnabled(s.id, on); if (!on) McpManager.forget(s.id) }) },
                )
            }
        }
        SectionLabel("常用连接器")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            McpPresets.ALL.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                val added = servers.any { it.id == p.id }
                ClawdyRow(
                    p.name, subtitle = p.transport.label + " · " + p.status + if (added) " · 已添加" else "",
                    hint = p.description, icon = p.icon, iconTint = if (added) c.text2 else c.accent,
                    onClick = { onEdit(if (added) p.id else "preset:${p.id}") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ClawdyButton("添加自定义连接器", { onEdit("new") }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = ClawdyIcons.Plus, kind = ButtonKind.SECONDARY)
    }
}

/** [arg]: an existing server id, "preset:<id>" for a new one from a preset, or "new" for a custom one. */
@Composable
fun ConnectorEditScreen(arg: String, onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { runCatching { (ctx.applicationContext as MinisApp).mcpRepository }.getOrNull() } ?: run {
        ClawdyPage("连接器", onBack) { Note("连接器功能没有启动。") }; return
    }
    val servers by repo.servers.collectAsState()
    val existing = servers.firstOrNull { it.id == arg }
    val preset = when {
        arg.startsWith("preset:") -> presetFor(arg.removePrefix("preset:"))
        existing != null -> presetFor(existing.id)
        else -> null
    }
    val isNew = existing == null
    val usePresetForm = preset != null && isNew

    var name by remember(arg) { mutableStateOf(existing?.id ?: preset?.id ?: "") }
    var note by remember(arg) { mutableStateOf(existing?.note ?: preset?.description ?: "") }
    var stdio by remember(arg) { mutableStateOf(existing?.isStdio ?: (preset?.transport == McpPresets.Transport.STDIO)) }
    var url by remember(arg) { mutableStateOf(existing?.url ?: "") }
    var headers by remember(arg) { mutableStateOf(existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "") }
    var command by remember(arg) { mutableStateOf(existing?.let { (listOf(it.command.orEmpty()) + it.args).joinToString(" ") { a -> if (a.contains(' ')) "\"$a\"" else a } } ?: "") }
    var env by remember(arg) { mutableStateOf(existing?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "") }
    val values = remember(arg) { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val authorized = remember(arg, result) { existing?.let { McpOAuth.authorized(ctx, it.id) } ?: false }

    fun parsePairs(text: String, sep: Char): Map<String, String> = text.lines().mapNotNull { l ->
        val i = l.indexOf(sep); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()

    fun splitCommand(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quote: Char? = null
        for (ch in line.trim()) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun buildConfig(): MCPRepository.MCPServerConfig? {
        val id = name.trim()
        if (!Regex("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,40}$").matches(id)) { Toast.makeText(ctx, "名字只能用字母、数字、中文、- 和 _", Toast.LENGTH_SHORT).show(); return null }
        if (isNew && servers.any { it.id == id }) { Toast.makeText(ctx, "已经有叫 $id 的连接器了", Toast.LENGTH_SHORT).show(); return null }
        if (usePresetForm) {
            preset!!.fields.filter { !it.optional }.firstOrNull { values[it.key].isNullOrBlank() }?.let {
                Toast.makeText(ctx, "请填写 ${it.label}", Toast.LENGTH_SHORT).show(); return null
            }
            return McpPresets.build(ctx, preset, id, values).copy(note = note.trim().ifBlank { null })
        }
        return if (stdio) {
            val parts = splitCommand(command)
            if (parts.isEmpty()) { Toast.makeText(ctx, "请填写启动命令", Toast.LENGTH_SHORT).show(); return null }
            MCPRepository.MCPServerConfig(id = id, note = note.trim().ifBlank { null }, enabled = existing?.enabled ?: true,
                command = parts.first(), args = parts.drop(1), env = parsePairs(env, '='),
                startupTimeoutSeconds = existing?.startupTimeoutSeconds ?: 180, createdAt = existing?.createdAt ?: System.currentTimeMillis())
        } else {
            val u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) { Toast.makeText(ctx, "地址要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show(); return null }
            MCPRepository.MCPServerConfig(id = id, note = note.trim().ifBlank { null }, enabled = existing?.enabled ?: true,
                url = u, headers = parsePairs(headers, ':'), oauth = existing?.oauth, createdAt = existing?.createdAt ?: System.currentTimeMillis())
        }
    }

    fun save(then: ((String) -> Unit)? = null) {
        val cfg = buildConfig() ?: return
        if (isNew) repo.add(cfg) else repo.update(cfg)
        McpManager.forget(cfg.id)
        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
        then?.invoke(cfg.id)
    }

    val title = preset?.name ?: existing?.id ?: "自定义连接器"
    ClawdyPage(title, onBack) {
        preset?.let { p ->
            ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Text(p.description, color = c.text, style = serif(14.sp, line = 21.sp))
                Text("${p.transport.label} · ${p.status}" + (if (p.needs.isNotEmpty()) " · 需要：" + p.needs.joinToString("、") else ""),
                    color = c.text2, style = serif(12.sp, line = 18.sp), modifier = Modifier.padding(top = 6.dp))
                if (p.tip.isNotBlank()) Text(p.tip, color = c.text2, style = serif(12.sp, line = 18.sp), modifier = Modifier.padding(top = 6.dp))
            }
        }
        SectionLabel("名字")
        ClawdyTextField(name, { name = it.trim() }, "例如 amap", Modifier.padding(horizontal = 16.dp), mono = true)
        Hint("Clawdy 用这个名字调用连接器。")
        if (usePresetForm) {
            preset!!.fields.forEach { f ->
                SectionLabel(f.label + if (f.optional) "（可选）" else "")
                ClawdyTextField(values[f.key].orEmpty(), { values[f.key] = it }, f.placeholder, Modifier.padding(horizontal = 16.dp), mono = true,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            }
            if (preset.fields.isEmpty()) Note("这个连接器不需要填写任何信息。")
        } else {
            SectionLabel("类型")
            Segmented(listOf("远程（URL）", "本地（Termux 命令）"), if (stdio) 1 else 0, { stdio = it == 1 }, Modifier.padding(horizontal = 16.dp))
            if (stdio) {
                SectionLabel("启动命令")
                ClawdyTextField(command, { command = it }, "npx -y @scope/some-mcp-server --flag", Modifier.padding(horizontal = 16.dp), mono = true)
                SectionLabel("环境变量（每行 KEY=值，可写 \$\$变量名 引用加密的环境变量）")
                ClawdyTextField(env, { env = it }, "API_KEY=\$\$MY_KEY", Modifier.padding(horizontal = 16.dp).heightIn(min = 72.dp), singleLine = false, mono = true)
                Note("会在 Termux 里用 supergateway 启动，并只监听 127.0.0.1:${McpManager.portFor(name.ifBlank { "x" })}。日志在 ~/clawdy/mcp/。")
            } else {
                SectionLabel("地址（Streamable HTTP，或以 /sse 结尾的旧版 SSE）")
                ClawdyTextField(url, { url = it.trim() }, "https://example.com/mcp", Modifier.padding(horizontal = 16.dp), mono = true)
                SectionLabel("请求头（每行 名字: 值）")
                ClawdyTextField(headers, { headers = it }, "Authorization: Bearer \$\$MY_TOKEN", Modifier.padding(horizontal = 16.dp).heightIn(min = 72.dp), singleLine = false, mono = true)
            }
        }
        SectionLabel("说明（告诉 Clawdy 什么时候用它）")
        ClawdyTextField(note, { note = it }, "例如：查地点和路线时用", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClawdyButton(if (busy) "连接中…" else "测试连接", {
                save { id ->
                    busy = true; result = null
                    scope.launch { result = McpManager.test(ctx, id); busy = false }
                }
            }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !busy, icon = ClawdyIcons.Zap, height = 44.dp)
            ClawdyButton("保存", { save { if (isNew) onBack() } }, Modifier.weight(1f), height = 44.dp)
        }
        result?.let { (ok, msg) -> Note(msg, color = if (ok) c.success else c.error) }
        val remote = !(existing?.isStdio ?: stdio)
        if (remote && (preset?.oauth == true || existing?.oauth != null || result?.second?.contains("401") == true)) {
            Spacer(Modifier.height(10.dp))
            ClawdyButton(if (authorized) "已登录 · 重新登录" else "登录（OAuth）", {
                save { id ->
                    val s = repo.servers.value.firstOrNull { it.id == id } ?: return@save
                    busy = true
                    scope.launch {
                        val r = McpOAuth.connect(ctx, s)
                        busy = false
                        result = when (r) { is McpOAuth.Result.Ok -> true to "登录成功，点「测试连接」看看"; is McpOAuth.Result.Failed -> false to "登录失败：${r.message}" }
                    }
                }
            }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Key, enabled = !busy)
            if (authorized && existing != null) {
                Spacer(Modifier.height(8.dp))
                ClawdyButton("退出登录", { McpOAuth.signOut(ctx, existing.id); result = null }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.GHOST, height = 40.dp)
            }
        }
        if (existing != null) {
            Spacer(Modifier.height(16.dp))
            if (existing.isStdio) {
                ClawdyButton("停止（结束 Termux 里的进程）", { scope.launch { McpManager.stopStdio(ctx, existing.id); Toast.makeText(ctx, "已停止", Toast.LENGTH_SHORT).show() } },
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Stop, height = 44.dp)
                Spacer(Modifier.height(8.dp))
            }
            ClawdyButton("删除这个连接器", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog("删除「${existing.id}」？", "它的配置会删除；保存在环境变量里的密钥不会删除。", "删除", onDismiss = { confirmDelete = false }) {
        scope.launch { if (existing.isStdio) McpManager.stopStdio(ctx, existing.id) }
        McpOAuth.signOut(ctx, existing.id)
        repo.delete(existing.id); onBack()
    }
}
