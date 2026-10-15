package com.openminis.app.ui.clawdy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.clawdy.ToolStatus
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyMotion
import com.openminis.app.ui.theme.ClawdyTokens
import kotlinx.coroutines.delay

fun toolIcon(step: Step): ImageVector = when {
    step.isThinking -> ClawdyIcons.Brain
    else -> when (step.block.toolName) {
        "termux_run", "shell_execute" -> ClawdyIcons.Terminal
        "phone_hands" -> ClawdyIcons.Hand
        "browser_use", "web_search" -> ClawdyIcons.Globe
        "file_read", "file_write", "file_edit" -> ClawdyIcons.File
        "read_image" -> ClawdyIcons.Image
        "memory_write", "memory_get" -> ClawdyIcons.Memory
        "vault_list", "vault_fill" -> ClawdyIcons.Key
        else -> ClawdyIcons.Wrench
    }
}

@Composable
fun toolTint(step: Step): Color {
    val c = Clawdy.colors
    return when (step.block.toolName) {
        "termux_run", "shell_execute" -> c.termux
        "phone_hands" -> c.accent
        else -> c.text2
    }
}

/**
 * 02/03/05 — every tool call and thought of one assistant turn in ONE card. Collapsed: status,
 * current step, timer, progress. Tap: expands in place into the step timeline (260ms).
 */
@Composable
fun TaskCard(
    turn: TurnItem.Assistant,
    state: CardState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStepClick: (Step) -> Unit,
    onRetryStep: (Step) -> Unit,
    onShowError: (Step) -> Unit,
) {
    val c = Clawdy.colors
    val steps = turn.steps
    val running = state == CardState.RUNNING || state == CardState.WAITING
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { now = System.currentTimeMillis(); delay(1000) }
    }
    val (bg, border) = when (state) {
        CardState.RUNNING -> c.taskCard to c.taskBorder
        CardState.WAITING -> c.amber.copy(alpha = 0.13f) to c.amber.copy(alpha = 0.45f)
        CardState.DONE -> c.success.copy(alpha = 0.10f) to c.success.copy(alpha = 0.35f)
        CardState.FAILED -> c.error.copy(alpha = 0.09f) to c.error.copy(alpha = 0.35f)
        CardState.STOPPED -> c.surface to c.border
    }
    val total = steps.size
    val doneCount = steps.count { it.state == StepState.DONE }
    val currentIdx = steps.indexOfFirst { it.state == StepState.RUNNING }.takeIf { it >= 0 } ?: (total - 1)
    val current = steps.getOrNull(currentIdx)
    val failed = steps.firstOrNull { it.state == StepState.FAILED }
    val elapsed = Turns.elapsedMs(steps, now, running)
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(ClawdyMotion.CardExpand, easing = ClawdyMotion.Standard), label = "chev")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ClawdyTokens.CardShape)
            .background(bg)
            .border(1.dp, border, ClawdyTokens.CardShape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    CardState.RUNNING -> ClawdySpinner(16.dp)
                    CardState.WAITING -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(c.amber))
                    CardState.DONE -> CheckBadge(c.success)
                    CardState.FAILED -> Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(c.error), contentAlignment = Alignment.Center) {
                        Icon(ClawdyIcons.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                    CardState.STOPPED -> ClawdySpinner(16.dp, color = c.text2, spinning = false)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val head = when (state) {
                    CardState.RUNNING -> if (total == 0) "正在思考" else "正在处理 · 第 ${currentIdx + 1}/$total 步"
                    CardState.WAITING -> "等待你确认 · 第 ${currentIdx + 1}/${total.coerceAtLeast(1)} 步"
                    CardState.DONE -> "已完成 $total 步" + (elapsed?.let { " · 用时 ${Turns.fmt(it)}" } ?: "")
                    CardState.FAILED -> "第 ${(steps.indexOf(failed) + 1).coerceAtLeast(1)} 步失败"
                    CardState.STOPPED -> "已停止 · 完成 $doneCount/$total 步"
                }
                Text(head, color = c.text, style = serif(14.sp, FontWeight.SemiBold, 20.sp))
                val sub = when (state) {
                    CardState.RUNNING, CardState.WAITING -> current?.title ?: "Clawdy 正在想下一步"
                    CardState.FAILED -> failed?.title ?: turn.error?.take(60)
                    else -> null
                }
                if (sub != null && !expanded) {
                    Text(sub, color = c.text2, style = serif(12.sp, line = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (running && elapsed != null) {
                Text(Turns.clock(elapsed), color = c.text2, style = Mono)
                Spacer(Modifier.width(8.dp))
            }
            Icon(ClawdyIcons.ChevronDown, if (expanded) "收起" else "展开", tint = c.text2, modifier = Modifier.size(16.dp).rotate(chevron))
        }
        if (running && !expanded) {
            val frac = if (total == 0) 0.08f else (doneCount + 0.5f) / total
            ThinProgress(frac, if (state == CardState.WAITING) c.amber else c.accent, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        } else if (!expanded) {
            Spacer(Modifier.height(4.dp))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(ClawdyMotion.CardExpand, easing = ClawdyMotion.Standard)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(ClawdyMotion.CardExpand, easing = ClawdyMotion.Standard)) + fadeOut(tween(150)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(border))
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (steps.isEmpty()) {
                        Text("还没有工具调用", color = c.text2, style = serif(13.sp))
                    }
                    steps.forEach { s -> StepRow(s, onClick = { onStepClick(s) }) }
                }
            }
        }
        if (state == CardState.FAILED && failed != null) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClawdyButton("重试这一步", { onRetryStep(failed) }, Modifier.weight(1f), kind = ButtonKind.DANGER, height = 44.dp, icon = ClawdyIcons.Retry)
                ClawdyButton("查看错误", { onShowError(failed) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, height = 44.dp)
            }
        }
    }
}

@Composable
private fun StepRow(s: Step, onClick: () -> Unit) {
    val c = Clawdy.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(18.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            when (s.state) {
                StepState.DONE -> Icon(ClawdyIcons.Check, "完成", tint = c.success, modifier = Modifier.size(16.dp))
                StepState.RUNNING -> ClawdySpinner(14.dp)
                StepState.PENDING -> HollowDot(12.dp)
                StepState.FAILED -> Icon(ClawdyIcons.Close, "失败", tint = c.error, modifier = Modifier.size(16.dp))
                StepState.STOPPED -> HollowDot(12.dp, c.text2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = toolTint(s)
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(toolIcon(s), s.toolLabel, tint = tint, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    s.title, color = if (s.state == StepState.PENDING) c.text2 else c.text,
                    style = serif(13.sp, line = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                add(s.toolLabel)
                s.exitCode?.let { add("exit $it") }
                if (s.block.durationMs > 0) add(Turns.fmt(s.block.durationMs).let { if (s.block.durationMs < 1000) "${s.block.durationMs}ms" else it })
            }.joinToString(" · ")
            Text(meta, color = c.text2, style = Mono)
            if (s.isTermux && !s.command.isNullOrBlank()) {
                Text(
                    s.command.lineSequence().first(),
                    color = c.codeText, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(c.codeBg).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val MAX_DETAIL = 64 * 1024

private fun splitOutput(content: String): Pair<String, String> {
    val idx = content.indexOf("[stderr]")
    val out = if (idx >= 0) content.substring(0, idx) else content
    val err = if (idx >= 0) content.substring(idx + "[stderr]".length) else ""
    return out.trim() to err.trim()
}

private fun cap(s: String): Pair<String, Boolean> =
    if (s.length > MAX_DETAIL) s.take(MAX_DETAIL) to true else s to false

/** 04 — step detail bottom sheet: tool, workspace, duration, exit badge, 命令/输出/错误 tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepDetailSheet(step: Step, initialTab: Int = 1, onDismiss: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(initialTab) }
    val (out, err) = remember(step.block.content) { splitOutput(step.block.content) }
    val errText = if (err.isBlank() && step.state == StepState.FAILED) out else err
    val command = step.command ?: step.block.toolArgs.ifBlank { "（无）" }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, shape = ClawdyTokens.SheetShape,
        containerColor = c.surface, dragHandle = { SheetGrab() },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(toolTint(step)),
                    contentAlignment = Alignment.Center,
                ) { Icon(toolIcon(step), null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, color = c.text, style = serif(16.sp, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(step.toolLabel, step.workspace,
                        step.block.durationMs.takeIf { it > 0 }?.let { Turns.fmt(it) }).joinToString(" · ")
                    Text(sub, color = c.text2, style = Mono)
                }
                val code = step.exitCode
                when {
                    code != null -> Pill("exit $code", if (code == 0) c.success else c.error,
                        (if (code == 0) c.success else c.error).copy(alpha = 0.16f), mono = true)
                    step.state == StepState.DONE -> Pill("成功", c.success, c.success.copy(alpha = 0.16f))
                    step.state == StepState.FAILED -> Pill("失败", c.error, c.error.copy(alpha = 0.16f))
                    step.state == StepState.RUNNING -> Pill("运行中", c.accent, c.accent.copy(alpha = 0.16f))
                }
            }
            Spacer(Modifier.height(14.dp))
            // tabs with sliding underline (180ms)
            val tabs = listOf("命令", "输出", "错误")
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { i, t ->
                        Box(Modifier.weight(1f).height(44.dp).clickable { tab = i }, contentAlignment = Alignment.Center) {
                            Text(t, color = if (tab == i) c.text else c.text2,
                                style = serif(14.sp, if (tab == i) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
                val frac by animateFloatAsState(tab.toFloat(), tween(ClawdyMotion.TabUnderline, easing = ClawdyMotion.Standard), label = "ul")
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.BottomStart)) {
                    val w = maxWidth / 3
                    Box(Modifier.padding(start = w * frac + (w - 28.dp) / 2).width(28.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Spacer(Modifier.height(12.dp))
            val (shown, truncated) = cap(
                when (tab) { 0 -> command; 1 -> out.ifBlank { "（无输出）" }; else -> errText.ifBlank { "（无错误）" } }
            )
            Box(
                Modifier.fillMaxWidth().heightIn(min = 132.dp, max = 340.dp).clip(ClawdyTokens.BlockShape).background(c.codeBg)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(shown, color = c.codeText, style = Mono.copy(lineHeight = 20.sp), softWrap = false)
            }
            if (truncated) Text("内容超过 64 KB，已截断", color = c.text2, style = serif(12.sp), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClawdyButton("复制输出", {
                    copyText(ctx, when (tab) { 0 -> command; 1 -> out; else -> errText })
                }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Copy)
                if (step.isTermux) {
                    ClawdyButton("在 Termux 中打开", {
                        if (!ToolStatus.launch(ctx, "com.termux")) Toast.makeText(ctx, "没有找到 Termux", Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f), icon = ClawdyIcons.Terminal)
                }
            }
        }
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Clawdy", text))
    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}
