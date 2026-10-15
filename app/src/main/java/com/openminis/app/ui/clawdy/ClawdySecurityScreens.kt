package com.openminis.app.ui.clawdy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openminis.app.clawdy.ClawdyPrefs
import com.openminis.app.clawdy.ClawdySecurity
import com.openminis.app.clawdy.vault.Vault
import com.openminis.app.clawdy.vault.VaultFillGate
import com.openminis.app.clawdy.vault.VaultFillRequest
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyTokens
import io.github.nanomuse.guard.GateMode
import io.github.nanomuse.guard.Grants
import io.github.nanomuse.hands.HandsApps

// ───────────────────────── screen-lock confirmation ─────────────────────────

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The phone's own lock (fingerprint / face / PIN) as the key to the 密码箱. Uses STRONG biometrics or
 * the device credential so the confirmation also unlocks the Keystore key bound to it ([Vault]).
 * A phone with no screen lock passes straight through (its vault key is not bound either).
 */
@Composable
fun rememberDeviceAuth(): (title: String, subtitle: String, onResult: (Boolean) -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.invoke(r.resultCode == Activity.RESULT_OK); pending = null
    }
    return remember(ctx) {
        { title, subtitle, onResult ->
            val act = ctx.findActivity()
            when {
                !Vault.deviceSecure(ctx) -> onResult(true)
                act == null -> onResult(false)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val b = BiometricPrompt.Builder(act).setTitle(title).setSubtitle(subtitle)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
                    }
                    b.build().authenticate(CancellationSignal(), ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
                        override fun onAuthenticationFailed() = Unit
                    })
                }
                else -> {
                    val i = io.github.nanomuse.guard.DeviceCredential.keyguardIntent(ctx, title, subtitle)
                    if (i == null) onResult(false) else { pending = onResult; legacy.launch(i) }
                }
            }
        }
    }
}

// ───────────────────────── 安全 ─────────────────────────

@Composable
fun SecurityScreen(onBack: () -> Unit, onVault: () -> Unit, onSensitiveApps: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(ClawdySecurity.mode(ctx)) }
    var shots by remember { mutableStateOf(ClawdySecurity.screenshotUpload(ctx)) }
    var noPwd by remember { mutableStateOf(ClawdySecurity.noPasswordFields(ctx)) }
    var clip by remember { mutableStateOf(ClawdySecurity.clipboardConfirm(ctx)) }
    val always by Grants.always.collectAsState()
    val vaultEntries by Vault.entries.collectAsState()
    var confirmRevoke by remember { mutableStateOf(false) }
    val sensitiveCount = remember { ClawdySecurity.sensitiveApps(ctx).size }

    ClawdyPage("安全", onBack) {
        SectionLabel("工作模式")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard(ClawdyIcons.Lock, "谨慎", "每一个敏感操作都单独询问；安装软件也要问；不记住任何授权。", mode == GateMode.CAUTIOUS) {
                mode = GateMode.CAUTIOUS; ClawdySecurity.setMode(ctx, mode)
            }
            ModeCard(ClawdyIcons.Shield, "标准（推荐）", "删除、发送、付款前询问；同意时可以让本对话内同类操作不再询问。", mode == GateMode.STANDARD) {
                mode = GateMode.STANDARD; ClawdySecurity.setMode(ctx, mode)
            }
            ModeCard(ClawdyIcons.Zap, "放手", "只有付款、发消息、删除和高危命令会问；Termux 里的上传等联网操作直接运行并告诉你；同意一次后本对话内同类操作自动放行（付款除外）。", mode == GateMode.RELAXED) {
                mode = GateMode.RELAXED; ClawdySecurity.setMode(ctx, mode)
            }
        }
        SectionLabel("密码箱")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("密码箱", subtitle = if (vaultEntries.isEmpty()) "保存账号、银行卡、地址；Clawdy 经你同意后代填，看不到内容" else "${vaultEntries.size} 个条目 · 加密保存在本机",
                icon = ClawdyIcons.Key, onClick = onVault)
        }
        SectionLabel("隐私")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("截图上传", if (shots) "开：Hands 把屏幕截图发给识图模型" else "关：只把屏幕上的文字和按钮位置发给模型", shots, ClawdyIcons.Image) {
                shots = it; ClawdyPrefs.setBool(ctx, ClawdyPrefs.KEY_SCREENSHOT_UPLOAD, it)
            }
            RowDivider()
            SwitchRow("不在密码框上操作", if (noPwd) "开：Hands 不往密码、验证码框里输入，交给你或密码箱" else "关：任务里给了密码时 Hands 可以输入", noPwd, ClawdyIcons.Lock) {
                noPwd = it; ClawdyPrefs.setBool(ctx, ClawdyPrefs.KEY_NO_PASSWORD_FIELDS, it)
            }
            RowDivider()
            SwitchRow("读取剪贴板前确认", "Termux 命令要读剪贴板（termux-clipboard-get）时先问你", clip, ClawdyIcons.Clipboard) {
                clip = it; ClawdyPrefs.setBool(ctx, ClawdySecurity.KEY_CLIPBOARD_CONFIRM, it)
            }
            RowDivider()
            ClawdyRow("敏感应用", subtitle = "Hands 永远不操作这些应用（$sensitiveCount 个，默认是银行和支付类）", icon = ClawdyIcons.Ban, onClick = onSensitiveApps)
        }
        Hint("每次确认卡片都会列出这一步将发送的数据：命令大小、目标网址、附带的文件，以及模型看到的是截图还是文字。")
        SectionLabel("已记住的授权")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            if (always.isEmpty()) ClawdyRow("没有长期授权", subtitle = "在确认卡片上选择「总是允许」的会出现在这里", icon = ClawdyIcons.Check, iconTint = c.text2)
            always.forEachIndexed { i, g ->
                if (i > 0) RowDivider()
                ClawdyRow(io.github.nanomuse.guard.RiskText.plainTarget(g.target), subtitle = g.label.ifBlank { g.riskClass.name.lowercase() },
                    icon = ClawdyIcons.Shield, trailing = {
                        Text("撤销", color = c.error, style = serif(13.sp, FontWeight.SemiBold),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Grants.revoke(g.key) }.padding(8.dp))
                    })
            }
            RowDivider()
            ClawdyRow("撤销所有已记住的授权", subtitle = "也会清掉本次运行中各对话的临时授权", icon = ClawdyIcons.Trash, iconTint = c.error, onClick = { confirmRevoke = true })
        }
    }
    if (confirmRevoke) ConfirmDialog("撤销所有授权？", "之后每个敏感操作都会重新询问你。", "撤销", onDismiss = { confirmRevoke = false }) {
        Grants.revokeAll(); Grants.clearAllSessions()
        Toast.makeText(ctx, "已撤销", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val c = Clawdy.colors
    Row(
        Modifier.fillMaxWidth().clip(ClawdyTokens.CardShape).background(if (selected) c.taskCard else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.border, ClawdyTokens.CardShape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = if (selected) 0.18f else 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) c.accent else c.text2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = serif(15.sp, FontWeight.SemiBold))
            Text(desc, color = c.text2, style = serif(12.sp, line = 18.sp), modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (selected) c.accent else c.text2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (selected) Icon(ClawdyIcons.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

// ───────────────────────── 敏感应用 ─────────────────────────

@Composable
fun SensitiveAppsScreen(onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val apps = remember { HandsApps.launchable(ctx) }
    val chosen = remember { mutableStateMapOf<String, Boolean>().apply { ClawdySecurity.sensitiveApps(ctx).forEach { put(it, true) } } }
    var query by remember { mutableStateOf("") }
    val sorted = remember(apps, query) {
        apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .sortedWith(compareBy({ chosen[it.packageName] != true }, { it.label.lowercase() }))
    }
    ClawdyPage("敏感应用", onBack) {
        Hint("Hands 遇到这些应用会停下，让你亲手操作。默认包含常见银行、支付和钱包应用（没装的不会显示）。")
        ClawdyTextField(query, { query = it }, "搜索应用", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            sorted.forEachIndexed { i, a ->
                if (i > 0) RowDivider(16.dp)
                ClawdyRow(a.label, subtitle = a.packageName, trailing = {
                    ClawdySwitch(chosen[a.packageName] == true, { on -> chosen[a.packageName] = on; ClawdySecurity.setSensitive(ctx, a.packageName, on) })
                })
            }
        }
    }
}

// ───────────────────────── 密码箱 ─────────────────────────

@Composable
fun VaultScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val auth = rememberDeviceAuth()
    val entries by Vault.entries.collectAsState()
    var unlocked by remember { mutableStateOf(Vault.unlocked()) }
    var lockS by remember { mutableIntStateOf(ClawdySecurity.vaultLockSeconds(ctx)) }
    var adding by remember { mutableStateOf(false) }
    fun unlock() = auth("解锁密码箱", "用指纹、面容或锁屏密码确认") { ok ->
        if (ok) { Vault.markUnlocked(if (lockS == 0) 15 else lockS); unlocked = true }
        else Toast.makeText(ctx, "没有通过验证", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) { if (!unlocked) unlock() }

    ClawdyPage("密码箱", onBack, actions = {
        if (unlocked) ClawdyIconButton(ClawdyIcons.Lock, "上锁", { Vault.lock(); unlocked = false }, tint = c.text2)
    }) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(ClawdyIcons.Shield, null, tint = c.text2, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "内容用 Android Keystore 里的 AES-256 密钥加密，只存在这台手机上" +
                    (if (Vault.deviceSecure(ctx)) "，密钥和锁屏绑定，" else "（这台手机没有锁屏，建议先设置锁屏），") +
                    "不会同步、不进备份。Clawdy 只能看到条目名称；代填前每次都要你确认，填的内容不会发给模型。",
                color = c.text2, style = serif(12.sp, line = 18.sp),
            )
        }
        if (!unlocked) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ClawdyIcons.Lock, null, tint = c.text2, modifier = Modifier.size(40.dp))
                Text("已上锁", color = c.text, style = serif(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp))
                Text("${entries.size} 个条目", color = c.text2, style = serif(13.sp), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                ClawdyButton("解锁", { unlock() }, icon = ClawdyIcons.Key)
            }
            return@ClawdyPage
        }
        SectionLabel("条目")
        if (entries.isEmpty()) Hint("还没有条目。点下面的「添加」保存第一个账号。")
        else ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            entries.sortedWith(compareBy({ it.type.ordinal }, { it.label.lowercase() })).forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                ClawdyRow(m.label, subtitle = listOf(m.type.label, m.target).filter { it.isNotBlank() }.joinToString(" · "),
                    icon = typeIcon(m.type), onClick = { onEdit(m.id) })
            }
        }
        Spacer(Modifier.height(12.dp))
        ClawdyButton("添加", { adding = !adding }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = ClawdyIcons.Plus)
        if (adding) {
            ClawdyCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Vault.Type.entries.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    ClawdyRow(t.label, icon = typeIcon(t), onClick = { adding = false; onEdit("new:" + t.name) })
                }
            }
        }
        SectionLabel("自动上锁")
        ClawdyCard(Modifier.padding(horizontal = 16.dp)) {
            ClawdyRow("离开后多久上锁", icon = ClawdyIcons.Clock, trailing = {
                Segmented(ClawdySecurity.LOCK_CHOICES.map { it.second }, ClawdySecurity.LOCK_CHOICES.indexOfFirst { it.first == lockS }.coerceAtLeast(0), { i ->
                    lockS = ClawdySecurity.LOCK_CHOICES[i].first
                    ClawdyPrefs.setInt(ctx, ClawdySecurity.KEY_VAULT_LOCK_SECONDS, lockS)
                })
            })
        }
        Hint("代填（vault_fill）不受这个设置影响：每一次代填都要在确认卡片上重新验证。")
    }
}

fun typeIcon(t: Vault.Type): ImageVector = when (t) {
    Vault.Type.LOGIN -> ClawdyIcons.User
    Vault.Type.CARD -> ClawdyIcons.Card
    Vault.Type.ADDRESS -> ClawdyIcons.MapPin
    Vault.Type.NOTE -> ClawdyIcons.File
}

@Composable
fun VaultEditScreen(idArg: String, onBack: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val auth = rememberDeviceAuth()
    val isNew = idArg.startsWith("new:")
    val existing = if (isNew) null else Vault.meta(idArg)
    val type = existing?.type ?: runCatching { Vault.Type.valueOf(idArg.removePrefix("new:")) }.getOrDefault(Vault.Type.LOGIN)
    val fields = Vault.fieldsOf(type)
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var target by remember { mutableStateOf(existing?.target ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    val values = remember { mutableStateMapOf<String, String>() }
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    var loaded by remember { mutableStateOf(isNew) }
    var confirmDelete by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }

    fun withAuth(block: () -> Unit) {
        try { block() } catch (e: Vault.LockedException) {
            auth("验证身份", "保存或读取密码箱需要确认") { ok -> if (ok) runCatching(block).onFailure { Toast.makeText(ctx, "失败：${it.message}", Toast.LENGTH_LONG).show() } }
        } catch (e: Exception) { Toast.makeText(ctx, "失败：${e.message}", Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(idArg) {
        if (!Vault.unlocked()) { onBack(); return@LaunchedEffect }
        if (!isNew) withAuth { values.putAll(Vault.readValues(ctx, idArg)); loaded = true }
    }

    ClawdyPage(if (isNew) "新建" + type.label else label.ifBlank { type.label }, onBack) {
        SectionLabel("名称")
        ClawdyTextField(label, { label = it }, when (type) { Vault.Type.LOGIN -> "例如：淘宝主账号"; Vault.Type.CARD -> "例如：招行信用卡"; Vault.Type.ADDRESS -> "例如：家"; else -> "名称" }, Modifier.padding(horizontal = 16.dp))
        if (type == Vault.Type.LOGIN) {
            SectionLabel("网站或应用")
            Box(Modifier.padding(horizontal = 16.dp)) {
                ClawdyTextField(target, { target = it }, "taobao.com 或 com.taobao.taobao", mono = true, trailing = {
                    ClawdyIconButton(ClawdyIcons.Grid, "选择应用", { appMenu = true }, tint = c.text2)
                })
                androidx.compose.material3.DropdownMenu(appMenu, { appMenu = false }, containerColor = c.surface, modifier = Modifier.height(360.dp)) {
                    HandsApps.launchable(ctx).forEach { a ->
                        androidx.compose.material3.DropdownMenuItem({ Text(a.label, style = serif(14.sp)) }, { target = a.packageName; if (label.isBlank()) label = a.label; appMenu = false })
                    }
                }
            }
        }
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ClawdySpinner(24.dp) }
        } else fields.forEach { f ->
            SectionLabel(f.label)
            val secret = f.secret && shown[f.key] != true
            ClawdyTextField(
                values[f.key].orEmpty(), { values[f.key] = it }, f.label, Modifier.padding(horizontal = 16.dp),
                singleLine = !f.multiline, mono = f.secret,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                trailing = if (f.secret) ({ ClawdyIconButton(if (secret) ClawdyIcons.Eye else ClawdyIcons.EyeOff, "显示", { shown[f.key] = !(shown[f.key] ?: false) }, tint = c.text2) }) else null,
            )
        }
        SectionLabel("备注（不加密，Clawdy 看不到）")
        ClawdyTextField(notes, { notes = it }, "可选", Modifier.padding(horizontal = 16.dp), singleLine = false)
        Spacer(Modifier.height(16.dp))
        ClawdyButton("保存", {
            val meta = Vault.Meta(existing?.id ?: Vault.newId(), type, label.trim(), target.trim(), emptyList(), notes.trim())
            withAuth { Vault.save(ctx, meta, values.toMap()); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show(); onBack() }
        }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = loaded && label.isNotBlank() && values.values.any { it.isNotBlank() }, icon = ClawdyIcons.Lock)
        if (existing != null) {
            Spacer(Modifier.height(10.dp))
            ClawdyButton("删除", { confirmDelete = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = ClawdyIcons.Trash)
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog("删除「${existing.label}」？", "删除后无法恢复。", "删除", onDismiss = { confirmDelete = false }) {
        withAuth { Vault.delete(ctx, existing.id); onBack() }
    }
}

// ───────────────────────── 代填确认 (vault_fill) ─────────────────────────

/** The approval for one vault fill: which entry, which app, which fields. Allowing needs the screen lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultFillSheet(request: VaultFillRequest) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val auth = rememberDeviceAuth()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meta = Vault.meta(request.entryId)
    val names = request.fields.map { k -> meta?.let { m -> Vault.fieldsOf(m.type).firstOrNull { it.key == k }?.label } ?: k }
    ModalBottomSheet(
        onDismissRequest = { VaultFillGate.decide(request.id, false) },
        sheetState = sheet, shape = ClawdyTokens.SheetShape, containerColor = c.surface, dragHandle = { SheetGrab() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ClawdyAvatar(AvatarMood.WAITING, 60.dp, breathe = false, glow = false)
            Spacer(Modifier.height(12.dp))
            Pill("等待你确认", if (c.isDark) androidx.compose.ui.graphics.Color(0xFFF2C27A) else androidx.compose.ui.graphics.Color(0xFF8F5400), c.amber.copy(alpha = 0.2f))
            Text("Clawdy 想用密码箱代填", color = c.text, style = serif(19.sp, FontWeight.SemiBold, 27.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
            Column(
                Modifier.fillMaxWidth().clip(ClawdyTokens.BlockShape).background(c.bg)
                    .drawBehind { drawRect(c.accent, size = size.copy(width = 3.dp.toPx())) }
                    .padding(start = 17.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FillLine("条目", request.entryLabel + if (request.entryTarget.isNotBlank()) "（${request.entryTarget}）" else "")
                FillLine("填到", request.appLabel ?: request.appPackage ?: "当前应用")
                FillLine("字段", names.joinToString("、"))
            }
            val mismatch = request.entryTarget.isNotBlank() && request.appPackage != null &&
                !request.entryTarget.equals(request.appPackage, true) && !(request.appLabel ?: "").contains(request.entryLabel, true)
            Text(
                (if (mismatch) "注意：这个条目保存的网站/应用和当前应用不一致，确认是同一个账号再继续。\n" else "") +
                    "内容由手机直接填进输入框，不会发给模型、不进聊天记录和日志。",
                color = if (mismatch) c.error else c.text2, style = serif(12.sp, line = 18.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClawdyButton("拒绝", { VaultFillGate.decide(request.id, false) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                ClawdyButton("验证并填入", {
                    auth("确认代填", "${request.entryLabel} → ${request.appLabel ?: request.appPackage ?: ""}") { ok ->
                        if (ok) VaultFillGate.decide(request.id, true)
                        else Toast.makeText(ctx, "没有通过验证", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.weight(1f), icon = ClawdyIcons.Key)
            }
        }
    }
}

@Composable
private fun FillLine(k: String, v: String) {
    val c = Clawdy.colors
    Row {
        Text(k, color = c.text2, style = serif(13.sp), modifier = Modifier.width(44.dp))
        Text(v, color = c.text, style = serif(14.sp, line = 20.sp))
    }
}
