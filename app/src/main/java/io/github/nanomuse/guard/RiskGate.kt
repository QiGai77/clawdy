package io.github.nanomuse.guard

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/** One thing the agent wants to do and is waiting on. */
data class RiskRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val kind: GuardKind,
    val assessment: RiskAssessment,
    /** The command, or "Tap “Pay now”". Shown verbatim in the card's preview box. */
    val preview: String,
    /** For browser requests: the page the tap lands on. */
    val pageUrl: String? = null,
    /** For browser requests: the element's own text. */
    val elementText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Clawdy 工作模式「谨慎」: nothing on this card may be remembered. */
    val cautious: Boolean = false,
) {
    /**
     * What may be remembered. Nothing alarming (a warning) is ever remembered. "For this chat"
     * is offered for deleting and sending, not for money. "Always for X" needs an object to
     * bind to; for money it is the highest tier — offered as a deliberate choice that the card
     * confirms with the phone's screen lock ([needsCredential]), and the first time is always
     * asked because the grant can only come from the card.
     */
    val canSession: Boolean get() = !cautious && assessment.warnings.isEmpty() && assessment.riskClass != RiskClass.MONEY && kind != GuardKind.DEVICE
    val canAlways: Boolean get() = !cautious && assessment.warnings.isEmpty() && !assessment.target.isNullOrBlank()
    val canRemember: Boolean get() = canSession || canAlways
    val needsCredential: Boolean get() = assessment.riskClass == RiskClass.MONEY
}

/**
 * Clawdy 工作模式 (Settings → 安全 → 工作模式), set by `ClawdySecurity.apply`:
 * - [CAUTIOUS] 谨慎: every sensitive action asks, every time; installs ask too; nothing is remembered
 *   and remembered grants are ignored.
 * - [STANDARD] 标准: upstream behaviour; the card offers "本对话内同类操作不再询问" (checked by default).
 * - [RELAXED] 放手: only payments, sending messages (screen / browser), deletions and always-ask
 *   warnings ask; network transfers from the shell and other-device requests run with a notice; an
 *   approval is remembered for the rest of the chat automatically (never for money).
 */
enum class GateMode { CAUTIOUS, STANDARD, RELAXED }

enum class RiskDecision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY, TIMEOUT }

sealed class GateOutcome {
    /** Go ahead. [notice] is a line for the model when something ran without asking (installs). */
    data class Allowed(val notice: String? = null) : GateOutcome()
    /** Do not run it; [message] is what the model is told. */
    data class Denied(val message: String) : GateOutcome()
}

/**
 * The approval queue: one card at a time, requests behind it wait their turn. Same shape as
 * OpenMinis' `ConfigConfirmationGate` so the background-notification plumbing can be shared.
 * A request that gets no answer within [TIMEOUT_MS] is a denial — the agent stops and waits
 * rather than doing the thing on a stale "yes".
 */
object RiskGate {
    private const val TAG = "RiskGate"
    const val TIMEOUT_MS: Long = 180_000

    /** The user's 工作模式; see [GateMode]. */
    @Volatile var mode: GateMode = GateMode.STANDARD

    private val _pending = MutableStateFlow<RiskRequest?>(null)
    val pending: StateFlow<RiskRequest?> = _pending.asStateFlow()

    /** How many are waiting behind the visible one. */
    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued.asStateFlow()

    private val queue = ArrayDeque<Pair<RiskRequest, Continuation<RiskDecision>>>()
    private val awaiters = HashMap<String, Continuation<RiskDecision>>()
    private val timeouts = HashMap<String, Job>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Posts a notification when the app is backgrounded; returns true if it did. Wired in MinisApp. */
    @Volatile var backgroundNotifier: ((RiskRequest) -> Boolean)? = null
    @Volatile var cancelNotification: ((String) -> Unit)? = null
    private val notified = HashSet<String>()

    /** Decisions taken, newest first, for the log line under the card and for debugging. */
    private val _recent = MutableStateFlow<List<Pair<RiskRequest, RiskDecision>>>(emptyList())
    val recent: StateFlow<List<Pair<RiskRequest, RiskDecision>>> = _recent.asStateFlow()

    /**
     * Waits for the person's answer. Cancelling the caller (the chat was stopped, the session
     * closed) withdraws the request: the card goes away and the next one in line comes up,
     * instead of a stale card living on until the timeout.
     */
    suspend fun ask(request: RiskRequest): RiskDecision = suspendCancellableCoroutine { cont ->
        // The withdrawal is registered before the request is queued, and the queueing checks
        // that the caller is still there. Before, a cancel that landed before the register ran
        // relied on the two hops reaching the main thread in order; had the register waited on
        // the lock, the withdrawal would have found nothing and the dead request would have sat
        // on the card until the three-minute timeout.
        cont.invokeOnCancellation { scope.launch { resolve(request.id, RiskDecision.DENY, record = false) } }
        scope.launch {
            mutex.withLock {
                if (!cont.isActive) return@withLock
                awaiters[request.id] = cont
                timeouts[request.id] = scope.launch {
                    delay(TIMEOUT_MS)
                    resolve(request.id, RiskDecision.TIMEOUT)
                }
                if (_pending.value == null) {
                    _pending.value = request
                    notifyIfBackgrounded(request)
                } else {
                    queue.addLast(request to cont)
                    _queued.value = queue.size
                }
            }
        }
    }

    fun decide(id: String, decision: RiskDecision) {
        scope.launch { resolve(id, decision) }
    }

    fun notifyPending() {
        _pending.value?.let { notifyIfBackgrounded(it) }
    }

    /** A withdrawn request ([record] false) leaves the card and the queue but is not a decision anyone took. */
    private suspend fun resolve(id: String, decision: RiskDecision, record: Boolean = true) {
        var cont: Continuation<RiskDecision>? = null
        var advancedTo: RiskRequest? = null
        var resolved: RiskRequest? = null
        mutex.withLock {
            cont = awaiters.remove(id) ?: return@withLock
            timeouts.remove(id)?.cancel()
            notified.remove(id)
            if (_pending.value?.id == id) {
                resolved = _pending.value
                val next = queue.removeFirstOrNull()
                _pending.value = next?.first
                advancedTo = next?.first
                _queued.value = queue.size
            } else {
                // Still queued: drop it from the line.
                val idx = queue.indexOfFirst { it.first.id == id }
                if (idx >= 0) { resolved = queue[idx].first; queue.removeAt(idx); _queued.value = queue.size }
            }
        }
        val c = cont ?: return
        cancelNotification?.invoke(id)
        if (record) {
            resolved?.let { r -> _recent.value = (listOf(r to decision) + _recent.value).take(20) }
            AppLogger.info(TAG, "request $id → $decision")
        } else {
            AppLogger.info(TAG, "request $id withdrawn (caller cancelled)")
        }
        advancedTo?.let { notifyIfBackgrounded(it) }
        // A cancelled continuation ignores the resume; the others get their answer.
        c.resume(decision)
    }

    private fun notifyIfBackgrounded(request: RiskRequest) {
        val notifier = backgroundNotifier ?: return
        scope.launch {
            mutex.withLock {
                if (request.id in notified) return@launch
                if (_pending.value?.id != request.id) return@launch
                if (notifier(request)) notified.add(request.id)
            }
        }
    }

    // ── the two entry points the tools call ────────────────────────────────────────────────

    /** Decides for a shell command: grants first, then the card. */
    suspend fun checkShell(sessionId: String, command: String): GateOutcome {
        val a = ShellGuard.assess(command)
        return check(sessionId, GuardKind.SHELL, a, preview = command.trim())
    }

    suspend fun check(
        sessionId: String,
        kind: GuardKind,
        assessment: RiskAssessment,
        preview: String,
        pageUrl: String? = null,
        elementText: String? = null,
    ): GateOutcome {
        val m = mode
        val cautious = m == GateMode.CAUTIOUS
        val asksInstall = cautious && assessment.riskClass == RiskClass.INSTALL
        if (!assessment.needsApproval && !asksInstall) {
            return GateOutcome.Allowed(
                if (assessment.riskClass == RiskClass.INSTALL) INSTALL_NOTICE else null,
            )
        }
        if (m == GateMode.RELAXED && assessment.warnings.isEmpty() &&
            (assessment.riskClass == RiskClass.REMOTE ||
                (assessment.riskClass == RiskClass.OUTBOUND && (kind == GuardKind.SHELL || kind == GuardKind.COMPUTER)))
        ) {
            AppLogger.info(TAG, "relaxed mode: ran without asking (${assessment.riskClass})")
            return GateOutcome.Allowed(relaxedNotice(assessment.reason))
        }
        if (!cautious && assessment.warnings.isEmpty() && Grants.allows(assessment.riskClass, assessment.target, sessionId)) {
            AppLogger.info(TAG, "covered by a grant: ${Grants.key(assessment.riskClass, assessment.target)}")
            // A payment that ran on a standing grant is said out loud every time; the person
            // chose that, and the line is how they keep track of it.
            return GateOutcome.Allowed(
                if (assessment.riskClass == RiskClass.MONEY) autoPaidNotice(RiskText.plainTarget(assessment.target)) else null,
            )
        }
        val request = RiskRequest(
            sessionId = sessionId,
            kind = kind,
            assessment = assessment,
            preview = preview,
            pageUrl = pageUrl,
            elementText = elementText,
            cautious = cautious,
        )
        return when (ask(request)) {
            RiskDecision.ALLOW_ONCE -> {
                // 放手: one yes covers the same kind of action on the same object for the rest of the chat.
                if (m == GateMode.RELAXED && request.canSession) Grants.grantSession(assessment.riskClass, sessionId, assessment.reason, assessment.target)
                GateOutcome.Allowed()
            }
            RiskDecision.ALLOW_SESSION -> {
                if (request.canSession) Grants.grantSession(assessment.riskClass, sessionId, assessment.reason, assessment.target)
                GateOutcome.Allowed()
            }
            RiskDecision.ALLOW_ALWAYS -> {
                val target = assessment.target?.takeIf { request.canAlways }
                target?.let { Grants.grantAlways(assessment.riskClass, it, assessment.reason) }
                // Point the person to where it lives, once, right after they chose it.
                GateOutcome.Allowed(target?.let { rememberedNotice(RiskText.plainTarget(it), assessment.riskClass) })
            }
            RiskDecision.DENY -> GateOutcome.Denied(
                "The user did not allow this (${assessment.reason}). Do not retry it or work around it; " +
                    "tell them in one line what you were about to do and ask how they want to proceed.",
            )
            RiskDecision.TIMEOUT -> GateOutcome.Denied(
                "No answer from the user within 3 minutes, so this was not run (${assessment.reason}). " +
                    "Stop here and wait for them; do not retry on your own.",
            )
        }
    }

    fun relaxedNotice(reason: String): String =
        "[Clawdy] Ran without asking because the user chose the 放手 work mode ($reason). Mention it to the user in one line."

    const val INSTALL_NOTICE =
        "[nanoMuse] This command installs software; it ran without asking. Mention it to the user in one line."

    /** After "always allow for X": the model tells the user where the grant lives, once. */
    fun rememberedNotice(target: String, riskClass: RiskClass): String =
        "[nanoMuse] The user chose to remember this approval (${riskClass.name.lowercase()} · $target)" +
            (if (riskClass == RiskClass.MONEY) ", confirmed with their screen lock, so payments in $target will run without a card from now on" else "") +
            ". Tell them in one line that it is listed under Settings → Permissions → Remembered approvals, where it can be revoked."

    /** A payment that ran on a standing grant: always said out loud. */
    fun autoPaidNotice(target: String): String =
        "[nanoMuse] This payment step ran without asking because the user remembered approvals for $target. " +
            "Say so in one line, and remind them it can be revoked under Settings → Permissions."
}
