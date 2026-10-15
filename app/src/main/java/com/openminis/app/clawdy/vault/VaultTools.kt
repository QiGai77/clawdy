package com.openminis.app.clawdy.vault

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import io.github.nanomuse.hands.HandsApps
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * A fill the agent asked for, waiting on the user's approval sheet ([com.openminis.app.ui.clawdy.VaultFillSheet]).
 * Carries names only — the entry's label, the app it goes into, the fields — never a value.
 */
data class VaultFillRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val entryId: String,
    val entryLabel: String,
    val entryTarget: String,
    val appPackage: String?,
    val appLabel: String?,
    /** Field keys that will be filled, in the order they go in. */
    val fields: List<String>,
)

/** One approval at a time; the agent waits on [ask] until the sheet answers (3 minutes, then a denial). */
object VaultFillGate {
    private val _pending = MutableStateFlow<VaultFillRequest?>(null)
    val pending: StateFlow<VaultFillRequest?> = _pending.asStateFlow()
    private var cont: CancellableContinuation<Boolean>? = null

    suspend fun ask(r: VaultFillRequest): Boolean = withTimeoutOrNull(180_000) {
        suspendCancellableCoroutine<Boolean> { c ->
            synchronized(this@VaultFillGate) {
                cont?.let { if (it.isActive) it.resume(false) }
                cont = c
                _pending.value = r
            }
            c.invokeOnCancellation { synchronized(this@VaultFillGate) { if (_pending.value?.id == r.id) _pending.value = null } }
        }
    } ?: run { synchronized(this) { if (_pending.value?.id == r.id) _pending.value = null }; false }

    fun decide(id: String, allow: Boolean) {
        val c = synchronized(this) {
            if (_pending.value?.id != id) return
            _pending.value = null
            cont.also { cont = null }
        }
        if (c?.isActive == true) c.resume(allow)
    }
}

/**
 * `vault_list` and `vault_fill`: the agent's only access to the 密码箱.
 *
 * - vault_list returns ids, types, labels, sites and *which* fields exist. No value.
 * - vault_fill finds the login form on the screen, asks the user in the approval sheet (entry,
 *   app, fields; confirmed with the screen lock), and only then decrypts and types the values
 *   into the fields locally with ACTION_SET_TEXT. The result is "filled" / "denied" / "not_found"
 *   and field names. Values never enter the tool result, the chat, the logs or any model request.
 */
object VaultTools {
    const val LIST = "vault_list"
    const val FILL = "vault_fill"
    private const val TAG = "VaultFill"

    /** Values filled recently, so the Hands text view of the screen can blank them (see ScreenText). */
    private val recentlyFilled = java.util.Collections.synchronizedList(ArrayList<String>())
    fun redactions(): List<String> = synchronized(recentlyFilled) { recentlyFilled.toList() }

    fun listDefinition() = AgentToolDefinition(
        name = LIST,
        description = "List the entries in the user's on-device 密码箱 (privacy vault): id, type (LOGIN/CARD/ADDRESS/NOTE), label, site or app, and which fields each has. " +
            "Values are never returned. Use it to find the entry to pass to vault_fill.",
        parameters = mapOf("tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, in the user's language.")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    fun fillDefinition() = AgentToolDefinition(
        name = FILL,
        description = "Fill one 密码箱 entry into the form that is on the phone's screen right now (another app's login page, after phone_hands stopped there). " +
            "The user approves every fill in a sheet (and confirms with the screen lock); the app types the values itself. You never see the values: the result is only filled / denied / not_found. " +
            "fields: 'login' (username + password, the default for LOGIN entries) or one field key such as username, password, phone, email, number, holder, expiry, cvv, name, address, postcode — a single field goes into the field that has the cursor. " +
            "After a fill, continue with phone_hands (e.g. tap the sign-in button); never ask the user to tell you the password.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, in the user's language."),
            "entry" to AgentToolParam("string", "The entry id from vault_list, or its label / site."),
            "fields" to AgentToolParam("string", "'login' or one field key. Default: login for LOGIN entries, otherwise required."),
        ),
        required = listOf("tool_title", "entry"),
        propertyOrdering = listOf("tool_title", "entry", "fields"),
    )

    fun list(context: Context): ToolExecutionResult {
        Vault.init(context)
        val arr = JSONArray()
        Vault.entries.value.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("type", m.type.name).put("label", m.label)
                .put("site_or_app", m.target).put("fields", JSONArray(m.fieldKeys)))
        }
        val body = JSONObject().put("count", arr.length()).put("entries", arr)
        if (arr.length() == 0) body.put("tell_user", "The vault is empty; the user can add entries in 设置 → 安全 → 密码箱.")
        return ToolExecutionResult(body.toString(2), true, toolTitle = "查看密码箱条目")
    }

    suspend fun fill(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", "填入密码箱条目")
        fun result(status: String, message: String, ok: Boolean, extra: JSONObject.() -> Unit = {}) =
            ToolExecutionResult(JSONObject().put("status", status).put("message", message).apply(extra).toString(2), ok, toolTitle = title)
        Vault.init(context)
        val meta = Vault.find(args.optString("entry", ""))
            ?: return result("not_found", "No vault entry matches. Call vault_list for the labels; if none fits, ask the user to add one in 设置 → 安全 → 密码箱.", false)
        val svc = MinisAccessibilityService.getInstance()
            ?: return result("unavailable", "The accessibility service (Hands) is off, so nothing can be filled. Tell the user to turn it on in 工具连接.", false)
        var pkg = svc.foregroundPackage().first
        if (pkg == null || pkg == context.packageName || pkg == "com.android.systemui") {
            // a Hands run ends by bringing Clawdy back: go back to the app it left on screen
            val last = io.github.nanomuse.hands.Hands.lastOperated?.takeIf { System.currentTimeMillis() - it.second < 15 * 60_000L }?.first
                ?: return result("no_form", "No other app is in front. Open the app's login page first (phone_hands), then call vault_fill.", false)
            reopen(context, last)
            delay(1500)
            pkg = last
        }
        val want = args.optString("fields", "").trim().lowercase().ifEmpty { if (meta.type == Vault.Type.LOGIN) "login" else "" }
        val fields = when {
            want == "login" -> listOf("username", "password").filter { it in meta.fieldKeys }
            want.isEmpty() -> return result("bad_request", "Say which field to fill (one of ${meta.fieldKeys.joinToString()}).", false)
            want in meta.fieldKeys -> listOf(want)
            else -> return result("not_found", "Entry '${meta.label}' has no field '$want'. It has: ${meta.fieldKeys.joinToString()}.", false)
        }
        if (fields.isEmpty()) return result("not_found", "Entry '${meta.label}' has no username or password saved.", false)
        // the form has to be there before the user is asked
        val app = pkg!!
        val plan = withContext(Dispatchers.Default) { planTargets(svc, app, fields) }
            ?: return result("no_form", "No matching input field is on the screen (${fields.joinToString()}). Tap the field or open the login page, then try again.", false)

        val appLabel = HandsApps.labelOf(context, app)
        val req = VaultFillRequest(sessionId = sessionId, entryId = meta.id, entryLabel = meta.label, entryTarget = meta.target,
            appPackage = app, appLabel = appLabel, fields = plan.keys.toList())
        AppLogger.info(TAG, "fill requested: entry=${meta.id} app=$app fields=${req.fields}")
        bringClawdyToFront(context, sessionId)
        val allowed = VaultFillGate.ask(req)
        if (!allowed) {
            reopen(context, app)
            return result("denied", "The user did not allow filling '${meta.label}'. Do not retry; ask how they want to continue.", false)
        }
        val values = try {
            Vault.readValues(context, meta.id)
        } catch (e: Exception) {
            reopen(context, app)
            return result("locked", "The vault could not be unlocked (${e.javaClass.simpleName}). Ask the user to try again.", false)
        }
        reopen(context, app)
        delay(1500)
        val filled = withContext(Dispatchers.Default) {
            val fresh = planTargets(svc, app, fields) ?: emptyMap()
            fresh.mapNotNull { (key, node) ->
                val v = values[key] ?: return@mapNotNull null
                if (svc.setNodeText(node, v)) {
                    if (!Vault.fieldsOf(meta.type).first { it.key == key }.secret) synchronized(recentlyFilled) {
                        recentlyFilled += v; while (recentlyFilled.size > 20) recentlyFilled.removeAt(0)
                    }
                    key
                } else null
            }
        }
        AppLogger.info(TAG, "fill done: entry=${meta.id} filled=$filled")
        return if (filled.isEmpty()) result("failed", "The fields did not accept text after approval (the page may have changed). Tell the user; do not retry on your own.", false)
        else result("filled", "Filled ${filled.joinToString()} from '${meta.label}' into ${appLabel ?: app}. Continue the task (e.g. tap the sign-in button with phone_hands).", true) {
            put("entry", meta.label); put("fields", JSONArray(filled))
        }
    }

    /**
     * Which on-screen node each field goes into. A password goes into the password field; a
     * username into the editable field just before it (or the one with the cursor). A single
     * non-login field goes into the field that has the cursor.
     */
    private fun planTargets(svc: MinisAccessibilityService, pkg: String, fields: List<String>): Map<String, AccessibilityNodeInfo>? {
        val roots = svc.rootNodes().filter { it.packageName?.toString() == pkg }
        if (roots.isEmpty()) return null
        val editable = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collectEditable(it, editable, 0) }
        editable.sortWith(compareBy({ r(it).top }, { r(it).left }))
        val focused = roots.firstNotNullOfOrNull { runCatching { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() }
        val out = LinkedHashMap<String, AccessibilityNodeInfo>()
        val pwd = editable.firstOrNull { it.isPassword }
        if (fields.size > 1 || fields.first() in setOf("username", "password")) {
            if ("username" in fields) {
                val idx = pwd?.let { p -> editable.indexOfFirst { r(it) == r(p) } } ?: -1
                val user = (if (idx > 0) editable.subList(0, idx).lastOrNull { !it.isPassword } else null)
                    ?: focused?.takeIf { !it.isPassword }
                    ?: editable.firstOrNull { !it.isPassword }
                user?.let { out["username"] = it }
            }
            if ("password" in fields) (pwd ?: focused?.takeIf { it.isPassword })?.let { out["password"] = it }
        } else {
            (focused ?: editable.singleOrNull())?.let { out[fields.first()] = it }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun r(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    private fun collectEditable(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 60) return
        if (n.isEditable && n.isVisibleToUser && n.isEnabled) out += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectEditable(it, out, depth + 1) }
    }

    private fun bringClawdyToFront(context: Context, sessionId: String) {
        runCatching {
            val i = Intent(context, Class.forName("com.openminis.app.MainActivity")).apply {
                if (sessionId.isNotBlank() && !sessionId.startsWith("__new__")) data = android.net.Uri.parse("minis://session/$sessionId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(i)
        }.onFailure { AppLogger.warning(TAG, "bring to front failed: ${it.message}") }
    }

    private suspend fun reopen(context: Context, pkg: String) {
        HandsApps.launchable(context).firstOrNull { it.packageName == pkg }?.let { HandsApps.open(context, it) }
        delay(300)
    }
}
