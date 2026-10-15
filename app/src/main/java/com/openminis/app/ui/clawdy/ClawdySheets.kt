package com.openminis.app.ui.clawdy

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openminis.app.clawdy.ClawdyPrefs
import com.openminis.app.clawdy.ClawdyPresets
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.addAttachment
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyTokens
import io.github.nanomuse.guard.RiskClass
import io.github.nanomuse.guard.RiskDecision
import io.github.nanomuse.guard.RiskGate
import io.github.nanomuse.guard.RiskRequest
import io.github.nanomuse.guard.RiskText
import io.github.nanomuse.identity.NanoMuseIdentity
import java.io.File

// ───────────────────────── 09 model sheet ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(vm: ChatViewModel, providerRepository: ProviderRepository, onAddProvider: () -> Unit, onDismiss: () -> Unit) {
    val c = Clawdy.colors
    val config by providerRepository.config.collectAsState()
    val active by vm.activeEntryId.collectAsState()
    val thinking by vm.thinkingLevel.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val instances = config.instances.filter { it.isEnabled }
    val activeEntry = config.modelEntries.firstOrNull { it.id == active }
    val activeCaps = activeEntry?.let { ClawdyPresets.capsFor(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ClawdyTokens.SheetShape, containerColor = c.surface, dragHandle = { SheetGrab() }) {
        Column(Modifier.navigationBarsPadding().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("选择模型", color = c.text, style = serif(18.sp, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("切换只影响当前对话，默认模型在设置里改", color = c.text2, style = serif(12.sp), modifier = Modifier.padding(horizontal = 20.dp))
            if (instances.isEmpty()) {
                Text(
                    "还没有配置服务商。默认推荐 ${ClawdyPresets.DEFAULT_MODEL_NAME}，添加 DeepSeek 的 API Key 即可使用。",
                    color = c.text2, style = serif(14.sp, line = 21.sp), modifier = Modifier.padding(20.dp),
                )
            }
            instances.forEach { inst ->
                val entries = providerRepository.visibleEntries(inst.id)
                SectionLabel(inst.label)
                if (entries.isEmpty()) {
                    Text("没有模型（检查 Key 或在服务商详情里添加）", color = c.text2, style = serif(13.sp), modifier = Modifier.padding(horizontal = 20.dp))
                }
                entries.forEach { e ->
                    val caps = ClawdyPresets.capsFor(e)
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.selectEntry(e.id); onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.model.displayName, color = c.text, style = serif(15.sp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (caps.tools) CapTag("工具调用")
                                if (caps.vision) CapTag("识图")
                                if (caps.thinking) CapTag("推理")
                            }
                        }
                        if (e.id == active) Icon(ClawdyIcons.Check, "当前", tint = c.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            RowDivider(0.dp)
            ClawdyRow(
                "深度思考",
                subtitle = if (activeCaps?.thinking == true) "让模型先推理再回答（更慢，更准）" else "当前模型不支持推理",
                icon = ClawdyIcons.Brain,
                trailing = {
                    ClawdySwitch(thinking != ThinkingLevel.OFF && activeCaps?.thinking == true, { on ->
                        vm.setThinkingLevel(if (on) ThinkingLevel.MEDIUM else ThinkingLevel.OFF)
                    }, enabled = activeCaps?.thinking == true)
                },
            )
            ClawdyRow("添加服务商", icon = ClawdyIcons.Plus, onClick = onAddProvider)
        }
    }
}

@Composable
private fun CapTag(text: String) {
    val c = Clawdy.colors
    Text(text, color = c.text2, style = serif(11.sp), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

// ───────────────────────── 06 approval sheet ─────────────────────────

/**
 * Sensitive-action confirmation, driven by nanoMuse's RiskGate (the agent is suspended on
 * [RiskGate.ask] until a decision lands here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(request: RiskRequest, onEditRequest: (String) -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gateMode = io.github.nanomuse.guard.RiskGate.mode
    // 标准: remembering for this chat is pre-checked; 放手 remembers by itself (RiskGate); 谨慎 never offers it.
    var rememberChoice by remember(request.id) { mutableStateOf(gateMode == io.github.nanomuse.guard.GateMode.STANDARD && request.canSession) }
    val name = NanoMuseIdentity.name(ctx)
    val title = if (request.assessment.riskClass == RiskClass.INSTALL) "$name 想安装软件"
        else runCatching { RiskText.title(ctx, request, name) }.getOrDefault("确认这一步操作")
    val money = request.assessment.riskClass == RiskClass.MONEY
    val shake = rememberInfiniteTransition(label = "shake")
    val angle by shake.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 1800
            0f at 1080; -6f at 1260; 6f at 1440; -3f at 1620; 0f at 1800
        }, RepeatMode.Restart), label = "a",
    )
    ModalBottomSheet(
        onDismissRequest = { RiskGate.decide(request.id, RiskDecision.DENY) },
        sheetState = sheet, shape = ClawdyTokens.SheetShape, containerColor = c.surface, dragHandle = { SheetGrab() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ClawdyAvatar(AvatarMood.WAITING, 60.dp, Modifier.rotate(angle), breathe = false, glow = false)
            Spacer(Modifier.height(12.dp))
            Pill("等待你确认", if (c.isDark) Color(0xFFF2C27A) else Color(0xFF8F5400), c.amber.copy(alpha = 0.2f))
            Text(title, color = c.text, style = serif(19.sp, FontWeight.SemiBold, 27.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
            // Quote block: 3dp accent bar, surface tint, 14dp radius, no heavy border, serif text.
            Row(
                Modifier.fillMaxWidth().clip(ClawdyTokens.BlockShape).background(c.bg)
                    .drawBehind { drawRect(if (money) c.amber else c.accent, size = size.copy(width = 3.dp.toPx())) }
                    .padding(start = 17.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    request.preview, color = c.text, style = serif(15.sp, line = 23.sp),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
                Text("编辑", color = c.accent, style = serif(13.sp, FontWeight.SemiBold),
                    modifier = Modifier.clip(CircleShape).clickable {
                        // RiskGate cannot rewrite a pending action: cancel it and hand the content to the composer.
                        RiskGate.decide(request.id, RiskDecision.DENY)
                        onEditRequest("改成这样再执行：\n" + request.preview)
                    }.padding(horizontal = 10.dp, vertical = 12.dp))
            }
            val reason = request.assessment.reason
            Text(
                buildString {
                    if (reason.isNotBlank()) append("原因：").append(reason).append("\n")
                    append(dataPreview(ctx, request))
                },
                color = c.text2, style = serif(12.sp, line = 18.sp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
            )
            if (request.canSession && gateMode == io.github.nanomuse.guard.GateMode.STANDARD) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { rememberChoice = !rememberChoice },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClawdyCheckbox(rememberChoice)
                    Spacer(Modifier.width(10.dp))
                    Text("本对话内同类操作不再询问", color = c.text, style = serif(14.sp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClawdyButton("取消", { RiskGate.decide(request.id, RiskDecision.DENY) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                ClawdyButton(when (request.assessment.riskClass) {
                    RiskClass.MONEY -> "确认付款"
                    RiskClass.DESTRUCTIVE -> "确认删除"
                    RiskClass.OUTBOUND -> "确认发送"
                    else -> "允许"
                }, {
                    RiskGate.decide(request.id, if (rememberChoice && request.canSession) RiskDecision.ALLOW_SESSION else RiskDecision.ALLOW_ONCE)
                }, Modifier.weight(1f))
            }
            if (gateMode == io.github.nanomuse.guard.GateMode.RELAXED && request.canSession) {
                Text("放手模式：同意后，本对话内同类操作不再询问", color = c.text2, style = serif(12.sp), modifier = Modifier.padding(top = 10.dp))
            }
            Text("密码、验证码和支付密码只能由你亲手输入", color = c.text2, style = serif(12.sp), modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
fun ClawdyCheckbox(checked: Boolean) {
    val c = Clawdy.colors
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (checked) c.accent else Color.Transparent)
            .border(1.5.dp, if (checked) c.accent else c.text2, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(ClawdyIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
}

// ───────────────────────── 05 file card ─────────────────────────

fun mimeOf(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        "md" -> "text/markdown"
        else -> "application/octet-stream"
    }
}

private fun contentUri(ctx: android.content.Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

/** Open in an external app (WPS etc.) via ACTION_VIEW + FileProvider, always through a chooser. */
fun openExternal(ctx: android.content.Context, path: String) {
    val f = File(path)
    if (!f.exists()) {
        Toast.makeText(ctx, "找不到文件（可能需要在系统设置里给 Clawdy「所有文件访问」权限）", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        val uri = contentUri(ctx, f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(view, "用其他应用打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "无法打开：${it.message}", Toast.LENGTH_SHORT).show() }
}

fun shareFile(ctx: android.content.Context, path: String) {
    val f = File(path)
    runCatching {
        val uri = contentUri(ctx, f)
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(path)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "无法分享：${it.message}", Toast.LENGTH_SHORT).show() }
}

private fun humanSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1024.0 / 1024.0)
}

@Composable
fun FileCard(path: String) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val f = remember(path) { File(path) }
    val exists = remember(path) { runCatching { f.exists() }.getOrDefault(false) }
    val ext = path.substringAfterLast('.', "").uppercase()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFA587), Color(0xFFE07F5F), Color(0xFFC25A3C)), Offset.Zero, Offset(160f, 120f))),
                contentAlignment = Alignment.Center,
            ) { Text(ext.take(4), color = Color.White, style = Mono.copy(fontWeight = FontWeight.Bold)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, color = c.text, style = serif(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (exists) humanSize(f.length()) else "未在本机找到", color = c.text2, style = serif(12.sp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClawdyButton("打开", { openExternal(ctx, path) }, Modifier.weight(1f), height = 44.dp, icon = ClawdyIcons.External)
            ClawdyButton("分享", { shareFile(ctx, path) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, height = 44.dp, icon = ClawdyIcons.Share, enabled = exists)
        }
    }
}

// ───────────────────────── 13 attach sheet ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(vm: ChatViewModel, onDismiss: () -> Unit, onWorkspace: () -> Unit) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var linkDialog by remember { mutableStateOf(false) }
    fun nameOf(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
    fun add(uri: Uri) {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        vm.addAttachment(InputAttachment(fileName = nameOf(uri), uri = uri, mimeType = mime,
            kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> uris.forEach(::add); if (uris.isNotEmpty()) onDismiss() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(::add); if (uris.isNotEmpty()) onDismiss() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) {
            vm.addAttachment(InputAttachment(fileName = "photo.jpg", uri = u, mimeType = "image/jpeg", kind = InputAttachment.Kind.IMAGE)); onDismiss()
        }
    }
    var webSearch by remember { mutableStateOf(ClawdyPrefs.bool(ctx, ClawdyPrefs.KEY_WEB_SEARCH, true)) }
    var hands by remember { mutableStateOf(ClawdyPrefs.bool(ctx, ClawdyPrefs.KEY_HANDS_FOR_MESSAGE, true)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ClawdyTokens.SheetShape, containerColor = c.surface, dragHandle = { SheetGrab() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val tiles: List<Triple<ImageVector, String, () -> Unit>> = listOf(
                Triple(ClawdyIcons.Camera, "拍照") {
                    runCatching {
                        val dir = File(ctx.filesDir, "camera-photos").apply { mkdirs() }
                        val f = File(dir, "clawdy_${System.currentTimeMillis()}.jpg")
                        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                        cameraUri = u; camera.launch(u)
                    }.onFailure { Toast.makeText(ctx, "无法打开相机", Toast.LENGTH_SHORT).show() }
                },
                Triple(ClawdyIcons.Image, "相册") { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                Triple(ClawdyIcons.File, "文件") { files.launch(arrayOf("*/*")) },
                Triple(ClawdyIcons.Phone, "当前屏幕") { Toast.makeText(ctx, "当前屏幕：开发中（将通过 Hands 截图并读取节点）", Toast.LENGTH_SHORT).show() },
                Triple(ClawdyIcons.Folder, "工作区文件", onWorkspace),
                Triple(ClawdyIcons.Link, "网页链接") { linkDialog = true },
            )
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (icon, label, action) ->
                        Column(
                            Modifier.weight(1f).clip(ClawdyTokens.CardShape).background(c.bg).border(1.dp, c.border, ClawdyTokens.CardShape)
                                .clickable(onClick = action).padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(icon, null, tint = c.accent, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(label, color = c.text, style = serif(13.sp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ClawdyRow("联网搜索", icon = ClawdyIcons.Globe, trailing = {
                ClawdySwitch(webSearch, { webSearch = it; ClawdyPrefs.setBool(ctx, ClawdyPrefs.KEY_WEB_SEARCH, it) })
            })
            ClawdyRow("Hands 操作手机", subtitle = "关掉后本条消息只用 Termux 和对话", icon = ClawdyIcons.Hand, trailing = {
                ClawdySwitch(hands, { hands = it; ClawdyPrefs.setBool(ctx, ClawdyPrefs.KEY_HANDS_FOR_MESSAGE, it) })
            })
        }
    }
    if (linkDialog) {
        var url by remember { mutableStateOf("https://") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkDialog = false }, containerColor = c.surface, shape = ClawdyTokens.CardShape,
            title = { Text("网页链接", style = serif(18.sp, FontWeight.SemiBold), color = c.text) },
            text = { ClawdyTextField(url, { url = it }, "https://", mono = true) },
            confirmButton = {
                ClawdyButton("插入", {
                    val cur = vm.inputText.value
                    vm.setInputText(if (cur.isBlank()) url.trim() else "$cur ${url.trim()}")
                    linkDialog = false; onDismiss()
                }, height = 40.dp)
            },
            dismissButton = { ClawdyButton("取消", { linkDialog = false }, kind = ButtonKind.GHOST, height = 40.dp) },
        )
    }
}


/**
 * 「将发送的数据」: what this step actually sends, counted from the request itself — the command's
 * size, the hosts it reaches, the files it attaches — and what the model sees of the screen.
 */
fun dataPreview(ctx: android.content.Context, r: io.github.nanomuse.guard.RiskRequest): String {
    val shots = com.openminis.app.clawdy.ClawdySecurity.screenshotUpload(ctx)
    return when (r.kind) {
        io.github.nanomuse.guard.GuardKind.SHELL, io.github.nanomuse.guard.GuardKind.COMPUTER -> {
            val cmd = r.preview
            val bytes = cmd.toByteArray().size
            val lines = cmd.lines().count { it.isNotBlank() }
            val hosts = (Regex("""https?://([A-Za-z0-9.\-]+)""").findAll(cmd).map { it.groupValues[1] } +
                Regex("""\b[\w.\-]+@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})(?::|\s)""").findAll(cmd).map { it.groupValues[1] })
                .distinct().take(4).toList()
            val files = (Regex("""@([^\s'";|&]+)""").findAll(cmd).map { it.groupValues[1] }.filter { '/' in it || '.' in it } +
                Regex("""(?:-T|--upload-file|-F\s*\S+=@?)\s*([^\s'";|&]+)""").findAll(cmd).map { it.groupValues[1] })
                .map { it.substringAfterLast('/') }.filter { it.isNotBlank() && !it.contains('@') }.distinct().take(4).toList()
            buildString {
                append("将发送的数据：命令 ").append(lines).append(" 行 · ").append(bytes).append(" 字节")
                if (hosts.isNotEmpty()) append(" · 目标 ").append(hosts.joinToString("、"))
                if (files.isNotEmpty()) append(" · 附带文件 ").append(files.joinToString("、"))
                if (r.assessment.target != null && hosts.isEmpty()) append(" · 对象 ").append(io.github.nanomuse.guard.RiskText.plainTarget(r.assessment.target))
            }
        }
        io.github.nanomuse.guard.GuardKind.SCREEN -> buildString {
            append("将在「").append(r.pageUrl ?: "当前应用").append("」里点「").append((r.elementText ?: "").take(30)).append("」")
            append(if (shots) " · Hands 看的是截图" else " · Hands 只看屏幕文字，不上传截图")
        }
        io.github.nanomuse.guard.GuardKind.BROWSER -> buildString {
            append("将在网页 ")
            append(runCatching { android.net.Uri.parse(r.pageUrl).host }.getOrNull() ?: r.pageUrl ?: "当前页面")
            r.elementText?.takeIf { it.isNotBlank() }?.let { append(" 点「").append(it.take(30)).append("」") }
        }
        else -> "来自其他设备的请求：" + (r.pageUrl ?: "未知设备")
    }
}
