package com.openminis.app.clawdy

import android.content.Context
import io.github.nanomuse.guard.GateMode
import io.github.nanomuse.guard.RiskGate

/**
 * Settings → 安全. Every switch here is read by the code it names; nothing is a dead toggle.
 *
 * - 工作模式 → [RiskGate.mode] (see [GateMode]).
 * - 截图上传 → `HandsOperator`: off (default) = the screen model gets the accessibility text of the
 *   screen ([io.github.nanomuse.hands.ScreenText]), never a screenshot.
 * - 不在密码框上操作 → `HandsOperator.typeText`: on (default) = the screen model never types into
 *   a password / code field; the user takes over. Vault fills are the separate sanctioned path.
 * - 敏感应用 → `HandsOperator`: Hands stops before acting inside one of these apps.
 * - 剪贴板读取需确认 → `termux_run`: a command that reads the clipboard raises the approval card.
 * - 密码箱自动锁定 → [com.openminis.app.clawdy.vault.Vault.unlockedUntil].
 */
object ClawdySecurity {
    const val KEY_WORK_MODE = "work_mode"                  // 0 谨慎 · 1 标准 · 2 放手
    const val KEY_SENSITIVE_APPS = "sensitive_apps"        // newline-separated package names
    const val KEY_SENSITIVE_APPS_SEEDED = "sensitive_apps_seeded"
    const val KEY_CLIPBOARD_CONFIRM = "clipboard_confirm"  // default true
    const val KEY_VAULT_LOCK_SECONDS = "vault_lock_seconds" // default 60

    /** Banking and payment apps Hands never operates unless the user takes them off the list. */
    val DEFAULT_SENSITIVE_APPS = listOf(
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.unionpay",                  // 云闪付
        "com.icbc",                      // 工商银行
        "com.chinamworld.main",          // 建设银行
        "com.android.bankabc",           // 农业银行
        "com.chinamworld.bocmbci",       // 中国银行
        "cmb.pb",                        // 招商银行
        "com.bankcomm.Bankcomm",         // 交通银行
        "com.ecitic.bank.mobile",        // 中信银行
        "com.yitong.mbank.psbc",         // 邮储银行
        "com.paypal.android.p2pmobile",  // PayPal
        "com.google.android.apps.walletnfcrel", // Google Wallet
    )

    val LOCK_CHOICES = listOf(0 to "每次都验证", 60 to "1 分钟", 300 to "5 分钟", 900 to "15 分钟")

    fun mode(context: Context): GateMode {
        val p = ClawdyPrefs.prefs(context)
        if (!p.contains(KEY_WORK_MODE)) return GateMode.STANDARD
        return when (p.getInt(KEY_WORK_MODE, 1)) {
            0 -> GateMode.CAUTIOUS
            2 -> GateMode.RELAXED
            else -> GateMode.STANDARD
        }
    }

    fun setMode(context: Context, mode: GateMode) {
        ClawdyPrefs.setInt(context, KEY_WORK_MODE, mode.ordinal)
        RiskGate.mode = mode
    }

    /** Pushes the stored settings into the code that enforces them. Called from MinisApp.onCreate. */
    fun apply(context: Context) {
        RiskGate.mode = mode(context)
    }

    fun screenshotUpload(context: Context) = ClawdyPrefs.bool(context, ClawdyPrefs.KEY_SCREENSHOT_UPLOAD, false)
    fun noPasswordFields(context: Context) = ClawdyPrefs.bool(context, ClawdyPrefs.KEY_NO_PASSWORD_FIELDS, true)
    fun clipboardConfirm(context: Context) = ClawdyPrefs.bool(context, KEY_CLIPBOARD_CONFIRM, true)
    fun vaultLockSeconds(context: Context) = ClawdyPrefs.int(context, KEY_VAULT_LOCK_SECONDS, 60)

    fun sensitiveApps(context: Context): Set<String> {
        val p = ClawdyPrefs.prefs(context)
        if (!p.getBoolean(KEY_SENSITIVE_APPS_SEEDED, false)) {
            p.edit().putString(KEY_SENSITIVE_APPS, DEFAULT_SENSITIVE_APPS.joinToString("\n")).putBoolean(KEY_SENSITIVE_APPS_SEEDED, true).apply()
            return DEFAULT_SENSITIVE_APPS.toSet()
        }
        return p.getString(KEY_SENSITIVE_APPS, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setSensitive(context: Context, pkg: String, on: Boolean) {
        val now = sensitiveApps(context).toMutableSet()
        if (on) now += pkg else now -= pkg
        ClawdyPrefs.prefs(context).edit().putString(KEY_SENSITIVE_APPS, now.sorted().joinToString("\n")).apply()
    }

    fun isSensitive(context: Context, pkg: String?): Boolean = pkg != null && pkg in sensitiveApps(context)

    /** termux-api commands that read the clipboard (its content then goes to the model). */
    private val CLIPBOARD_READ = Regex("""\btermux-clipboard-get\b|\bam\s+broadcast\b.*clipper\.get""")
    fun readsClipboard(command: String) = CLIPBOARD_READ.containsMatchIn(command)
}
