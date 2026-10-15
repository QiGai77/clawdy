package com.openminis.app.clawdy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.tools.TermuxTool

/** Real, re-checkable status of every tool Clawdy can drive. Call [read] on every resume. */
data class ToolStatus(
    val termuxInstalled: Boolean,
    val termuxPermission: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityBound: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val shizukuInstalled: Boolean,
    val shizukuBinder: Boolean,
    val shizukuGranted: Boolean,
) {
    val termuxReady get() = termuxInstalled && termuxPermission
    val handsReady get() = accessibilityEnabled && overlay
    val shizukuReady get() = shizukuBinder && shizukuGranted
    /** Connected tools out of the three the home line counts (Termux, Hands, Shizuku). */
    val connected get() = listOf(termuxReady, handsReady, shizukuReady).count { it }
    val total get() = 3

    companion object {
        fun read(context: Context): ToolStatus {
            val shizukuBinder = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
            val shizukuGranted = shizukuBinder && runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            return ToolStatus(
                termuxInstalled = TermuxTool.isTermuxInstalled(context),
                termuxPermission = TermuxTool.hasPermission(context),
                accessibilityEnabled = isAccessibilityServiceEnabled(context),
                accessibilityBound = MinisAccessibilityService.getInstance() != null,
                overlay = Settings.canDrawOverlays(context),
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
                batteryUnrestricted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName),
                shizukuInstalled = isInstalled(context, "moe.shizuku.privileged.api"),
                shizukuBinder = shizukuBinder,
                shizukuGranted = shizukuGranted,
            )
        }

        fun isInstalled(context: Context, pkg: String): Boolean = try {
            context.packageManager.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, MinisAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (s in splitter) {
                val cn = ComponentName.unflattenFromString(s) ?: continue
                if (cn == expected) return true
            }
            return false
        }

        // ── intents ──
        fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun overlaySettings(context: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun notificationSettings(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @android.annotation.SuppressLint("BatteryLife")
        fun batterySettings(context: Context) =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun appDetails(context: Context) =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun launch(context: Context, pkg: String): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(i); true }.getOrDefault(false)
        }
        fun market(context: Context, pkg: String, fallbackUrl: String) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
        fun safeStart(context: Context, intent: Intent) {
            runCatching { context.startActivity(intent) }.onFailure {
                runCatching { context.startActivity(appDetails(context)) }
            }
        }

        const val TERMUX_ALLOW_CMD = "echo 'allow-external-apps=true' >> ~/.termux/termux.properties"
        const val TERMUX_FDROID = "https://f-droid.org/packages/com.termux/"
        const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
    }
}

/** Clawdy-only preferences (safety switches, onboarding, feedback). */
object ClawdyPrefs {
    private const val FILE = "clawdy_prefs"
    const val KEY_ONBOARDED = "onboarded"
    const val KEY_CONFIRM_SENSITIVE = "confirm_sensitive"      // 0 = always ask, 1 = once per chat
    const val KEY_SCREENSHOT_UPLOAD = "screenshot_upload"      // default false
    const val KEY_NO_PASSWORD_FIELDS = "no_password_fields"    // default true
    const val KEY_WEB_SEARCH = "attach_web_search"
    const val KEY_HANDS_FOR_MESSAGE = "attach_hands"
    const val KEY_NICKNAME = "nickname"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun bool(context: Context, key: String, def: Boolean) = prefs(context).getBoolean(key, def)
    fun setBool(context: Context, key: String, v: Boolean) = prefs(context).edit().putBoolean(key, v).apply()
    fun int(context: Context, key: String, def: Int) = prefs(context).getInt(key, def)
    fun setInt(context: Context, key: String, v: Int) = prefs(context).edit().putInt(key, v).apply()
    fun str(context: Context, key: String) = prefs(context).getString(key, null)
    fun setStr(context: Context, key: String, v: String) = prefs(context).edit().putString(key, v).apply()
}
