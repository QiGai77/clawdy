package com.openminis.app.ui.clawdy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.MinisApp
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.EnvVarPrivacyStore
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.ui.theme.Clawdy
import io.github.nanomuse.hands.Hands
import io.github.nanomuse.net.OwnProviderProxy
import kotlinx.coroutines.launch

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

// ───────────────────────── 对话与上下文 ─────────────────────────

@Composable
fun ChatPrefsScreen(onBack: () -> Unit, onMemory: () -> Unit) {
    val ctx = LocalContext.current
    var compact by remember { mutableStateOf(AutoCompactPrefs.isEnabled()) }
    var memory by remember { mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(ctx)) }
    ClawdyPage("对话与上下文", onBack) {
        SectionLabel("上下文")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("自动压缩上下文", "对话接近模型上下文上限时，先把较早的内容总结压缩再继续，而不是报错", compact, ClawdyIcons.Layers) {
                compact = it; AutoCompactPrefs.setEnabled(ctx, it)
            }
        }
        Hint("每个模型的上下文上限在 设置 → 模型 → 配置 → 密钥 → 模型 里改；分组也可以单独设上限。")
        SectionLabel("记忆")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("使用记忆", "开：把 MEMORY.md 带进每次对话，并允许 Clawdy 更新它", memory, ClawdyIcons.Memory) {
                memory = it; MemoryGlobalPrefs.setGlobalEnabled(ctx, it)
            }
            RowDivider()
            ClawdyRow("人设与记忆", hint = "查看和编辑 MEMORY.md、自动记忆开关", icon = ClawdyIcons.File, onClick = onMemory)
        }
    }
}

// ───────────────────────── 记忆 ─────────────────────────

@Composable
fun MemoryScreen(memoryRepository: MemoryRepository?, onBack: () -> Unit, onFile: (String) -> Unit) {
    val c = Clawdy.colors
    var reload by remember { mutableIntStateOf(0) }
    val files = remember(reload) { runCatching { memoryRepository?.listAllFiles() }.getOrNull().orEmpty() }
    ResumeEffect { reload++ }
    ClawdyPage("记忆", onBack) {
        Hint("GLOBAL.md 是长期记忆；其余按日期记录每天发生的事。Clawdy 在对话里用 memory_write 写入。")
        if (memoryRepository == null) { Note("记忆功能没有启动。"); return@ClawdyPage }
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            files.forEachIndexed { i, f ->
                if (i > 0) RowDivider()
                ClawdyRow(if (f.isGlobal) "长期记忆（GLOBAL.md）" else f.name.removeSuffix(".md"),
                    subtitle = listOf(f.preview.take(40), f.fileSize, f.modifiedDate).filter { it.isNotBlank() }.joinToString(" · "),
                    icon = if (f.isGlobal) ClawdyIcons.Memory else ClawdyIcons.File, iconTint = if (f.isGlobal) c.accent else c.text2,
                    onClick = { onFile(f.name) })
            }
        }
    }
}

@Composable
fun MemoryFileScreen(memoryRepository: MemoryRepository?, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(name) { mutableStateOf(memoryRepository?.readFile(name).orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val global = name == "GLOBAL.md"
    ClawdyPage(if (global) "长期记忆" else name.removeSuffix(".md"), onBack) {
        ClawdyTextField(text, { text = it }, "（空）", Modifier.padding(horizontal = 16.dp).heightIn(min = 240.dp), singleLine = false)
        Spacer(Modifier.height(12.dp))
        ClawdyButton("保存", {
            runCatching { memoryRepository?.saveFile(name, text) }
                .onSuccess { Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        if (!global) {
            Spacer(Modifier.height(10.dp))
            ClawdyButton("删除这一天的记录", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
        }
    }
    if (confirmDelete) ConfirmDialog("删除 $name？", "删除后无法恢复。", "删除", onDismiss = { confirmDelete = false }) {
        memoryRepository?.deleteFile(name); onBack()
    }
}

// ───────────────────────── 技能 ─────────────────────────

@Composable
fun SkillsScreen(skillRepository: SkillRepository?, onBack: () -> Unit, onSkill: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (skillRepository == null) { ClawdyPage("技能", onBack) { Note("技能功能没有启动。") }; return }
    val skills by skillRepository.skills.collectAsState()
    var url by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    ClawdyPage("技能", onBack) {
        Hint("技能是一份 SKILL.md 说明书（可带脚本），Clawdy 遇到相关任务时会先读它。关掉的技能不会出现在提示词里。")
        if (skills.isEmpty()) Note("还没有技能。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            skills.sortedBy { it.name.lowercase() }.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                ClawdyRow(s.name, subtitle = s.description.take(60).ifBlank { "v${s.version}" }, icon = ClawdyIcons.Puzzle,
                    iconTint = if (s.isEnabled) c.accent else c.text2, onClick = { onSkill(s.id) },
                    trailing = { ClawdySwitch(s.isEnabled, { on -> skillRepository.setEnabled(s.id, on) }) })
            }
        }
        SectionLabel("从 GitHub 导入")
        ClawdyTextField(url, { url = it }, "https://github.com/用户/仓库/tree/main/技能目录", Modifier.padding(horizontal = 16.dp), mono = true)
        Spacer(Modifier.height(8.dp))
        ClawdyButton(if (importing) "导入中…" else "导入", {
            importing = true
            scope.launch {
                val r = runCatching { skillRepository.importFromGitHub(url.trim()) }
                importing = false
                val s = r.getOrNull()
                if (s != null) { url = ""; Toast.makeText(ctx, "已导入 ${s.name}", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(ctx, "导入失败：" + (r.exceptionOrNull()?.message ?: "没有找到 SKILL.md"), Toast.LENGTH_LONG).show()
            }
        }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = !importing && url.startsWith("https://"), icon = ClawdyIcons.Upload)
    }
}

@Composable
fun SkillScreen(skillRepository: SkillRepository?, skillId: String, onBack: () -> Unit) {
    val c = Clawdy.colors
    val skills by (skillRepository?.skills ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val s = skills.firstOrNull { it.id == skillId }
    var confirmDelete by remember { mutableStateOf(false) }
    if (s == null || skillRepository == null) { ClawdyPage("技能", onBack) { Note("这个技能已经删除。") }; return }
    val files = remember(skillId) { runCatching { skillRepository.listSkillFiles(skillId) }.getOrDefault(emptyList()) }
    ClawdyPage(s.name, onBack) {
        if (s.description.isNotBlank()) Text(s.description, color = c.text, style = serif(14.sp, line = 21.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow("启用", "版本 ${s.version}" + (s.sourceURL?.let { " · 来自 GitHub" } ?: ""), s.isEnabled, ClawdyIcons.Puzzle) { skillRepository.setEnabled(s.id, it) }
        }
        if (files.size > 1) { SectionLabel("文件"); Note(files.joinToString("\n")) }
        SectionLabel("SKILL.md")
        Text(s.body.ifBlank { skillRepository.readSkillFile(s.id, "SKILL.md").orEmpty() }.take(20_000), color = c.codeText, style = Mono,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.codeBg).padding(12.dp))
        Spacer(Modifier.height(16.dp))
        ClawdyButton("删除技能", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
    }
    if (confirmDelete) ConfirmDialog("删除技能「${s.name}」？", "它的 SKILL.md 和附带文件都会删除。", "删除", onDismiss = { confirmDelete = false }) {
        skillRepository.delete(s.id); onBack()
    }
}

// ───────────────────────── 环境变量 ─────────────────────────

@Composable
fun EnvVarsScreen(envVarRepository: EnvVarRepository?, onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    if (envVarRepository == null) { ClawdyPage("环境变量", onBack) { Note("环境变量没有启动。") }; return }
    val entries by envVarRepository.entries.collectAsState()
    val privacy by EnvVarPrivacyStore.enabled.collectAsState()
    var editing by remember { mutableStateOf<EnvVarRepository.EnvVarEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    ClawdyPage("环境变量", onBack) {
        Hint("每次 termux_run 运行前都会 export 这些变量（比如 GITHUB_TOKEN），脚本里直接用 \$名字。值加密保存在本机，模型只知道变量名。")
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow("隐私模式", "命令输出里出现变量的值时，先打码再给模型", privacy, ClawdyIcons.EyeOff) { EnvVarPrivacyStore.setEnabled(it) }
        }
        if (entries.isNotEmpty()) ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            entries.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                ClawdyRow(e.key, subtitle = e.note.ifBlank { "值已加密" }, icon = ClawdyIcons.Variable, onClick = { editing = e })
            }
        }
        Spacer(Modifier.height(12.dp))
        ClawdyButton("添加变量", { adding = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = ClawdyIcons.Plus)
    }
    if (adding || editing != null) {
        val e = editing
        var key by remember(e) { mutableStateOf(e?.key ?: "") }
        var value by remember(e) { mutableStateOf("") }
        var note by remember(e) { mutableStateOf(e?.note ?: "") }
        fun close() { adding = false; editing = null }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { close() }, containerColor = c.surface, shape = com.openminis.app.ui.theme.ClawdyTokens.CardShape,
            title = { Text(if (e == null) "添加变量" else "编辑 ${e.key}", style = serif(18.sp, FontWeight.SemiBold), color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClawdyTextField(key, { key = it.trim() }, "名字，例如 GITHUB_TOKEN", mono = true)
                    ClawdyTextField(value, { value = it }, if (e == null) "值" else "新的值（留空不改）", mono = true, visualTransformation = PasswordVisualTransformation())
                    ClawdyTextField(note, { note = it }, "备注（可选）")
                    if (e != null) Text("删除", color = c.error, style = serif(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp).clickable {
                        envVarRepository.delete(e.id); close()
                    })
                }
            },
            confirmButton = {
                ClawdyButton("保存", {
                    when {
                        !envVarRepository.isValidKey(key) -> Toast.makeText(ctx, "名字只能用字母、数字和下划线，且不能以数字开头", Toast.LENGTH_LONG).show()
                        envVarRepository.isDuplicateKey(key, e?.id) -> Toast.makeText(ctx, "已经有 $key 了", Toast.LENGTH_SHORT).show()
                        e == null && value.isEmpty() -> Toast.makeText(ctx, "请填写值", Toast.LENGTH_SHORT).show()
                        e == null -> { envVarRepository.add(key, value, note.trim()); close() }
                        else -> { envVarRepository.update(e.id, key, value.ifEmpty { envVarRepository.getValue(e.key).orEmpty() }, note.trim()); close() }
                    }
                }, height = 40.dp)
            },
            dismissButton = { ClawdyButton("取消", { close() }, kind = ButtonKind.GHOST, height = 40.dp) },
        )
    }
}

// ───────────────────────── 网络代理 ─────────────────────────

@Composable
fun NetworkProxyScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(OwnProviderProxy.config(ctx)) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "") }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.password) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testUrl by remember { mutableStateOf("https://api.deepseek.com/") }
    fun current() = cfg.copy(host = host.trim(), port = port.trim().toIntOrNull() ?: 0, user = user.trim(), password = pass)
    ClawdyPage("网络代理", onBack) {
        Hint("一个 HTTP 代理，只给模型服务商的请求用（官方地址和你填的 Base URL）。Termux、网页浏览和其他请求不走它。默认关闭。")
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow("使用代理", if (cfg.usable) "已启用 ${cfg.address}" else null, cfg.enabled, ClawdyIcons.Globe) { on ->
                val next = current().copy(enabled = on)
                if (on && !next.usable) Toast.makeText(ctx, "先填好地址和端口", Toast.LENGTH_SHORT).show()
                else { cfg = next; OwnProviderProxy.save(ctx, next) }
            }
        }
        SectionLabel("地址")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyTextField(host, { host = it }, "127.0.0.1", Modifier.weight(1f), mono = true)
            Spacer(Modifier.width(8.dp))
            ClawdyTextField(port, { port = it.filter(Char::isDigit).take(5) }, "端口", Modifier.width(100.dp), mono = true)
        }
        SectionLabel("认证（可选）")
        ClawdyTextField(user, { user = it }, "用户名", Modifier.padding(horizontal = 16.dp), mono = true)
        Spacer(Modifier.height(8.dp))
        ClawdyTextField(pass, { pass = it }, "密码", Modifier.padding(horizontal = 16.dp), mono = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClawdyButton(if (testing) "测试中…" else "测试", {
                val probe = current()
                if (probe.host.isBlank() || probe.port !in 1..65535) { result = false to "地址或端口不对"; return@ClawdyButton }
                testing = true; result = null
                scope.launch {
                    val p = runCatching { OwnProviderProxy.probe(probe, testUrl.trim()) }.getOrNull()
                    testing = false
                    result = when {
                        p == null -> false to "测试失败"
                        p.ok -> true to "通过代理访问 ${p.host} 成功 · HTTP ${p.status} · ${p.millis}ms"
                        else -> false to "访问 ${p.host} 失败：" + (p.failure?.detail?.ifBlank { null } ?: "HTTP ${p.status}")
                    }
                }
            }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !testing, height = 44.dp)
            ClawdyButton("保存", {
                val next = current()
                cfg = next; OwnProviderProxy.save(ctx, next)
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f), height = 44.dp)
        }
        SectionLabel("测试地址")
        ClawdyTextField(testUrl, { testUrl = it }, "https://api.deepseek.com/", Modifier.padding(horizontal = 16.dp), mono = true)
        result?.let { (ok, msg) -> Text(msg, color = if (ok) c.success else c.error, style = serif(13.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
    }
}

// ───────────────────────── 通知与后台 ─────────────────────────

@Composable
fun ResumeEffect(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
fun BackgroundScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MinisApp
    val repo = app.backgroundSettingsRepository
    val notif by repo.taskNotificationsEnabled.collectAsState()
    val overlay by repo.backgroundOverlayEnabled.collectAsState()
    var ignoring by remember { mutableStateOf(PowerOptimizationManager.isIgnoringBatteryOptimizations(ctx)) }
    ResumeEffect { ignoring = PowerOptimizationManager.isIgnoringBatteryOptimizations(ctx) }
    val oem = remember { PowerOptimizationManager.needsOemAutostartGuidance() }
    ClawdyPage("通知与后台", onBack) {
        SectionLabel("通知")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("任务完成提醒", "在后台跑完一个任务时发通知", notif, ClawdyIcons.Bell) { repo.setTaskNotificationsEnabled(it) }
            RowDivider()
            SwitchRow("后台运行时显示悬浮提示", "切到其他应用时，用小浮窗显示任务进度", overlay, ClawdyIcons.Layers) { repo.setBackgroundOverlayEnabled(it) }
        }
        SectionLabel("电量与后台")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("不受电池优化限制", subtitle = if (ignoring) "已允许" else "未允许", hint = if (ignoring) "长任务不会被系统暂停" else "建议开启，否则锁屏后任务可能被系统杀掉",
                icon = ClawdyIcons.Battery, iconTint = if (ignoring) c.success else c.amber, onClick = if (ignoring) null else ({
                    ctx.activityOrNull()?.let { PowerOptimizationManager.requestBatteryOptimizationExemption(it) }
                    Unit
                }))
            if (oem) {
                RowDivider()
                ClawdyRow("自启动 / 后台管理", hint = "国产系统（MIUI、ColorOS、EMUI 等）还要在这里允许 Clawdy 后台运行", icon = ClawdyIcons.Settings, onClick = {
                    ctx.activityOrNull()?.let { if (!PowerOptimizationManager.openOemAutostartSettings(it)) PowerOptimizationManager.openAppDetailsSettings(it) }
                    Unit
                })
            }
        }
    }
}

// ───────────────────────── Hands 设置 ─────────────────────────

@Composable
fun HandsSettingsScreen(providerRepository: ProviderRepository, onBack: () -> Unit, onSecurity: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val config by providerRepository.config.collectAsState()
    var chosen by remember { mutableStateOf(Hands.modelEntryId(ctx)) }
    val textOnly = !com.openminis.app.clawdy.ClawdySecurity.screenshotUpload(ctx)
    val current = remember(chosen, config.revision) { Hands.screenModel(ctx) ?: if (textOnly) Hands.textModel(ctx) else null }
    val auto = remember(config.revision) { Hands.screenModel(ctx, automatic = true) ?: if (textOnly) Hands.textModel(ctx) else null }
    val candidates = remember(config.revision, textOnly) {
        if (textOnly) providerRepository.allVisibleEntries() else Hands.visionEntries(ctx).map { it.second }
    }
    ClawdyPage("Hands 设置", onBack) {
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ClawdyRow("看屏幕的方式", subtitle = if (textOnly) "只发屏幕文字（任何模型都能用）" else "发截图（需要能识图的模型）", icon = ClawdyIcons.Eye, onClick = onSecurity)
        }
        Hint("在 设置 → 安全 → 截图上传 里切换。文字模式更省流量、更私密；遇到图片多的界面（游戏、地图）用截图模式更准。")
        SectionLabel("操作屏幕用的模型")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("自动", subtitle = auto?.label ?: "没有可用的模型", onClick = { chosen = null; Hands.setModelEntryId(ctx, null) },
                trailing = { if (chosen == null) Icon(ClawdyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) })
            candidates.forEach { e ->
                RowDivider(16.dp)
                val inst = config.instances.firstOrNull { it.id == e.providerInstanceId }
                ClawdyRow(e.model.displayName, subtitle = inst?.label, onClick = { chosen = e.id; Hands.setModelEntryId(ctx, e.id) },
                    trailing = { if (chosen == e.id) Icon(ClawdyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) })
            }
        }
        current?.let { Note("当前使用：${it.label}") }
        if (candidates.isEmpty()) Note(if (textOnly) "先在 模型配置 里添加一个服务商。" else "没有能识图的模型。打开某个模型的「识图」开关，或者关掉截图上传改用文字模式。")
    }
}

// ───────────────────────── 定时任务 ─────────────────────────

@Composable
fun ScheduledScreen(onBack: () -> Unit, onEdit: (String?) -> Unit, onRuns: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val manager = remember { ScheduledTaskManager(ctx.applicationContext) }
    val tasks by manager.store().observe().collectAsState(initial = manager.list())
    var deleting by remember { mutableStateOf<String?>(null) }
    ClawdyPage("定时任务", onBack, actions = { ClawdyIconButton(ClawdyIcons.Plus, "新建", { onEdit(null) }, tint = c.accent) }) {
        Hint("到点后 Clawdy 会自己开一个对话执行你写好的提示词，完成后发通知。")
        if (tasks.isEmpty()) Note("还没有定时任务。点右上角 + 新建。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            tasks.forEachIndexed { i, t ->
                if (i > 0) RowDivider()
                val time = "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute)
                val rep = when (t.repeatMode) {
                    ScheduledRepeatMode.ONCE -> "一次"
                    ScheduledRepeatMode.DAILY -> "每天"
                    ScheduledRepeatMode.WEEKDAYS -> "工作日"
                    ScheduledRepeatMode.CUSTOM -> "每周 " + t.customDays.sorted().joinToString("") { d -> listOf("", "日", "一", "二", "三", "四", "五", "六").getOrElse(d) { "" } }
                }
                Column {
                    ClawdyRow(t.label.ifBlank { t.prompt.take(20) }, subtitle = "$rep $time · " + t.prompt.take(40), icon = ClawdyIcons.Clock,
                        iconTint = if (t.enabled) c.accent else c.text2, onClick = { onEdit(t.id) },
                        trailing = { ClawdySwitch(t.enabled, { on -> manager.setEnabled(t.id, on) }) })
                    Row(Modifier.padding(start = 64.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("运行记录", color = c.accent, style = serif(13.sp), modifier = Modifier.clickable { onRuns(t.id) })
                        Text("删除", color = c.error, style = serif(13.sp), modifier = Modifier.clickable { deleting = t.id })
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        ConfirmDialog("删除这个定时任务？", "已经运行过的对话不会删除。", "删除", onDismiss = { deleting = null }) { manager.delete(id) }
    }
}

