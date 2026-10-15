package com.openminis.app.ui.clawdy

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.BuildConfig
import com.openminis.app.clawdy.ClawdyPrefs
import com.openminis.app.clawdy.ClawdyPresets
import com.openminis.app.clawdy.ToolStatus
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.tools.TermuxTool
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyTokens
import io.github.nanomuse.hands.Hands
import kotlinx.coroutines.launch

@Composable
fun ClawdyPage(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val c = Clawdy.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        ClawdyTopBar(title, onBack, actions = actions)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

// ───────────────────────── 10 add provider ─────────────────────────

@Composable
fun ProviderAddScreen(providerRepository: ProviderRepository, onBack: () -> Unit, onSaved: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var presetKey by remember { mutableStateOf("deepseek") }
    val preset = ClawdyPresets.preset(presetKey)!!
    var name by remember(presetKey) { mutableStateOf(preset.name) }
    var base by remember(presetKey) { mutableStateOf(preset.displayBase) }
    var key by remember(presetKey) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember(presetKey) { mutableStateOf(preset.models.first().id) }
    val fetched = remember(presetKey) { mutableStateListOf<String>() }
    var testing by remember { mutableStateOf(false) }
    var result by remember(presetKey) { mutableStateOf<ClawdyPresets.TestResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    ClawdyPage("添加服务商", onBack) {
        SectionLabel("类型")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClawdyPresets.presets.forEach { p ->
                val sel = p.key == presetKey
                Text(
                    p.name, color = if (sel) Color.White else c.text, style = serif(13.sp, FontWeight.Medium),
                    modifier = Modifier.clip(CircleShape).background(if (sel) c.accent else c.surface)
                        .border(1.dp, if (sel) c.accent else c.border, CircleShape)
                        .clickable { presetKey = p.key }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        preset.note?.let { Text(it, color = c.text2, style = serif(12.sp), modifier = Modifier.padding(start = 20.dp, top = 8.dp)) }
        SectionLabel("名称")
        ClawdyTextField(name, { name = it }, preset.name, Modifier.padding(horizontal = 16.dp))
        SectionLabel("Base URL")
        ClawdyTextField(base, { base = it }, preset.displayBase, Modifier.padding(horizontal = 16.dp), mono = true)
        SectionLabel("API Key")
        ClawdyTextField(
            key, { key = it }, "sk-…", Modifier.padding(horizontal = 16.dp), mono = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { ClawdyIconButton(if (showKey) ClawdyIcons.EyeOff else ClawdyIcons.Eye, "显示", { showKey = !showKey }, tint = c.text2) },
        )
        SectionLabel("默认模型")
        Box(Modifier.padding(horizontal = 16.dp)) {
            ClawdyTextField(model, { model = it }, "模型 ID", mono = true, trailing = {
                ClawdyIconButton(ClawdyIcons.ChevronDown, "选择模型", { modelMenu = true }, tint = c.text2)
            })
            DropdownMenu(modelMenu, { modelMenu = false }, containerColor = c.surface) {
                (preset.models.map { it.id to it.name } + fetched.filter { f -> preset.models.none { it.id == f } }.map { it to it }).forEach { (id, label) ->
                    DropdownMenuItem({ Text(if (label == id) id else "$label  ·  $id", style = serif(14.sp)) }, { model = id; modelMenu = false })
                }
            }
        }
        Text(
            if (fetched.isEmpty()) "测试连接后会拉取 /models 列表；拉不到就手填" else "已从 /models 拉取 ${fetched.size} 个模型",
            color = c.text2, style = serif(12.sp), modifier = Modifier.padding(start = 20.dp, top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))
        result?.let { r ->
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(ClawdyTokens.BlockShape)
                    .background((if (r.ok) c.success else c.error).copy(alpha = 0.10f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (r.ok) ClawdyIcons.Check else ClawdyIcons.Alert, null, tint = if (r.ok) c.success else c.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(r.message, color = c.text, style = serif(13.sp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClawdyButton(if (testing) "测试中…" else "测试连接", {
                testing = true
                scope.launch {
                    val r = ClawdyPresets.testConnection(preset, base, key)
                    result = r; testing = false
                    fetched.clear(); fetched.addAll(r.models.take(200))
                }
            }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !testing && key.isNotBlank(), icon = ClawdyIcons.Zap)
            ClawdyButton(if (saving) "保存中…" else "保存", {
                saving = true
                scope.launch {
                    runCatching {
                        ClawdyPresets.addProvider(providerRepository, preset, name, base, key, listOf(model) + preset.models.map { it.id })
                    }.onSuccess {
                        Toast.makeText(ctx, "已添加 ${it.first.label}", Toast.LENGTH_SHORT).show(); onSaved()
                    }.onFailure { Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
                    saving = false
                }
            }, Modifier.weight(1f), enabled = !saving && key.isNotBlank() && model.isNotBlank() && base.startsWith("http"))
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Icon(ClawdyIcons.Shield, null, tint = c.text2, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text("Key 用 Android Keystore 加密后只存在本机，请求直接发给服务商，不经过任何中转。", color = c.text2, style = serif(12.sp, line = 18.sp))
        }
    }
}

// ───────────────────────── 11 tools ─────────────────────────

@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val st = rememberToolStatus()
    var handsOn by remember { mutableStateOf(Hands.enabled(ctx)) }
    val termuxPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var a11yGuide by remember { mutableStateOf(false) }

    ClawdyPage("工具连接", onBack) {
        Hint("每次回到这一页都会重新检测")
        // Termux (0.3.0: real diagnosis + a live RUN_COMMAND test + the one-line setup script)
        val diag = remember(st) { com.openminis.app.clawdy.TermuxDiag.read(ctx) }
        var testing by remember { mutableStateOf(false) }
        var test by remember { mutableStateOf<com.openminis.app.clawdy.TermuxDiag.TestResult?>(null) }
        val scope = rememberCoroutineScope()
        ToolCard(ClawdyIcons.Terminal, c.termux, "Termux", st.termuxReady,
            if (st.termuxReady) "已连接 · 命令在 ~/clawdy/<任务> 里运行" else "Clawdy 的 Linux 环境，用来跑 python、node、pandoc 等") {
            CheckLine("已安装 Termux" + (diag.versionName?.let { "（$it · ${diag.build}）" } ?: ""), st.termuxInstalled)
            if (st.termuxInstalled) CheckLine("Termux 提供「运行命令」权限", diag.permissionDefined)
            CheckLine("已授予「在 Termux 中运行命令」权限", st.termuxPermission)
            CheckLine("已开启 allow-external-apps（点「测试连接」确认）", test?.ok)
            if (diag.verdict != com.openminis.app.clawdy.TermuxDiag.Verdict.READY) {
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.amber.copy(alpha = 0.12f)).padding(12.dp)) {
                    Text(diag.title, color = c.text, style = serif(14.sp, FontWeight.SemiBold, 20.sp))
                    Text(diag.advice, color = c.text, style = serif(13.sp, line = 20.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (diag.verdict) {
                    com.openminis.app.clawdy.TermuxDiag.Verdict.NOT_INSTALLED, com.openminis.app.clawdy.TermuxDiag.Verdict.PLAY_BUILD, com.openminis.app.clawdy.TermuxDiag.Verdict.NOT_DEFINED ->
                        ClawdyButton("去下载（F-Droid）", { ToolStatus.market(ctx, "com.termux", ToolStatus.TERMUX_FDROID) }, Modifier.weight(1f), height = 40.dp)
                    com.openminis.app.clawdy.TermuxDiag.Verdict.INSTALLED_AFTER, com.openminis.app.clawdy.TermuxDiag.Verdict.NOT_GRANTED -> {
                        ClawdyButton("授权", { termuxPerm.launch(TermuxTool.PERMISSION_RUN_COMMAND) }, Modifier.weight(1f), height = 40.dp)
                        ClawdyButton("应用权限页", { ToolStatus.safeStart(ctx, ToolStatus.appDetails(ctx)) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, height = 40.dp)
                    }
                    com.openminis.app.clawdy.TermuxDiag.Verdict.READY -> {
                        ClawdyButton(if (testing) "测试中…" else "测试连接", {
                            testing = true; test = null
                            scope.launch { test = com.openminis.app.clawdy.TermuxDiag.test(ctx); testing = false }
                        }, Modifier.weight(1f), enabled = !testing, height = 40.dp, icon = ClawdyIcons.Zap)
                        ClawdyButton("打开 Termux", { ToolStatus.launch(ctx, "com.termux") }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, height = 40.dp)
                    }
                }
            }
            test?.let { Text(it.message, color = if (it.ok) c.success else c.error, style = serif(13.sp, line = 20.sp), modifier = Modifier.padding(top = 8.dp)) }
            if (st.termuxInstalled && diag.verdict != com.openminis.app.clawdy.TermuxDiag.Verdict.PLAY_BUILD) {
                Text("一键安装与诊断：在 Termux 里粘贴运行", color = c.text2, style = serif(12.sp), modifier = Modifier.padding(top = 12.dp))
                CopyLine(com.openminis.app.clawdy.TermuxDiag.SETUP_ONE_LINER)
                Hint("会开启 allow-external-apps、安装 python / node / pandoc 等，最后打印一张体检表。只想体检：在命令末尾加上 -s -- --diagnose。")
            }
        }
        // Hands
        ToolCard(ClawdyIcons.Hand, c.accent, "Hands 无障碍", st.handsReady && handsOn,
            if (st.accessibilityEnabled) "无障碍服务已开启" else "让 Clawdy 帮你点、输入、滑动其他 App") {
            CheckLine("无障碍服务已开启", st.accessibilityEnabled)
            CheckLine("可以显示在其他应用上层", st.overlay)
            CheckLine("Android 11 及以上（截图需要）", Build.VERSION.SDK_INT >= 30)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("允许 Hands 操作手机", color = c.text, style = serif(14.sp), modifier = Modifier.weight(1f))
                ClawdySwitch(handsOn, { handsOn = it; Hands.setEnabled(ctx, it) })
            }
            if (!st.accessibilityEnabled) {
                ClawdyButton("开启无障碍权限", { a11yGuide = true }, Modifier.fillMaxWidth().padding(top = 10.dp), height = 44.dp, icon = ClawdyIcons.Shield)
            }
        }
        // Shizuku
        ToolCard(ClawdyIcons.Zap, c.amber, "Shizuku（可选）", st.shizukuReady,
            when {
                st.shizukuReady -> "已连接并授权"
                st.shizukuBinder -> "服务在运行，还没授权给 Clawdy"
                st.shizukuInstalled -> "已安装，服务未运行"
                else -> "高级：免 root 执行 adb 级操作"
            }) {
            CheckLine("已安装 Shizuku", st.shizukuInstalled)
            CheckLine("Shizuku 服务在运行", st.shizukuBinder)
            CheckLine("已授权给 Clawdy", st.shizukuGranted)
            if (!st.shizukuReady) ClawdyButton(if (st.shizukuInstalled) "去连接" else "去下载", {
                if (st.shizukuBinder) runCatching { rikka.shizuku.Shizuku.requestPermission(7301) }
                else if (st.shizukuInstalled) ToolStatus.launch(ctx, "moe.shizuku.privileged.api")
                else ToolStatus.market(ctx, "moe.shizuku.privileged.api", ToolStatus.SHIZUKU_URL)
            }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.SECONDARY, height = 40.dp)
        }
        SectionLabel("权限")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("悬浮窗", hint = "Hands 工作时显示进度和停止按钮", icon = ClawdyIcons.Layers,
                trailing = { ClawdySwitch(st.overlay, { ToolStatus.safeStart(ctx, ToolStatus.overlaySettings(ctx)) }) })
            RowDivider()
            ClawdyRow("通知", hint = "后台确认敏感操作、任务完成提醒", icon = ClawdyIcons.Bell,
                trailing = { ClawdySwitch(st.notifications, {
                    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else ToolStatus.safeStart(ctx, ToolStatus.notificationSettings(ctx))
                }) })
            RowDivider()
            ClawdyRow("后台运行不受限制", hint = "电池优化白名单，长任务不被系统杀掉", icon = ClawdyIcons.Battery,
                trailing = { ClawdySwitch(st.batteryUnrestricted, { ToolStatus.safeStart(ctx, ToolStatus.batterySettings(ctx)) }) })
        }
    }
    if (a11yGuide) AccessibilityGuide(onDismiss = { a11yGuide = false })
}

/** Guided accessibility permission flow: why, the steps, and the button into system Settings. */
@Composable
fun AccessibilityGuide(onDismiss: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, shape = ClawdyTokens.CardShape,
        icon = { Icon(ClawdyIcons.Hand, null, tint = c.accent, modifier = Modifier.size(28.dp)) },
        title = { Text("开启 Hands 无障碍权限", style = serif(18.sp, FontWeight.SemiBold), color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Clawdy 需要无障碍权限才能替你在其他 App 里点击、输入和滑动。它只在你交给它任务时工作，敏感操作会先问你，密码框不会碰。", color = c.text, style = serif(14.sp, line = 21.sp))
                GuideStep(1, "点下面的按钮，进入系统「无障碍」设置")
                GuideStep(2, "在「已下载的应用 / 已安装的服务」里找到 Clawdy")
                GuideStep(3, "打开开关，并在弹窗里点「允许」")
                GuideStep(4, "返回 Clawdy，这里会自动打勾")
                Text("部分手机（MIUI / ColorOS）还需要在应用详情里允许「受限设置」。", color = c.text2, style = serif(12.sp, line = 18.sp))
            }
        },
        confirmButton = { ClawdyButton("去设置", { ToolStatus.safeStart(ctx, ToolStatus.accessibilitySettings()); onDismiss() }, height = 40.dp) },
        dismissButton = { ClawdyButton("应用详情", { ToolStatus.safeStart(ctx, ToolStatus.appDetails(ctx)) }, kind = ButtonKind.GHOST, height = 40.dp) },
    )
}

@Composable
private fun GuideStep(n: Int, text: String) {
    val c = Clawdy.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text("$n", color = c.accent, style = serif(12.sp, FontWeight.SemiBold))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.text, style = serif(14.sp, line = 20.sp))
    }
}

@Composable
private fun ToolCard(icon: ImageVector, tint: Color, title: String, ok: Boolean, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Clawdy.colors
    ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, style = serif(16.sp, FontWeight.SemiBold))
                Text(subtitle, color = c.text2, style = serif(12.sp, line = 17.sp))
            }
            if (ok) Pill("已连接", c.success, c.success.copy(alpha = 0.14f)) else Pill("未连接", c.text2, c.off.copy(alpha = 0.4f))
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun CheckLine(text: String, ok: Boolean?) {
    val c = Clawdy.colors
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> CheckBadge(c.success, 16.dp)
            false -> HollowDot(14.dp)
            null -> Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Icon(ClawdyIcons.Info, null, tint = c.text2, modifier = Modifier.size(14.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (ok == false) c.text2 else c.text, style = serif(13.sp))
    }
}

/** A one-line command with a copy button; the text scrolls sideways instead of wrapping. */
@Composable
fun CopyLine(cmd: String) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.codeBg).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(cmd, color = c.codeText, style = Mono, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 10.dp))
        ClawdyIconButton(ClawdyIcons.Copy, "复制", { copyText(ctx, cmd) }, tint = c.codeText, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun CodeLine(cmd: String) {
    val c = Clawdy.colors
    Text(cmd, color = c.codeText, style = Mono, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.codeBg).padding(10.dp))
}

// ───────────────────────── 12 onboarding ─────────────────────────

@Composable
fun OnboardingScreen(onAddProvider: () -> Unit, onDone: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val st = rememberToolStatus()
    var nick by remember { mutableStateOf(ClawdyPrefs.str(ctx, ClawdyPrefs.KEY_NICKNAME) ?: "") }
    val termuxPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var guide by remember { mutableStateOf(false) }
    fun finish() { ClawdyPrefs.setBool(ctx, ClawdyPrefs.KEY_ONBOARDED, true); onDone() }
    fun next() { if (step < 3) step++ else finish() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(4) { i -> Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(CircleShape).background(if (i <= step) c.accent else c.off)) }
            }
            Text("稍后再说", color = c.text2, style = serif(14.sp), modifier = Modifier.clip(CircleShape).clickable { next() }.padding(12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            when (step) {
                0 -> {
                    ClawdyAvatar(AvatarMood.HAPPY, 120.dp)
                    OnbTitle("你好，我是 Clawdy", "住在你手机里的 AI 助手：用 Termux 干活，用 Hands 帮你操作手机，敏感的事先问你。")
                    SectionLabel("怎么称呼你？")
                    ClawdyTextField(nick, { nick = it }, "你的昵称")
                    Text("界面语言：简体中文", color = c.text2, style = serif(12.sp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                1 -> {
                    StepIcon(ClawdyIcons.Terminal, c.termux)
                    OnbTitle("连接 Termux", "Termux 是 Clawdy 的 Linux：写文档、跑脚本、渲染网页都在这里。")
                    ClawdyCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        CheckLine("1. 安装 Termux（F-Droid 或 GitHub 版）", st.termuxInstalled)
                        if (!st.termuxInstalled) ClawdyButton("去下载", { ToolStatus.market(ctx, "com.termux", ToolStatus.TERMUX_FDROID) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), height = 40.dp)
                        Spacer(Modifier.height(6.dp))
                        CheckLine("2. 在 Termux 里粘贴运行这行命令（一键安装）", null)
                        CopyLine(com.openminis.app.clawdy.TermuxDiag.SETUP_ONE_LINER)
                        ClawdyButton("打开 Termux", { if (!ToolStatus.launch(ctx, "com.termux")) Toast.makeText(ctx, "还没安装 Termux", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth().padding(vertical = 8.dp), kind = ButtonKind.SECONDARY, height = 40.dp)
                        CheckLine("3. 授予「在 Termux 中运行命令」权限", st.termuxPermission)
                        if (!st.termuxPermission) ClawdyButton("授权", { termuxPerm.launch(TermuxTool.PERMISSION_RUN_COMMAND) }, Modifier.fillMaxWidth().padding(top = 8.dp), height = 40.dp)
                    }
                }
                2 -> {
                    StepIcon(ClawdyIcons.Hand, c.accent)
                    OnbTitle("开启 Hands", "让 Clawdy 能在其他 App 里替你点和输入。发消息、付款、删除前都会先问你。")
                    ClawdyCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        CheckLine("无障碍服务", st.accessibilityEnabled)
                        if (!st.accessibilityEnabled) ClawdyButton("开启无障碍权限", { guide = true }, Modifier.fillMaxWidth().padding(vertical = 8.dp), height = 40.dp)
                        CheckLine("悬浮窗权限", st.overlay)
                        if (!st.overlay) ClawdyButton("允许悬浮窗", { ToolStatus.safeStart(ctx, ToolStatus.overlaySettings(ctx)) }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.SECONDARY, height = 40.dp)
                    }
                }
                else -> {
                    StepIcon(ClawdyIcons.Key, c.amber)
                    OnbTitle("添加第一个服务商", "填上你自己的 API Key（推荐 DeepSeek，默认模型 ${ClawdyPresets.DEFAULT_MODEL_NAME}）。Key 只存在本机。")
                    ClawdyButton("添加服务商", onAddProvider, Modifier.fillMaxWidth(), icon = ClawdyIcons.Plus)
                }
            }
        }
        ClawdyButton(if (step == 3) "开始使用" else "下一步", {
            if (step == 0 && nick.isNotBlank()) ClawdyPrefs.setStr(ctx, ClawdyPrefs.KEY_NICKNAME, nick.trim())
            next()
        }, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
    if (guide) AccessibilityGuide { guide = false }
}

@Composable
private fun StepIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun OnbTitle(title: String, sub: String) {
    val c = Clawdy.colors
    Text(title, color = c.text, style = serif(24.sp, FontWeight.SemiBold, 32.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Text(sub, color = c.text2, style = serif(15.sp, line = 23.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 20.dp))
}

// ───────────────────────── 14 workspace ─────────────────────────

data class WsEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val st = rememberToolStatus()
    val path = remember { mutableStateListOf<String>() }
    var entries by remember { mutableStateOf<List<WsEntry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val rel = path.joinToString("/")
    LaunchedEffect(rel, reload, st.termuxReady) {
        if (!st.termuxReady) return@LaunchedEffect
        entries = null; err = null
        val dir = "\$HOME/clawdy" + if (rel.isEmpty()) "" else "/$rel"
        // find prints: type|size|mtime|name — robust to spaces.
        val cmd = "mkdir -p \"\$HOME/clawdy\"; cd \"$dir\" 2>/dev/null || exit 3; find . -mindepth 1 -maxdepth 1 -printf '%y|%s|%T@|%f\\n' | sort"
        val args = org.json.JSONObject().put("tool_title", "列出工作区").put("command", cmd).put("timeout", 30).toString()
        val r = TermuxTool.execute(args, ctx)
        if (!r.success) { err = r.output.lineSequence().firstOrNull() ?: "失败"; return@LaunchedEffect }
        entries = r.output.lineSequence().mapNotNull { line ->
            val p = line.split("|", limit = 4)
            if (p.size < 4) null else WsEntry(p[3], p[0] == "d", p[1].toLongOrNull() ?: 0, ((p[2].toDoubleOrNull() ?: 0.0) * 1000).toLong())
        }.sortedWith(compareBy<WsEntry>({ !it.dir }, { it.name.lowercase() })).toList()
    }
    ClawdyPage("工作区", onBack, actions = {
        ClawdyIconButton(ClawdyIcons.Retry, "刷新", { reload++ }, tint = c.text2)
    }) {
        // breadcrumb
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("~/clawdy", color = if (path.isEmpty()) c.text else c.accent, style = Mono.copy(fontSize = 13.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { path.clear() }.padding(4.dp))
            path.forEachIndexed { i, seg ->
                Icon(ClawdyIcons.ChevronRight, null, tint = c.text2, modifier = Modifier.size(14.dp))
                Text(seg, color = if (i == path.lastIndex) c.text else c.accent, style = Mono.copy(fontSize = 13.sp),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { while (path.size > i + 1) path.removeAt(path.lastIndex) }.padding(4.dp))
            }
        }
        when {
            !st.termuxReady -> EmptyState(ClawdyIcons.Terminal, "需要先连接 Termux", "工作区就是 Termux 里的 ~/clawdy/<任务名>。连接好 Termux 后这里会列出文件。")
            err != null -> EmptyState(ClawdyIcons.Alert, "读取失败", err ?: "")
            entries == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ClawdySpinner(24.dp) }
            entries!!.isEmpty() -> EmptyState(ClawdyIcons.Folder, "这里还是空的", "Clawdy 干活时产生的文件会出现在这里。")
            else -> ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
                entries!!.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    ClawdyRow(
                        e.name,
                        subtitle = buildString {
                            if (!e.dir) append(if (e.size < 1024) "${e.size} B" else if (e.size < 1048576) "%.1f KB".format(e.size / 1024.0) else "%.1f MB".format(e.size / 1048576.0)).append(" · ")
                            append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(e.mtime)))
                        },
                        icon = if (e.dir) ClawdyIcons.Folder else ClawdyIcons.File,
                        iconTint = if (e.dir) c.accent else c.termux,
                        onClick = if (e.dir) ({ path.add(e.name) }) else ({
                            Toast.makeText(ctx, "Termux 私有目录里的文件需要先复制到 ~/storage/shared/Clawdy 才能用其他应用打开", Toast.LENGTH_LONG).show()
                        }),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, sub: String) {
    val c = Clawdy.colors
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.text2, modifier = Modifier.size(40.dp))
        Text(title, color = c.text, style = serif(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp))
        Text(sub, color = c.text2, style = serif(13.sp, line = 20.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Third-party / open-source licenses: GPL-3.0 notice + bundled OFL font licenses from assets/licenses. */
@Composable
fun LicensesScreen(onBack: () -> Unit, onUpstreamAbout: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val files = remember { runCatching { ctx.assets.list("licenses")?.toList() }.getOrNull().orEmpty() }
    var open by remember { mutableStateOf<String?>(null) }
    ClawdyPage("开源许可", onBack) {
        Text(
            "Clawdy 是 nanoMuse（OpenMinis 的分支）的分支，以 GNU 通用公共许可证第 3 版（GPL-3.0）发布。你可以自由使用、修改和再发布，但修改后的版本也必须以 GPL-3.0 开源。",
            color = c.text, style = serif(14.sp, line = 22.sp), modifier = Modifier.padding(20.dp),
        )
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("上游项目与第三方组件", subtitle = "nanoMuse 关于页（依赖库许可）", icon = ClawdyIcons.Box, onClick = onUpstreamAbout)
            files.forEach { f ->
                RowDivider()
                ClawdyRow(f.removeSuffix(".txt"), subtitle = "SIL Open Font License 1.1", icon = ClawdyIcons.File, onClick = { open = if (open == f) null else f })
                if (open == f) {
                    val text = remember(f) { runCatching { ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("") }
                    Text(text, color = c.text2, style = Mono, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
