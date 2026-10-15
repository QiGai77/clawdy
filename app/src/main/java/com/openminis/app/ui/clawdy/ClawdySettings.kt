package com.openminis.app.ui.clawdy

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.BuildConfig
import com.openminis.app.clawdy.ClawdyPresets
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── shared bits ─────────────────────────

/**
 * Small-rounded-rect segmented control (no pills). 0.3.0: it fills the width it is given and every
 * option gets an equal share, so it belongs on its own line ([SegmentedRow]) rather than squeezed
 * next to a title — the 0.2.0 "离开后多久上锁" row rendered its title one character per line.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Clawdy.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(2.dp)) {
        options.forEachIndexed { i, l ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (selected == i) c.accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 4.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (selected == i) Color.White else c.text2, style = serif(13.sp, FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Title (and optional data subtitle / hint) on top, the segmented control on its own full-width line below. */
@Composable
fun SegmentedRow(
    title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, subtitle: String? = null, hint: String? = null,
) {
    ClawdyRow(title, subtitle = subtitle, hint = hint, icon = icon, below = { Segmented(options, selected, onSelect) })
}

/** Explanatory footnote: shown only with 外观 → 显示说明文字. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    if (!showHints()) return
    Text(text, color = Clawdy.colors.text2, style = serif(12.sp, line = 18.sp), modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

/** A message the user needs whatever the setting (empty states, results, warnings). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = Clawdy.colors.text2) {
    Text(text, color = color, style = serif(13.sp, line = 19.sp), modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ClawdyRow(title, hint = subtitle, icon = icon, trailing = { ClawdySwitch(checked, { onChange(it) }, enabled = enabled) })
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Clawdy.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, shape = ClawdyTokens.CardShape,
        title = { Text(title, style = serif(18.sp, FontWeight.SemiBold), color = c.text) },
        text = { Text(text, style = serif(14.sp, line = 21.sp), color = c.text) },
        confirmButton = { ClawdyButton(confirm, { onConfirm(); onDismiss() }, kind = if (danger) ButtonKind.DANGER else ButtonKind.PRIMARY, height = 40.dp) },
        dismissButton = { ClawdyButton("取消", onDismiss, kind = ButtonKind.GHOST, height = 40.dp) },
    )
}

private fun fmtTokens(n: Int?): String = when {
    n == null || n <= 0 -> "未设置"
    n >= 1_000_000 && n % 1_000_000 == 0 -> "${n / 1_000_000}M"
    n >= 1000 -> "${n / 1000}K"
    else -> n.toString()
}

/** "128k", "1m", "8192" → tokens. */
private fun parseTokens(s: String): Int? {
    val t = s.trim().lowercase().replace(",", "")
    if (t.isEmpty()) return null
    val mul = when { t.endsWith("m") -> 1_000_000; t.endsWith("k") -> 1000; else -> 1 }
    val num = t.trimEnd('k', 'm').toDoubleOrNull() ?: return null
    return (num * mul).toInt().takeIf { it > 0 }
}

// ───────────────────────── 15 settings (top level) ─────────────────────────

data class SettingsNav(
    val persona: () -> Unit,
    val models: () -> Unit,
    val security: () -> Unit,
    val tools: () -> Unit,
    val skills: () -> Unit,
    val connectors: () -> Unit,
    val scheduled: () -> Unit,
    val appearance: () -> Unit,
    val background: () -> Unit,
    val backup: () -> Unit,
    val advanced: () -> Unit,
    val licenses: () -> Unit,
)

/**
 * 0.3.0 top level, kept simple: 人设 (avatar row: SOUL.md, 对话方式, MEMORY.md) · 模型 · 安全 ·
 * 能力 (技能, 连接器, 工具连接, 定时任务) · 外观 · 系统 (通知与后台, 备份与恢复, 高级设置) · 关于.
 * Everything a beginner would not need (环境变量, 运行日志, 网络代理, 上下文细节, 分组故障切换,
 * Hands 屏幕模型, 调试) lives under 高级设置.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, nav: SettingsNav, providerRepository: ProviderRepository) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val mode = remember { com.openminis.app.clawdy.ClawdySecurity.mode(ctx) }
    val soulName = remember { com.openminis.app.agent.SoulStore.load(ctx)?.metadata?.name?.ifBlank { null } ?: "Clawdy" }
    val mcpCount = remember { runCatching { (ctx.applicationContext as com.openminis.app.MinisApp).mcpRepository?.servers?.value?.count { it.enabled } }.getOrNull() ?: 0 }
    ClawdyPage("设置", onBack) {
        Row(Modifier.fillMaxWidth().clickable(onClick = nav.persona).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyAvatar(AvatarMood.IDLE, 56.dp, breathe = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(soulName, color = c.text, style = serif(18.sp, FontWeight.SemiBold))
                Text("人设 · 对话方式 · 记忆", color = c.text2, style = serif(13.sp))
            }
            Icon(ClawdyIcons.ChevronRight, null, tint = c.text2, modifier = Modifier.size(18.dp))
        }
        SectionLabel("模型")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            val n = config.instances.size
            ClawdyRow("配置", subtitle = if (n == 0) "还没有服务商" else "$n 个密钥 · ${config.modelGroups.size} 个分组", icon = ClawdyIcons.Sliders, onClick = nav.models)
        }
        SectionLabel("安全")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("安全", subtitle = "工作模式：" + when (mode) {
                io.github.nanomuse.guard.GateMode.CAUTIOUS -> "谨慎"
                io.github.nanomuse.guard.GateMode.RELAXED -> "放手"
                else -> "标准"
            }, hint = "密码箱、隐私、敏感应用、已记住的授权", icon = ClawdyIcons.Shield, onClick = nav.security)
        }
        SectionLabel("能力")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("技能", hint = "启用、查看、从 GitHub 导入", icon = ClawdyIcons.Puzzle, onClick = nav.skills)
            RowDivider()
            ClawdyRow("连接器", subtitle = if (mcpCount > 0) "$mcpCount 个已启用" else null, hint = "MCP：飞书、GitHub、高德地图、12306 等", icon = ClawdyIcons.Plug, onClick = nav.connectors)
            RowDivider()
            ClawdyRow("工具连接", subtitle = "Termux · Hands · Shizuku", icon = ClawdyIcons.Wrench, onClick = nav.tools)
            RowDivider()
            ClawdyRow("定时任务", icon = ClawdyIcons.Clock, onClick = nav.scheduled)
        }
        SectionLabel("外观")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("外观", hint = "主题、字体、字号、强调色、引用块与代码块", icon = ClawdyIcons.Palette, onClick = nav.appearance)
        }
        SectionLabel("系统")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("通知与后台", hint = "任务提醒、电池优化、自启动", icon = ClawdyIcons.Battery, onClick = nav.background)
            RowDivider()
            ClawdyRow("备份与恢复", hint = "导出 / 导入聊天、服务商与设置", icon = ClawdyIcons.Upload, onClick = nav.backup)
            RowDivider()
            ClawdyRow("高级设置", hint = "环境变量、运行日志、网络代理、上下文与分组细节", icon = ClawdyIcons.Settings, onClick = nav.advanced)
        }
        SectionLabel("关于")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("版本", icon = ClawdyIcons.Info, trailing = { Text(BuildConfig.VERSION_NAME, color = c.text2, style = Mono) })
            RowDivider()
            ClawdyRow("开源许可", subtitle = "GPL-3.0", hint = "基于 nanoMuse / OpenMinis", icon = ClawdyIcons.File, onClick = nav.licenses)
            RowDivider()
            ClawdyRow("反馈问题", hint = "github.com/QiGai77/clawdy/issues", icon = ClawdyIcons.External, onClick = {
                ToolStatusLinks.open(ctx, "https://github.com/QiGai77/clawdy/issues")
            })
        }
    }
}

object ToolStatusLinks {
    fun open(ctx: android.content.Context, url: String) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "没有可打开链接的应用", Toast.LENGTH_SHORT).show() }
    }
}

// ───────────────────────── 模型配置 ─────────────────────────

@Composable
fun ModelConfigScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onKey: (String) -> Unit,
    onGroups: () -> Unit,
) {
    val c = Clawdy.colors
    val config by providerRepository.config.collectAsState()
    var picker by remember { mutableStateOf(false) }
    val defaultGroup = config.modelGroups.firstOrNull { it.id == config.defaultPrimaryGroupId }
    val defaultEntry = defaultGroup?.memberEntryIds?.firstNotNullOfOrNull { id -> config.modelEntries.firstOrNull { it.id == id } }
    ClawdyPage("模型配置", onBack) {
        SectionLabel("添加服务商")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("添加服务商", hint = "DeepSeek、OpenAI、Claude、Gemini、通义、智谱、Kimi、OpenRouter 或自定义", icon = ClawdyIcons.Plus, onClick = onAddProvider)
        }
        SectionLabel("默认模型")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow(
                defaultEntry?.model?.displayName ?: "自动（最近使用的模型）",
                subtitle = defaultGroup?.let { "新对话使用分组「${it.name}」" } ?: "新对话使用的模型",
                icon = ClawdyIcons.Sparkle, onClick = { picker = true },
            )
        }
        SectionLabel("密钥")
        if (config.instances.isEmpty()) Note("还没有密钥。先添加一个服务商，默认推荐 ${ClawdyPresets.DEFAULT_MODEL_NAME}。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            config.instances.forEachIndexed { i, inst ->
                if (i > 0) RowDivider()
                val n = providerRepository.visibleEntries(inst.id).size
                ClawdyRow(
                    inst.label, subtitle = (inst.customBaseURL ?: "官方地址") + " · $n 个模型" + if (!inst.isEnabled) " · 已停用" else "",
                    icon = ClawdyIcons.Key, iconTint = if (inst.isEnabled) c.accent else c.text2, onClick = { onKey(inst.id) },
                )
            }
        }
        SectionLabel("分组管理")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("模型分组", subtitle = if (config.modelGroups.isEmpty()) null else "${config.modelGroups.size} 个分组", hint = "把几个模型编成一组，失败时自动换下一个", icon = ClawdyIcons.Grid, onClick = onGroups)
        }
    }
    if (picker) DefaultModelSheet(providerRepository) { picker = false }
}

/** Picks the model new chats use: it becomes the only member of the "默认模型" group, which is the default group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultModelSheet(providerRepository: ProviderRepository, onDismiss: () -> Unit) {
    val c = Clawdy.colors
    val config by providerRepository.config.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ClawdyTokens.SheetShape, containerColor = c.surface, dragHandle = { SheetGrab() }) {
        Column(Modifier.navigationBarsPadding().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("默认模型", color = c.text, style = serif(18.sp, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Hint("新对话和定时任务都用它。也可以在「分组管理」里把一个分组设为默认。")
            ClawdyRow("自动", hint = "不指定，沿用最近使用的模型", onClick = {
                providerRepository.defaultPrimaryGroupId = null; onDismiss()
            }, trailing = { if (config.defaultPrimaryGroupId == null) Icon(ClawdyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) })
            config.instances.filter { it.isEnabled }.forEach { inst ->
                SectionLabel(inst.label)
                providerRepository.visibleEntries(inst.id).forEach { e ->
                    val g = config.modelGroups.firstOrNull { it.id == config.defaultPrimaryGroupId }
                    val isDefault = g?.memberEntryIds?.firstOrNull() == e.id
                    ClawdyRow(e.model.displayName, subtitle = e.model.id, onClick = {
                        setDefaultEntry(providerRepository, e); onDismiss()
                    }, trailing = { if (isDefault) Icon(ClawdyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) })
                }
            }
        }
    }
}

const val DEFAULT_GROUP_NAME = "默认模型"

fun setDefaultEntry(repo: ProviderRepository, e: ModelEntry) {
    val cfg = repo.config.value
    val existing = cfg.modelGroups.firstOrNull { it.name == DEFAULT_GROUP_NAME }
    val gid = if (existing != null) {
        repo.updateGroup(existing.copy(memberEntryIds = mutableListOf(e.id)))
        existing.id
    } else {
        val g = ModelGroup(name = DEFAULT_GROUP_NAME, memberEntryIds = mutableListOf(e.id))
        repo.addGroup(g); g.id
    }
    repo.defaultPrimaryGroupId = gid
}

// ───────────────────────── 密钥详情 ─────────────────────────

@Composable
fun KeyDetailScreen(providerRepository: ProviderRepository, instanceId: String, onBack: () -> Unit, onEntry: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by providerRepository.config.collectAsState()
    val inst = config.instances.firstOrNull { it.id == instanceId }
    if (inst == null) { ClawdyPage("密钥", onBack) { Note("这个密钥已经删除。") }; return }
    var name by remember(instanceId) { mutableStateOf(inst.label) }
    var base by remember(instanceId) { mutableStateOf(inst.customBaseURL.orEmpty()) }
    var newKey by remember(instanceId) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var addId by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val savedKey = remember(instanceId, config.revision) { providerRepository.loadApiKey(instanceId) }
    val dirty = name.trim() != inst.label || base.trim() != inst.customBaseURL.orEmpty() || newKey.isNotBlank()

    fun save() {
        val b = base.trim().trimEnd('/')
        if (b.isNotEmpty() && !b.startsWith("http")) { Toast.makeText(ctx, "地址要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show(); return }
        io.github.nanomuse.net.LanOnly.problem(b)?.let { Toast.makeText(ctx, "明文 http:// 只能用于局域网地址，请改用 https://", Toast.LENGTH_LONG).show(); return }
        providerRepository.updateInstance(inst.copy(label = name.trim().ifBlank { inst.label }, customBaseURL = b.ifEmpty { null }))
        if (newKey.isNotBlank()) { providerRepository.saveApiKey(inst.id, newKey.trim()); newKey = "" }
        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
    }

    ClawdyPage(inst.label, onBack) {
        SectionLabel("名称")
        ClawdyTextField(name, { name = it }, "给这个密钥起个名字", Modifier.padding(horizontal = 16.dp))
        SectionLabel("请求地址（Base URL）")
        ClawdyTextField(base, { base = it }, "留空 = 官方地址", Modifier.padding(horizontal = 16.dp), mono = true)
        Hint(if (inst.appendV1Suffix) "请求会自动在地址后加 /v1" else "按填写的地址原样请求（不自动加 /v1）")
        SectionLabel("API Key")
        ClawdyTextField(
            newKey, { newKey = it }, if (savedKey.isNullOrBlank()) "还没有保存 Key" else "已保存 ····${savedKey.takeLast(4)}（填写即替换）",
            Modifier.padding(horizontal = 16.dp), mono = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { ClawdyIconButton(if (showKey) ClawdyIcons.EyeOff else ClawdyIcons.Eye, "显示", { showKey = !showKey }, tint = c.text2) },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClawdyButton(if (busy) "刷新中…" else "刷新模型列表", {
                busy = true; note = null
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { providerRepository.refreshModels(providerRepository.instance(inst.id) ?: inst) } }
                    busy = false
                    note = r.fold({ "已刷新，共 ${providerRepository.visibleEntries(inst.id).size} 个模型" }, { "刷新失败：${it.message?.take(80)}" })
                }
            }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !busy, icon = ClawdyIcons.Retry, height = 44.dp)
            ClawdyButton("保存", { save() }, Modifier.weight(1f), enabled = dirty, height = 44.dp)
        }
        note?.let { Note(it) }
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SwitchRow("启用", "停用后它的模型不会出现在模型选择里", inst.isEnabled, ClawdyIcons.Zap) { on ->
                providerRepository.updateInstance(inst.copy(isEnabled = on))
            }
            if (inst.supportsAzureMode || inst.providerType == com.openminis.app.data.model.ProviderType.openAI) {
                RowDivider()
                SwitchRow("使用 Responses API", "OpenAI 新接口（GPT-6 需要）", inst.useResponsesAPI, ClawdyIcons.Sparkle) { on ->
                    providerRepository.updateInstance(inst.copy(useResponsesAPI = on))
                }
            }
        }
        SectionLabel("模型（点开可设置上下文、输出长度、推理、识图）")
        val entries = config.modelEntries.filter { it.providerInstanceId == inst.id }
        if (entries.isEmpty()) Note("没有模型。点「刷新模型列表」，或在下面手动添加模型 ID。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            entries.sortedWith(compareBy({ it.isHidden }, { it.model.displayName.lowercase() })).forEachIndexed { i, e ->
                if (i > 0) RowDivider(16.dp)
                val m = e.model
                ClawdyRow(
                    m.displayName, subtitle = buildString {
                        append(m.id)
                        append(" · 上下文 ").append(fmtTokens(m.contextWindow))
                        if (m.supportsReasoning == true) append(" · 推理")
                        if (m.hasImageInput) append(" · 识图")
                        if (e.isHidden) append(" · 已隐藏")
                    }, onClick = { onEntry(e.id) },
                )
            }
        }
        SectionLabel("手动添加模型")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyTextField(addId, { addId = it }, "模型 ID，例如 gpt-6-astra", Modifier.weight(1f), mono = true)
            Spacer(Modifier.width(8.dp))
            ClawdyButton("添加", {
                val id = addId.trim()
                if (config.modelEntries.any { it.providerInstanceId == inst.id && it.baseModel.id == id }) {
                    Toast.makeText(ctx, "已经有这个模型了", Toast.LENGTH_SHORT).show()
                } else {
                    providerRepository.addEntry(ModelEntry(providerInstanceId = inst.id, baseModel = LLMModel(id = id, displayName = id, provider = inst.label, inputModalities = listOf("text")), isCustom = true))
                    addId = ""
                }
            }, enabled = addId.isNotBlank(), height = 48.dp)
        }
        Spacer(Modifier.height(20.dp))
        ClawdyButton("删除这个密钥", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
    }
    if (confirmDelete) ConfirmDialog("删除「${inst.label}」？", "它的 API Key 和模型都会从本机删除，用到它的分组会失去这些模型。", "删除", onDismiss = { confirmDelete = false }) {
        providerRepository.removeInstance(inst.id); onBack()
    }
}

// ───────────────────────── 模型详情 ─────────────────────────

@Composable
fun ModelEntryScreen(providerRepository: ProviderRepository, entryId: String, onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val e = config.modelEntries.firstOrNull { it.id == entryId }
    if (e == null) { ClawdyPage("模型", onBack) { Note("这个模型已经删除。") }; return }
    val o = e.overrides
    val base = e.baseModel
    var name by remember(entryId) { mutableStateOf(o.displayName ?: "") }
    var ctxText by remember(entryId) { mutableStateOf(o.contextWindow?.toString() ?: "") }
    var outText by remember(entryId) { mutableStateOf(o.maxOutputTokens?.toString() ?: "") }
    var reasoning by remember(entryId) { mutableStateOf(e.model.supportsReasoning == true) }
    var vision by remember(entryId) { mutableStateOf(e.model.hasImageInput) }
    var maxThink by remember(entryId) { mutableStateOf(o.maxThinkingLevel) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val ctxN = if (ctxText.isBlank()) null else parseTokens(ctxText) ?: run { Toast.makeText(ctx, "上下文长度看不懂，例如 128k", Toast.LENGTH_SHORT).show(); return }
        val outN = if (outText.isBlank()) null else parseTokens(outText) ?: run { Toast.makeText(ctx, "最大输出看不懂，例如 8k", Toast.LENGTH_SHORT).show(); return }
        val baseVision = base.hasImageInput
        val inputs = (base.inputModalities ?: listOf("text")).toMutableList()
        if (vision && "image" !in inputs) inputs += "image"
        if (!vision) inputs.remove("image")
        providerRepository.updateEntry(
            e.copy(
                overrides = o.copy(
                    displayName = name.trim().ifBlank { null },
                    contextWindow = ctxN,
                    maxOutputTokens = outN,
                    supportsReasoning = if (reasoning == (base.supportsReasoning == true)) null else reasoning,
                    inputModalities = if (vision == baseVision) null else inputs,
                    maxThinkingLevel = maxThink,
                ),
                userModifiedAt = System.currentTimeMillis(),
            ),
        )
        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
    }

    ClawdyPage(e.model.displayName, onBack) {
        Text(base.id, color = c.text2, style = Mono, modifier = Modifier.padding(horizontal = 20.dp))
        SectionLabel("显示名称")
        ClawdyTextField(name, { name = it }, base.displayName, Modifier.padding(horizontal = 16.dp))
        SectionLabel("最大上下文窗口")
        ClawdyTextField(ctxText, { ctxText = it }, "默认 ${fmtTokens(base.contextWindow)}，例如 128k / 1m", Modifier.padding(horizontal = 16.dp), mono = true)
        SectionLabel("最大输出长度")
        ClawdyTextField(outText, { outText = it }, "默认 ${fmtTokens(base.maxOutputTokens)}，例如 8k", Modifier.padding(horizontal = 16.dp), mono = true)
        Hint("留空表示用服务商 / models.dev 报告的数值。")
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow("推理（深度思考）", "模型支持先思考再回答", reasoning, ClawdyIcons.Brain) { reasoning = it }
            RowDivider()
            SwitchRow("识图", "能读图片；Hands 截图模式需要", vision, ClawdyIcons.Image) { vision = it }
            RowDivider()
            SwitchRow("在模型选择里显示", null, !e.isHidden, ClawdyIcons.Eye) { show -> providerRepository.updateEntry(e.copy(isHidden = !show)) }
        }
        if (reasoning) {
            SectionLabel("推理强度上限")
            val levels = listOf<ThinkingLevel?>(null, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX)
            Segmented(listOf("默认", "低", "中", "高", "最高"), levels.indexOf(maxThink).coerceAtLeast(0), { maxThink = levels[it] }, Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
        ClawdyButton("保存", { save() }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        if (e.isCustom) {
            Spacer(Modifier.height(10.dp))
            ClawdyButton("删除这个模型", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
        }
    }
    if (confirmDelete) ConfirmDialog("删除模型？", "手动添加的模型 ${base.id} 会被删除。", "删除", onDismiss = { confirmDelete = false }) {
        providerRepository.removeEntry(e.id); onBack()
    }
}

// ───────────────────────── 分组管理 ─────────────────────────

@Composable
fun GroupsScreen(providerRepository: ProviderRepository, onBack: () -> Unit, onGroup: (String) -> Unit) {
    val c = Clawdy.colors
    val config by providerRepository.config.collectAsState()
    var newName by remember { mutableStateOf("") }
    ClawdyPage("分组管理", onBack) {
        Hint("一个分组是一串模型：按顺序使用，前一个失败（限流、出错）就自动换下一个；也可以轮流使用来分摊额度。")
        if (config.modelGroups.isNotEmpty()) ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            config.modelGroups.forEachIndexed { i, g ->
                if (i > 0) RowDivider()
                val names = g.memberEntryIds.mapNotNull { id -> config.modelEntries.firstOrNull { it.id == id }?.model?.displayName }
                ClawdyRow(
                    g.name, subtitle = (if (names.isEmpty()) "空分组" else names.joinToString(" → ")) + if (g.id == config.defaultPrimaryGroupId) " · 默认" else "",
                    icon = ClawdyIcons.Grid, iconTint = if (g.id == config.defaultPrimaryGroupId) c.accent else c.text2, onClick = { onGroup(g.id) },
                )
            }
        }
        SectionLabel("新建分组")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyTextField(newName, { newName = it }, "分组名称", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ClawdyButton("新建", {
                val g = ModelGroup(name = newName.trim())
                providerRepository.addGroup(g); newName = ""; onGroup(g.id)
            }, enabled = newName.isNotBlank(), height = 48.dp)
        }
    }
}

@Composable
fun GroupDetailScreen(providerRepository: ProviderRepository, groupId: String, onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val g = config.modelGroups.firstOrNull { it.id == groupId }
    if (g == null) { ClawdyPage("分组", onBack) { Note("这个分组已经删除。") }; return }
    var name by remember(groupId) { mutableStateOf(g.name) }
    var limitText by remember(groupId) { mutableStateOf(g.contextLimitTokens?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    fun update(block: (ModelGroup) -> ModelGroup) { providerRepository.updateGroup(block(g.copy(memberEntryIds = g.memberEntryIds.toMutableList()))) }

    ClawdyPage(g.name, onBack) {
        SectionLabel("名称")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyTextField(name, { name = it }, "分组名称", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ClawdyButton("改名", { update { it.copy(name = name.trim()) } }, enabled = name.isNotBlank() && name.trim() != g.name, height = 48.dp)
        }
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SwitchRow("设为默认", "新对话使用这个分组", g.id == config.defaultPrimaryGroupId, ClawdyIcons.Sparkle) { on ->
                providerRepository.defaultPrimaryGroupId = if (on) g.id else null
            }
            RowDivider()
            SegmentedRow("使用方式", listOf("依次备用", "轮流使用"), if (g.strategy == RoutingStrategy.loadBalance) 1 else 0, { i ->
                update { it.copy(strategy = if (i == 1) RoutingStrategy.loadBalance else RoutingStrategy.fallback) }
            }, icon = ClawdyIcons.Retry, hint = "依次备用：前一个失败才换下一个；轮流使用：分摊额度")
            RowDivider()
            SegmentedRow("何时换下一个", listOf("限流或服务出错", "任何错误"), if (g.fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always) 1 else 0, { i ->
                update { it.copy(fallbackStrategy = if (i == 1) com.openminis.app.data.model.FallbackStrategy.always else com.openminis.app.data.model.FallbackStrategy.default) }
            }, icon = ClawdyIcons.Alert)
        }
        SectionLabel("上下文上限（可选）")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyTextField(limitText, { limitText = it }, "不限制；例如 64k，超出时压缩", Modifier.weight(1f), mono = true)
            Spacer(Modifier.width(8.dp))
            ClawdyButton("应用", {
                val v = if (limitText.isBlank()) null else parseTokens(limitText)
                if (limitText.isNotBlank() && v == null) Toast.makeText(ctx, "看不懂这个数字", Toast.LENGTH_SHORT).show()
                else update { it.copy(contextLimitTokens = v, lastContextLimitTokens = v ?: it.lastContextLimitTokens) }
            }, height = 48.dp)
        }
        SectionLabel("成员（按顺序，点上下箭头调整）")
        val members = g.memberEntryIds.mapNotNull { id -> config.modelEntries.firstOrNull { it.id == id } }
        if (members.isEmpty()) Note("还没有模型，在下面勾选。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            members.forEachIndexed { i, e ->
                if (i > 0) RowDivider(16.dp)
                val inst = config.instances.firstOrNull { it.id == e.providerInstanceId }
                ClawdyRow("${i + 1}. ${e.model.displayName}", subtitle = inst?.label, trailing = {
                    Row {
                        ClawdyIconButton(ClawdyIcons.ChevronUp, "上移", {
                            update { gg -> gg.memberEntryIds.apply { if (i > 0) add(i - 1, removeAt(i)) }; gg }
                        }, size = 36.dp, iconSize = 16.dp, tint = c.text2, enabled = i > 0)
                        ClawdyIconButton(ClawdyIcons.ChevronDown, "下移", {
                            update { gg -> gg.memberEntryIds.apply { if (i < size - 1) add(i + 1, removeAt(i)) }; gg }
                        }, size = 36.dp, iconSize = 16.dp, tint = c.text2, enabled = i < members.size - 1)
                        ClawdyIconButton(ClawdyIcons.Close, "移出", {
                            update { gg -> gg.memberEntryIds.remove(e.id); gg }
                        }, size = 36.dp, iconSize = 16.dp, tint = c.text2)
                    }
                })
            }
        }
        SectionLabel("添加模型")
        config.instances.filter { it.isEnabled }.forEach { inst ->
            val avail = providerRepository.visibleEntries(inst.id).filter { it.id !in g.memberEntryIds }
            if (avail.isEmpty()) return@forEach
            Text(inst.label, color = c.text2, style = serif(12.sp), modifier = Modifier.padding(start = 24.dp, top = 6.dp, bottom = 4.dp))
            ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                avail.forEachIndexed { i, e ->
                    if (i > 0) RowDivider(16.dp)
                    ClawdyRow(e.model.displayName, subtitle = e.model.id, onClick = { update { gg -> gg.memberEntryIds.add(e.id); gg } },
                        trailing = { Icon(ClawdyIcons.Plus, "加入", tint = c.accent, modifier = Modifier.size(18.dp)) })
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ClawdyButton("删除分组", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
    }
    if (confirmDelete) ConfirmDialog("删除分组「${g.name}」？", "分组里的模型不会被删除。", "删除", onDismiss = { confirmDelete = false }) {
        providerRepository.removeGroup(g.id); onBack()
    }
}
