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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.clawdy.ClawdyPersona
import com.openminis.app.clawdy.memory.ClawdyMemory
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyLookStore
import com.openminis.app.ui.theme.LocalClawdyLook

// ───────────────────────── 人设（SOUL.md · 对话方式 · MEMORY.md） ─────────────────────────

@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val soul = remember { SoulStore.load(ctx) ?: SoulFile(SoulMetadata.DEFAULT, "") }
    var name by remember { mutableStateOf(soul.metadata.name.ifBlank { "Clawdy" }) }
    var body by remember { mutableStateOf(soul.body) }
    var style by remember { mutableStateOf(ClawdyPersona.styleId(ctx)) }
    var custom by remember { mutableStateOf(ClawdyPersona.customStyle(ctx)) }
    var memory by remember { mutableStateOf(ClawdyMemory.read(ctx)) }
    var memoryDirty by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(ClawdyMemory.autoEnabled(ctx)) }
    var memoryOn by remember { mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(ctx)) }
    var confirmClear by remember { mutableStateOf(false) }
    val updatedAt by ClawdyMemory.lastUpdate.collectAsState()
    ResumeEffect { if (!memoryDirty) memory = ClawdyMemory.read(ctx) }
    androidx.compose.runtime.LaunchedEffect(updatedAt) { if (!memoryDirty) memory = ClawdyMemory.read(ctx) }

    ClawdyPage("人设", onBack) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyAvatar(AvatarMood.HAPPY, 64.dp, breathe = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "Clawdy" }, color = c.text, style = serif(20.sp, FontWeight.SemiBold))
                Text("装在你手机上的 Agent", color = c.text2, style = serif(13.sp))
            }
        }

        SectionLabel("名字与性格（SOUL.md）")
        ClawdyTextField(name, { name = it.take(24) }, "名字", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        ClawdyTextField(body, { body = it }, "性格、说话习惯、你希望它记住的身份设定……（可留空）",
            Modifier.padding(horizontal = 16.dp).heightIn(min = 140.dp), singleLine = false)
        Hint("写在这里的内容会作为「性格」放进每次对话的系统提示词。不要写密码或密钥。")
        Spacer(Modifier.height(8.dp))
        ClawdyButton("保存人设", {
            val over = SoulStore.isOverLimit(body.trim())
            if (over.isOverLimit) { Toast.makeText(ctx, "太长了，请删减一些", Toast.LENGTH_SHORT).show(); return@ClawdyButton }
            runCatching { SoulStore.save(ctx, SoulFile(soul.metadata.copy(name = name.trim().ifBlank { "Clawdy" }), body.trim())) }
                .onSuccess { Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), height = 44.dp)

        SectionLabel("对话方式")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyPersona.STYLES.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                ClawdyRow(s.label, hint = s.blurb, onClick = { style = s.id; ClawdyPersona.setStyle(ctx, s.id) },
                    trailing = { if (style == s.id) Icon(ClawdyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) })
            }
        }
        if (style == "custom") {
            Spacer(Modifier.height(8.dp))
            ClawdyTextField(custom, { custom = it.take(800) }, "例如：回答尽量用口语，像朋友聊天；技术问题给出可以直接运行的命令",
                Modifier.padding(horizontal = 16.dp).heightIn(min = 96.dp), singleLine = false)
            Spacer(Modifier.height(8.dp))
            ClawdyButton("保存自定义方式", { ClawdyPersona.setCustomStyle(ctx, custom); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() },
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, height = 44.dp)
        }

        SectionLabel("记忆（MEMORY.md）")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("使用记忆", "把 MEMORY.md 带进每次对话", memoryOn, ClawdyIcons.Memory) { memoryOn = it; MemoryGlobalPrefs.setGlobalEnabled(ctx, it) }
            RowDivider()
            SwitchRow("自动记忆", "每次对话结束后，用当前模型把关于你的新信息整理进 MEMORY.md", auto, ClawdyIcons.Sparkle, enabled = memoryOn) {
                auto = it; ClawdyMemory.setAutoEnabled(ctx, it)
            }
        }
        Hint("记住的是你的基本信息、习惯、爱好、偏好、人际关系和正在做的事。密码、验证码、密钥、卡号和密码箱里的内容不会写进去。")
        updatedAt?.let { Note("刚刚更新过 · " + io.github.nanomuse.sysfiles.SystemFiles.relative(ctx, it)) }
        Spacer(Modifier.height(6.dp))
        ClawdyTextField(memory, { memory = it; memoryDirty = true }, "还没有记忆。和 Clawdy 聊几句，它会自己记下关于你的事。",
            Modifier.padding(horizontal = 16.dp).heightIn(min = 220.dp), singleLine = false, mono = true)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClawdyButton("清空", { confirmClear = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash, height = 44.dp)
            ClawdyButton("保存记忆", {
                runCatching { ClawdyMemory.write(ctx, memory) }
                    .onSuccess { memoryDirty = false; memory = ClawdyMemory.read(ctx); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
            }, Modifier.weight(1f), enabled = memoryDirty, height = 44.dp)
        }
    }
    if (confirmClear) ConfirmDialog("清空记忆？", "MEMORY.md 会恢复成空模板，Clawdy 记住的关于你的事都会删除，无法恢复。", "清空", onDismiss = { confirmClear = false }) {
        ClawdyMemory.clear(ctx); memory = ClawdyMemory.read(ctx); memoryDirty = false
    }
}

// ───────────────────────── 外观 ─────────────────────────

@Composable
fun ClawdyAppearanceScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val look = LocalClawdyLook.current
    var theme by remember { mutableIntStateOf(getAppearancePrefs(ctx).getInt(KEY_THEME_MODE, 0)) }
    fun set(block: (com.openminis.app.ui.theme.ClawdyLook) -> com.openminis.app.ui.theme.ClawdyLook) = ClawdyLookStore.update(ctx, block)

    ClawdyPage("外观", onBack) {
        SectionLabel("颜色")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SegmentedRow("主题", listOf("跟随系统", "浅色", "深色"), theme, { i -> theme = i; getAppearancePrefs(ctx).edit().putInt(KEY_THEME_MODE, i).apply() }, icon = ClawdyIcons.Palette)
            RowDivider()
            ClawdyRow("强调色", subtitle = ClawdyLookStore.ACCENTS.firstOrNull { it.id == look.accent }?.label ?: "珊瑚", icon = ClawdyIcons.Sparkle, below = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClawdyLookStore.ACCENTS.forEach { a ->
                        val col = if (c.isDark) a.dark else a.light
                        val sel = look.accent == a.id
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { set { it.copy(accent = a.id) } }) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(col)
                                    .border(if (sel) 2.dp else 0.dp, if (sel) c.text else Color.Transparent, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (sel) Icon(ClawdyIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                            Text(a.label, color = c.text2, style = serif(11.sp), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            })
        }

        SectionLabel("文字")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SegmentedRow("字体", listOf("衬线", "无衬线", "跟随系统"), look.font, { i -> set { it.copy(font = i) } }, icon = ClawdyIcons.Type,
                hint = "衬线：思源宋体 + Source Serif 4；无衬线：系统黑体；跟随系统：手机当前的系统字体")
            RowDivider()
            val scaleIdx = ClawdyLookStore.SCALES.indexOfFirst { kotlin.math.abs(it.first - look.scale) < 0.01f }.coerceAtLeast(1)
            SegmentedRow("字号", ClawdyLookStore.SCALES.map { it.second }, scaleIdx, { i -> set { it.copy(scale = ClawdyLookStore.SCALES[i].first) } }, icon = ClawdyIcons.Sliders)
            RowDivider()
            SegmentedRow("对话密度", listOf("紧凑", "标准", "宽松"), look.density, { i -> set { it.copy(density = i) } }, icon = ClawdyIcons.Rows,
                hint = "调整对话文字的行距和消息之间的间隔")
        }
        // live preview of the text settings
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Text("预览", color = c.text2, style = serif(12.sp))
            Text("今天下午三点提醒我给妈妈打电话。好的，我会在 15:00 提醒你。", color = c.text, style = serif(15.sp, line = 24.sp), modifier = Modifier.padding(top = 4.dp))
        }

        SectionLabel("引用块与代码块")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SegmentedRow("引用块", listOf("竖条 + 浅底", "只有竖条", "描边卡片"), look.quote, { i -> set { it.copy(quote = i) } }, icon = ClawdyIcons.Quote)
            RowDivider()
            SegmentedRow("代码块", listOf("深色", "浅色"), look.code, { i -> set { it.copy(code = i) } }, icon = ClawdyIcons.Code)
        }
        ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            com.openminis.app.ui.markdown.MarkdownText("> 引用块会这样显示。\n\n```bash\necho \"代码块会这样显示\"\n```", color = c.text, style = serif(15.sp, line = 24.sp))
        }

        SectionLabel("设置页")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("显示说明文字", subtitle = if (look.hints) "已显示" else "已隐藏", icon = ClawdyIcons.Info,
                trailing = { ClawdySwitch(look.hints, { on -> set { it.copy(hints = on) } }) })
        }
        Hint("关掉后，设置里各项下面的解释和页脚小字都会隐藏，页面更清爽。")
    }
}

// ───────────────────────── 高级设置 ─────────────────────────

data class AdvancedNav(
    val chatPrefs: () -> Unit,
    val hands: () -> Unit,
    val env: () -> Unit,
    val logs: () -> Unit,
    val network: () -> Unit,
    val groups: () -> Unit,
    val sysFiles: () -> Unit,
    val legacy: () -> Unit,
)

@Composable
fun AdvancedScreen(onBack: () -> Unit, nav: AdvancedNav) {
    val ctx = LocalContext.current
    var compact by remember { mutableStateOf(AutoCompactPrefs.isEnabled()) }
    ClawdyPage("高级设置", onBack) {
        Hint("这里是不常用、需要一点技术背景的设置。不确定的保持默认就好。")
        SectionLabel("对话")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("自动压缩上下文", "对话接近模型上下文上限时，先总结较早的内容再继续", compact, ClawdyIcons.Layers) {
                compact = it; AutoCompactPrefs.setEnabled(ctx, it)
            }
            RowDivider()
            ClawdyRow("上下文与记忆细节", hint = "压缩、记忆开关、上下文上限说明", icon = ClawdyIcons.Brain, onClick = nav.chatPrefs)
            RowDivider()
            ClawdyRow("模型分组与故障切换", hint = "备用模型、轮流使用、何时切换", icon = ClawdyIcons.Grid, onClick = nav.groups)
        }
        SectionLabel("工具")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("Hands 屏幕模型", hint = "操作屏幕用哪个模型、看截图还是看文字", icon = ClawdyIcons.Hand, onClick = nav.hands)
            RowDivider()
            ClawdyRow("环境变量", hint = "termux_run 运行前自动 export，比如 GITHUB_TOKEN", icon = ClawdyIcons.Variable, onClick = nav.env)
            RowDivider()
            ClawdyRow("网络代理", hint = "只用于请求模型服务商", icon = ClawdyIcons.Globe, onClick = nav.network)
        }
        SectionLabel("调试")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("运行日志", hint = "每次工具调用的完整记录，可导出", icon = ClawdyIcons.Logs, onClick = nav.logs)
            RowDivider()
            ClawdyRow("系统文件", hint = "SOUL / USER / MEMORY / HEARTBEAT 原始文件", icon = ClawdyIcons.File, onClick = nav.sysFiles)
            RowDivider()
            ClawdyRow("上游设置页", hint = "nanoMuse 原版设置，仅供排查问题", icon = ClawdyIcons.Settings, onClick = nav.legacy)
        }
    }
}
