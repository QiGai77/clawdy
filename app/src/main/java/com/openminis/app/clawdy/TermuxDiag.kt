package com.openminis.app.clawdy

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.openminis.app.tools.TermuxTool
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Why Termux is not connected, in words the user can act on (0.3.0).
 *
 * On the user's phone 0.2.0 showed 「在 Termux 中运行命令」 as not granted and 授权 did nothing.
 * The causes this tells apart:
 *  - Termux is the **Google Play build** (versionName `googleplay.YYYY.MM.DD`, installer
 *    com.android.vending): it does not declare `com.termux.permission.RUN_COMMAND` at all, so there
 *    is nothing to grant. Only the F-Droid / GitHub builds work.
 *  - The permission is not defined for another reason (very old Termux).
 *  - Termux was installed **after** Clawdy: Android records the permissions an app asks for when the
 *    app is installed or updated; a permission whose owner did not exist then is dropped. Installing
 *    the same Clawdy APK over itself (an update; data kept) makes Android record it again, after which
 *    授权 shows the dialog. If a ROM still hides it, uninstall + reinstall Clawdy.
 *  - Defined and requestable but not granted: grant it (dialog, or App info → Permissions →
 *    Additional permissions on ROMs that never show the dialog for third-party permissions).
 */
object TermuxDiag {
    const val PKG = "com.termux"
    const val SETUP_ONE_LINER = "curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh | bash"
    /** Termux's GitHub-release signing cert (TermuxConstants.APK_RELEASE_GITHUB_SIGNING_CERTIFICATE_SHA256_DIGEST). */
    private const val GITHUB_CERT = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    /** Termux's F-Droid signing cert (TermuxConstants.APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST). */
    private const val FDROID_CERT = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"

    enum class Verdict { NOT_INSTALLED, PLAY_BUILD, NOT_DEFINED, INSTALLED_AFTER, NOT_GRANTED, READY }

    data class Report(
        val installed: Boolean,
        val versionName: String?,
        val versionCode: Long,
        val installer: String?,
        val build: String,            // "F-Droid", "GitHub", "Google Play", "未知"
        val permissionDefined: Boolean,
        val granted: Boolean,
        val termuxInstalledAfterClawdy: Boolean,
        val verdict: Verdict,
    ) {
        val title: String get() = when (verdict) {
            Verdict.NOT_INSTALLED -> "还没有安装 Termux"
            Verdict.PLAY_BUILD -> "这是 Google Play 版 Termux，不能被其他应用调用"
            Verdict.NOT_DEFINED -> "这个 Termux 不支持「运行命令」接口"
            Verdict.INSTALLED_AFTER -> "Termux 是在 Clawdy 之后安装的"
            Verdict.NOT_GRANTED -> "还没有授予「在 Termux 中运行命令」权限"
            Verdict.READY -> "权限正常"
        }
        val advice: String get() = when (verdict) {
            Verdict.NOT_INSTALLED -> "从 F-Droid 或 GitHub 安装 Termux（不要用 Google Play 版），装好后回到这一页。"
            Verdict.PLAY_BUILD -> "Play 版（版本号 ${versionName ?: "googleplay…"}）没有 RUN_COMMAND 权限，这里永远没法授权。请先在 Termux 里备份需要的文件，卸载它，再从 F-Droid 或 GitHub 安装 Termux，然后覆盖安装一次 Clawdy。"
            Verdict.NOT_DEFINED -> "当前 Termux（${versionName ?: "未知版本"}，来源：$build）没有声明 com.termux.permission.RUN_COMMAND。请更新到 F-Droid / GitHub 的最新版 Termux（0.118 以上）。"
            Verdict.INSTALLED_AFTER -> "Android 只在安装或更新 Clawdy 时登记它要用的权限，那时还没有 Termux。请把同一个 Clawdy APK 再安装一次（直接覆盖安装，聊天和设置都会保留），然后回来点「授权」。如果覆盖安装后仍然没有授权弹窗，就卸载 Clawdy 再重新安装（这会清空 Clawdy 的数据，可以先在 备份与恢复 里导出）。"
            Verdict.NOT_GRANTED -> "点「授权」。如果没有弹窗（MIUI / ColorOS 等常见），点「应用权限页」，在 权限 → 其他权限 里打开「在 Termux 环境中运行命令」。"
            Verdict.READY -> "接着点「测试连接」，确认 allow-external-apps 已开启、Termux 在后台没有被杀。"
        }
    }

    fun read(context: Context): Report {
        val pm = context.packageManager
        val info: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") pm.getPackageInfo(PKG, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        if (info == null) return Report(false, null, 0, null, "未安装", false, false, false, Verdict.NOT_INSTALLED)
        val versionName = info.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(PKG).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(PKG)
        }.getOrNull()
        val cert = runCatching {
            val sig = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()
            else @Suppress("DEPRECATION") info.signatures?.firstOrNull()
            sig?.toByteArray()?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02X".format(it) } }
        }.getOrNull()
        val play = versionName?.startsWith("googleplay") == true || installer == "com.android.vending"
        val build = when {
            play -> "Google Play"
            cert == FDROID_CERT || installer == "org.fdroid.fdroid" -> "F-Droid"
            cert == GITHUB_CERT -> "GitHub"
            else -> "未知" + (installer?.let { "（安装来源 $it）" } ?: "")
        }
        val defined = runCatching { pm.getPermissionInfo(TermuxTool.PERMISSION_RUN_COMMAND, 0); true }.getOrDefault(false)
        val granted = TermuxTool.hasPermission(context)
        val me = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val after = me != null && info.firstInstallTime > me.lastUpdateTime
        val verdict = when {
            granted -> Verdict.READY
            play -> Verdict.PLAY_BUILD
            !defined -> Verdict.NOT_DEFINED
            after -> Verdict.INSTALLED_AFTER
            else -> Verdict.NOT_GRANTED
        }
        return Report(true, versionName, versionCode, installer, build, defined, granted, after, verdict)
    }

    data class TestResult(val ok: Boolean, val message: String)

    /** Sends a real RUN_COMMAND (`echo ok`) and explains the outcome. */
    suspend fun test(context: Context): TestResult {
        val r = read(context)
        if (r.verdict != Verdict.READY) return TestResult(false, r.title + "：" + r.advice)
        val args = JSONObject().put("tool_title", "测试 Termux 连接").put("command", "echo ok; echo \"termux \$TERMUX_VERSION\"").put("timeout", 20)
        val res = TermuxTool.execute(args.toString(), context)
        return when {
            res.success && res.output.contains("ok") ->
                TestResult(true, "连接正常：Termux 执行了 echo ok。" + (Regex("termux (\\S+)").find(res.output)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { "（Termux $it）" } ?: ""))
            res.timedOut ->
                TestResult(false, "20 秒没有回应。最常见的原因：① 还没开 allow-external-apps —— 在 Termux 里运行一键脚本；② Termux 被系统省电策略杀掉了 —— 先打开一次 Termux，并在系统设置里把 Termux 设为「不受电池优化限制」/允许自启动。")
            res.output.contains("Failed to reach") ->
                TestResult(false, "连不上 Termux 的命令服务：先打开一次 Termux 应用再试。（${res.output.lineSequence().firstOrNull()?.take(120)}）")
            else -> TestResult(false, "Termux 回应了，但命令没有成功：${res.output.take(200)}")
        }
    }
}
