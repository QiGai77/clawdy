@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openminis.app.ui.clawdy

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.openminis.app.clawdy.ClawdyPrefs
import com.openminis.app.clawdy.ClawdyPresets
import com.openminis.app.clawdy.ToolStatus
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.mergeStreamingOverlay
import com.openminis.app.ui.chat.removeAttachment
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate
import com.openminis.app.ui.theme.Clawdy
import com.openminis.app.ui.theme.ClawdyMotion
import com.openminis.app.ui.theme.ClawdyTokens
import io.github.nanomuse.guard.RiskGate
import io.github.nanomuse.identity.NanoMuseIdentity
import io.github.nanomuse.ui.home.HomeBus
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

object ClawdyRoutes {
    const val TOOLS = "clawdy/tools"
    const val PROVIDER_ADD = "clawdy/provider_add"
    const val SETTINGS = "clawdy/settings"
    const val WORKSPACE = "clawdy/workspace"
    const val ONBOARDING = "clawdy/onboarding"
    const val PERSONA = "clawdy/persona"
    const val SECURITY = "clawdy/security"
}

private fun newDraft() = "__new__${UUID.randomUUID()}"

/**
 * The Clawdy shell: drawer (08) + one conversation surface that is the home (01) while empty and
 * the chat (02–05) once it has messages. Start destination of the app.
 */
@Composable
fun ClawdyShell(
    navController: NavHostController,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
) {
    val ctx = LocalContext.current
    val c = Clawdy.colors
    val scope = rememberCoroutineScope()
    var sessionId by rememberSaveable { mutableStateOf(newDraft()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)

    // First run → guided onboarding (12), once.
    LaunchedEffect(Unit) {
        if (!ClawdyPrefs.bool(ctx, ClawdyPrefs.KEY_ONBOARDED, false)) runCatching { navController.navigate(ClawdyRoutes.ONBOARDING) }
    }
    // Deep links / notifications that ask the shell to show a session.
    LaunchedEffect(Unit) {
        HomeBus.requests.collect { req ->
            when (req) {
                is HomeBus.Request.ShowSession -> sessionId = req.sessionId
                is HomeBus.Request.PrefillComposer -> {
                    val id = newDraft(); sessionId = id
                }
                else -> {}
            }
            HomeBus.handled()
        }
    }

    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = c.bg,
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                ClawdyDrawer(
                    chatRepository = chatRepository,
                    currentSessionId = ChatViewModelStore.rememberPersistedId(sessionId),
                    onNewChat = { sessionId = newDraft(); scope.launch { drawer.close() } },
                    onOpenSession = { sessionId = it; scope.launch { drawer.close() } },
                    onNavigate = { route -> scope.launch { drawer.close() }; navController.safeNavigate(route) },
                    onDeletedCurrent = { sessionId = newDraft() },
                )
            }
        },
    ) {
        val vm: ChatViewModel = viewModel(
            key = "clawdy_$sessionId",
            viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
            factory = ChatViewModel.factory(
                sessionId = sessionId,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                appContext = ctx.applicationContext,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            ),
        )
        ClawdyConversation(
            vm = vm,
            sessionId = sessionId,
            navController = navController,
            providerRepository = providerRepository,
            onMenu = { scope.launch { drawer.open() } },
            onNewChat = { sessionId = newDraft() },
        )
    }
}

@Composable
fun rememberToolStatus(): ToolStatus {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(ToolStatus.read(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) status = ToolStatus.read(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return status
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    else -> "晚上好"
}

@Composable
private fun ClawdyConversation(
    vm: ChatViewModel,
    sessionId: String,
    navController: NavHostController,
    providerRepository: ProviderRepository,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = Clawdy.colors
    val messages by vm.uiMessages.collectAsState()
    val streamingById by vm.streamingById.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val modelName by vm.modelName.collectAsState()
    val title by vm.sessionTitle.collectAsState()
    val error by vm.error.collectAsState()
    val pending by RiskGate.pending.collectAsState()
    val tools = rememberToolStatus()

    val merged = remember(messages, streamingById) { mergeStreamingOverlay(messages, streamingById) }
    val turns = remember(merged) { Turns.build(merged) }
    val empty = turns.isEmpty()
    val persisted = ChatViewModelStore.rememberPersistedId(sessionId)
    val waitingHere = pending != null && (pending?.sessionId == persisted || pending?.sessionId == sessionId)

    var showModelSheet by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<Step, Int>?>(null) }
    val expanded = remember(sessionId) { mutableStateMapOf<String, Boolean>() }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        // Top bar: ☰ · title · new chat
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyIconButton(ClawdyIcons.Menu, "菜单", onMenu)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!empty) {
                    Text(title.ifBlank { "新对话" }, color = c.text, style = serif(16.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ClawdyIconButton(ClawdyIcons.NewChat, "新对话", onNewChat)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (empty) HomeEmpty()
            else ChatList(
                turns = turns,
                isStreaming = isStreaming,
                waitingHere = waitingHere,
                expanded = expanded,
                onStep = { detail = it to 1 },
                onRetryStep = { s -> if (!vm.rerunFromToolBlock(s.messageId, s.block.id)) Toast.makeText(ctx, "当前无法重试", Toast.LENGTH_SHORT).show() },
                onShowError = { detail = it to 2 },
                onRetryTurn = { t -> t.userMessageId?.let { vm.retryFromMessage(it) } },
            )
        }

        if (error != null && !isStreaming) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(ClawdyTokens.BlockShape)
                    .background(c.error.copy(alpha = 0.10f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ClawdyIcons.Alert, null, tint = c.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(error ?: "", color = c.text, style = serif(13.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ClawdyIconButton(ClawdyIcons.Close, "关闭", { vm.clearError() }, size = 36.dp, iconSize = 16.dp, tint = c.text2)
            }
        }

        // Tools line (01): only on the empty home, above the composer.
        if (empty) {
            Row(
                Modifier.align(Alignment.CenterHorizontally).clip(CircleShape)
                    .clickable { navController.safeNavigate(ClawdyRoutes.TOOLS) }
                    .heightIn(min = 44.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(tools.connected > 0)
                Spacer(Modifier.width(6.dp))
                Text("已连接工具 ${tools.connected}/${tools.total}", color = c.text2, style = serif(12.sp))
            }
        }

        Composer(
            vm = vm,
            isStreaming = isStreaming,
            modelLabel = modelName.ifBlank { if (providerRepository.instances.isEmpty()) ClawdyPresets.DEFAULT_MODEL_NAME else "选择模型" },
            onPlus = { showAttach = true },
            onModel = { showModelSheet = true },
        )
    }

    if (showModelSheet) {
        ModelSheet(
            vm = vm, providerRepository = providerRepository,
            onAddProvider = { showModelSheet = false; navController.safeNavigate(ClawdyRoutes.PROVIDER_ADD) },
            onDismiss = { showModelSheet = false },
        )
    }
    if (showAttach) {
        AttachSheet(vm = vm, onDismiss = { showAttach = false }, onWorkspace = { showAttach = false; navController.safeNavigate(ClawdyRoutes.WORKSPACE) })
    }
    detail?.let { (s, tab) -> StepDetailSheet(s, tab) { detail = null } }
    pending?.let { req -> ApprovalSheet(req, onEditRequest = { text -> vm.setInputText(text) }) }
}

@Composable
private fun HomeEmpty() {
    val c = Clawdy.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ClawdyAvatar(AvatarMood.IDLE, 120.dp)
        Spacer(Modifier.height(28.dp))
        Text(greeting(), color = c.text, style = serif(28.sp, FontWeight.SemiBold, 36.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("今天想让我做点什么？", color = c.text2, style = serif(15.sp, line = 22.sp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChatList(
    turns: List<TurnItem>,
    isStreaming: Boolean,
    waitingHere: Boolean,
    expanded: MutableMap<String, Boolean>,
    onStep: (Step) -> Unit,
    onRetryStep: (Step) -> Unit,
    onShowError: (Step) -> Unit,
    onRetryTurn: (TurnItem.Assistant) -> Unit,
) {
    val listState = rememberLazyListState()
    val last = turns.lastOrNull()
    val lastSig = (last as? TurnItem.Assistant)?.let { it.text.length + it.steps.size * 1000 } ?: turns.size
    LaunchedEffect(turns.size, lastSig) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(turns, key = { it.key }) { item ->
            when (item) {
                is TurnItem.User -> UserBubble(item)
                is TurnItem.Assistant -> AssistantTurn(
                    item,
                    isLast = item === last,
                    waiting = waitingHere && item === last,
                    expanded = expanded[item.key] == true,
                    onToggle = { expanded[item.key] = expanded[item.key] != true },
                    onStep = onStep, onRetryStep = onRetryStep, onShowError = onShowError,
                    onRetryTurn = { onRetryTurn(item) }, streamingAny = isStreaming,
                )
            }
        }
    }
}

@Composable
private fun UserBubble(item: TurnItem.User) {
    val c = Clawdy.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(ClawdyTokens.UserBubbleShape).background(c.userBubble).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (item.message.attachmentNames.isNotEmpty()) {
                Text(item.message.attachmentNames.joinToString("、"), color = c.text2, style = serif(12.sp), maxLines = 2)
                Spacer(Modifier.height(4.dp))
            }
            Text(item.message.content, color = c.text, style = serif(15.sp, line = 23.sp))
            if (item.message.isQueued) Text("排队中", color = c.text2, style = serif(11.sp))
        }
    }
}

@Composable
private fun AssistantTurn(
    turn: TurnItem.Assistant,
    isLast: Boolean,
    waiting: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStep: (Step) -> Unit,
    onRetryStep: (Step) -> Unit,
    onShowError: (Step) -> Unit,
    onRetryTurn: () -> Unit,
    streamingAny: Boolean,
) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val state = Turns.cardState(turn, waiting)
    val mood = when (state) {
        CardState.RUNNING -> AvatarMood.WORKING
        CardState.WAITING -> AvatarMood.WAITING
        CardState.FAILED -> AvatarMood.ERROR
        CardState.DONE -> if (isLast) AvatarMood.HAPPY else AvatarMood.IDLE
        CardState.STOPPED -> AvatarMood.IDLE
    }
    var feedback by remember(turn.key) { mutableStateOf(0) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        ClawdyAvatar(mood, 28.dp, Modifier.padding(top = 4.dp), breathe = state == CardState.RUNNING, glow = false, periodMs = ClawdyMotion.BreathWorking)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val showCard = turn.steps.isNotEmpty() || (turn.streaming && turn.text.isBlank()) || state == CardState.FAILED
            if (showCard) {
                TaskCard(turn, state, expanded, onToggle, onStep, onRetryStep, onShowError)
            }
            if (turn.text.isNotBlank()) {
                MarkdownText(turn.text, color = c.text, style = serif(15.sp, line = 24.sp))
            }
            if (!turn.streaming) {
                Turns.producedFiles(turn.text).forEach { FileCard(it) }
                if (turn.text.isNotBlank()) {
                    Row(Modifier.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        ClawdyIconButton(ClawdyIcons.Copy, "复制", { copyText(ctx, turn.text) }, size = 40.dp, iconSize = 18.dp, tint = c.text2)
                        ClawdyIconButton(ClawdyIcons.Retry, "重试", onRetryTurn, size = 40.dp, iconSize = 18.dp, tint = c.text2, enabled = !streamingAny && turn.userMessageId != null)
                        ClawdyIconButton(ClawdyIcons.ThumbUp, "赞", { feedback = if (feedback == 1) 0 else 1 }, size = 40.dp, iconSize = 18.dp, tint = if (feedback == 1) c.accent else c.text2)
                        ClawdyIconButton(ClawdyIcons.ThumbDown, "踩", { feedback = if (feedback == -1) 0 else -1 }, size = 40.dp, iconSize = 18.dp, tint = if (feedback == -1) c.error else c.text2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    vm: ChatViewModel,
    isStreaming: Boolean,
    modelLabel: String,
    onPlus: () -> Unit,
    onModel: () -> Unit,
) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val text by vm.inputText.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val hasContent = text.isNotBlank() || attachments.isNotEmpty()
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(if (focused) c.accent else c.border, tween(150), label = "b")
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (said.isNotBlank()) vm.setInputText(if (text.isBlank()) said else "$text $said")
        }
    }
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
            .shadow(10.dp, ClawdyTokens.InputShape, ambientColor = Color(0x142B211C), spotColor = Color(0x142B211C))
            .clip(ClawdyTokens.InputShape).background(c.surface).border(1.dp, borderColor, ClawdyTokens.InputShape)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.take(4).forEach { a ->
                    Row(
                        Modifier.clip(CircleShape).background(c.taskCard).padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (a.isImage) ClawdyIcons.Image else ClawdyIcons.File, null, tint = c.accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(a.fileName, color = c.text, style = serif(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                        ClawdyIconButton(ClawdyIcons.Close, "移除", { vm.removeAttachment(a.id) }, size = 32.dp, iconSize = 14.dp, tint = c.text2)
                    }
                }
                if (attachments.size > 4) Text("+${attachments.size - 4}", color = c.text2, style = serif(12.sp), modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            if (text.isEmpty()) Text("和 Clawdy 说点什么…", color = c.text2, style = serif(16.sp, line = 24.sp))
            BasicTextField(
                value = text,
                onValueChange = { vm.setInputText(it) },
                textStyle = serif(16.sp, line = 24.sp).merge(TextStyle(color = c.text)),
                cursorBrush = SolidColor(c.accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).border(1.dp, c.border, CircleShape).clickable(onClick = onPlus),
                contentAlignment = Alignment.Center,
            ) { Icon(ClawdyIcons.Plus, "附件", tint = c.text, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.height(32.dp).clip(CircleShape).background(c.taskCard).clickable(onClick = onModel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelLabel, color = c.text, style = serif(12.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                Spacer(Modifier.width(2.dp))
                Icon(ClawdyIcons.ChevronDown, null, tint = c.text2, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))
            ClawdyIconButton(ClawdyIcons.Mic, "语音输入", {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                runCatching { speech.launch(i) }.onFailure { Toast.makeText(ctx, "这台手机没有可用的语音识别", Toast.LENGTH_SHORT).show() }
            }, tint = c.text2, size = 44.dp)
            val src = remember { MutableInteractionSource() }
            val showStop = isStreaming && !hasContent
            val enabled = showStop || hasContent
            val bg by animateColorAsState(if (enabled) c.accent else c.off, tween(150), label = "send")
            Box(
                Modifier.pressScale(src).size(40.dp).clip(CircleShape).background(bg)
                    .clickable(interactionSource = src, indication = null, enabled = enabled) {
                        if (showStop) vm.cancelStream()
                        else {
                            val t = text
                            vm.setInputText("")
                            vm.sendMessage(t)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (showStop) ClawdyIcons.Stop else ClawdyIcons.Send, if (showStop) "停止" else "发送", tint = Color.White, modifier = Modifier.size(if (showStop) 18.dp else 20.dp))
            }
        }
    }
}

// ───────────────────────── 08 drawer ─────────────────────────

@Composable
private fun ClawdyDrawer(
    chatRepository: ChatRepository,
    currentSessionId: String,
    onNewChat: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onDeletedCurrent: () -> Unit,
) {
    val c = Clawdy.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by chatRepository.dao.observeSessionsSorted().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ChatSessionEntity?>(null) }
    val shown = remember(sessions, query) {
        if (query.isBlank()) sessions else sessions.filter { (it.title ?: "").contains(query, true) || (it.lastMessage ?: "").contains(query, true) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ClawdyIcons.Search, null, tint = c.text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索对话", color = c.text2, style = serif(14.sp))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = serif(14.sp).merge(TextStyle(color = c.text)),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(12.dp))
        DrawerEntry(ClawdyIcons.Plus, "新对话", onNewChat, highlight = true)
        DrawerEntry(ClawdyIcons.Folder, "工作区文件", { onNavigate(ClawdyRoutes.WORKSPACE) })
        DrawerEntry(ClawdyIcons.Clock, "定时任务", { onNavigate(Routes.SCHEDULED_TASKS) })
        DrawerEntry(ClawdyIcons.Memory, "记忆", { onNavigate(Routes.MEMORY) })
        Text("最近", color = c.text2, style = serif(12.sp, FontWeight.Medium), modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                val selected = s.id == currentSessionId
                Box {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) c.taskCard else Color.Transparent)
                            .combinedClickable(onClick = { onOpenSession(s.id) }, onLongClick = { menu = true })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (s.pinnedAt != null) {
                            Icon(ClawdyIcons.Pin, "已置顶", tint = c.accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                        }
                        Text(s.title?.ifBlank { null } ?: "新对话", color = c.text, style = serif(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(menu, { menu = false }, containerColor = c.surface) {
                        DropdownMenuItem({ Text("重命名", style = serif(14.sp)) }, { menu = false; renaming = s },
                            leadingIcon = { Icon(ClawdyIcons.Edit, null, Modifier.size(18.dp)) })
                        DropdownMenuItem({ Text(if (s.pinnedAt != null) "取消置顶" else "置顶", style = serif(14.sp)) }, {
                            menu = false
                            scope.launch { chatRepository.dao.updatePinnedAt(s.id, if (s.pinnedAt != null) null else System.currentTimeMillis()) }
                        }, leadingIcon = { Icon(ClawdyIcons.Pin, null, Modifier.size(18.dp)) })
                        DropdownMenuItem({ Text("删除", color = c.error, style = serif(14.sp)) }, {
                            menu = false
                            scope.launch {
                                chatRepository.deleteSession(s.id)
                                if (s.id == currentSessionId) onDeletedCurrent()
                            }
                        }, leadingIcon = { Icon(ClawdyIcons.Trash, null, Modifier.size(18.dp), tint = c.error) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawdyAvatar(AvatarMood.IDLE, 36.dp, breathe = false, glow = false)
            Spacer(Modifier.width(10.dp))
            Text(ClawdyPrefs.str(ctx, ClawdyPrefs.KEY_NICKNAME) ?: NanoMuseIdentity.name(ctx), color = c.text, style = serif(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            ClawdyIconButton(ClawdyIcons.Settings, "设置", { onNavigate(ClawdyRoutes.SETTINGS) }, tint = c.text2)
        }
    }
    renaming?.let { s ->
        RenameDialog(s.title ?: "", onDismiss = { renaming = null }) { t ->
            renaming = null
            scope.launch { chatRepository.dao.updateSessionTitle(s.id, t, System.currentTimeMillis()) }
        }
    }
}

@Composable
private fun DrawerEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, highlight: Boolean = false) {
    val c = Clawdy.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlight) c.accent else c.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (highlight) c.accent else c.text, style = serif(15.sp, if (highlight) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = Clawdy.colors
    var t by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = ClawdyTokens.CardShape,
        title = { Text("重命名", style = serif(18.sp, FontWeight.SemiBold), color = c.text) },
        text = { ClawdyTextField(t, { t = it }, "对话名称") },
        confirmButton = { ClawdyButton("保存", { onConfirm(t.trim()) }, enabled = t.isNotBlank(), height = 40.dp) },
        dismissButton = { ClawdyButton("取消", onDismiss, kind = ButtonKind.GHOST, height = 40.dp) },
    )
}

@Composable
fun ClawdyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Clawdy.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ClawdyTokens.ButtonShape).background(c.bg)
            .border(1.dp, c.border, ClawdyTokens.ButtonShape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            val style = (if (mono) Mono.copy(fontSize = 14.sp) else serif(15.sp)).merge(TextStyle(color = c.text))
            if (value.isEmpty()) Text(placeholder, color = c.text2, style = style.copy(color = c.text2))
            BasicTextField(value, onValueChange, singleLine = singleLine, textStyle = style, cursorBrush = SolidColor(c.accent),
                visualTransformation = visualTransformation, modifier = Modifier.fillMaxWidth())
        }
        trailing?.invoke()
    }
}
