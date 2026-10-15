package com.openminis.app.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clawdy: run a command inside the user's own Termux install through Termux's
 * official RUN_COMMAND intent (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
 *
 * Termux is the "heavy" Linux: python-docx / python-pptx, node, chromium +
 * playwright-core, git, pandoc… while the built-in Alpine/PRoot sandbox stays for
 * quick, isolated jobs. Every command runs under bash -l in a fixed workspace root
 * (~/clawdy/<workspace>), in the background (no Termux window), and its stdout,
 * stderr and exit code come back through a PendingIntent.
 *
 * One-time setup on the phone (shown to the user when a call fails):
 *  1. Install Termux from F-Droid or GitHub (the Play Store build is outdated).
 *  2. In Termux:  mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *     then `termux-reload-settings`.
 *  3. Grant Clawdy the "Run commands in Termux environment" permission
 *     (Settings → Apps → Clawdy → Permissions → Additional permissions).
 */
object TermuxTool {
    const val NAME = "termux_run"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val MAX_OUTPUT_CHARS = 30_000

    private val requestCounter = AtomicInteger(1)

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run a bash command in the user's Termux environment on this phone (full Termux userland: pkg, python, node, git, pandoc, chromium, etc.). " +
            "Use it for all shell work: editing/creating DOCX (python-docx), PPTX (python-pptx), PDF, rendering web pages, " +
            "headless browser / Playwright tests (playwright-core with Termux chromium: executablePath '/data/data/com.termux/files/usr/bin/chromium-browser'), builds and git. " +
            "Commands run with `bash -l -c` inside ~/clawdy/<workspace> (created if missing) in the background; stdout, stderr and the exit code are returned (output truncated to 30k chars). " +
            "Termux files live under /data/data/com.termux/files/home — NOT the Alpine sandbox paths; to hand a result to the user, copy it to ~/storage/shared/Clawdy/ (needs termux-setup-storage once). " +
            "If the call reports that Termux is missing or not authorised, tell the user the one-time setup steps from the error and stop. " +
            "Never use it to read passwords, keys or other apps' private data.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "command" to AgentToolParam("string", "The bash script to run. Multi-line is fine. Keep under ~4000 chars; write longer scripts to a file first."),
            "workspace" to AgentToolParam("string", "Workspace folder name under ~/clawdy (letters, digits, - and _ only). Default: 'default'."),
            "timeout" to AgentToolParam("integer", "Seconds to wait for the result (default 600, max 3600). The command keeps running in Termux after a timeout."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "workspace", "timeout"),
    )

    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    private val SETUP_HINT = """
        一次性设置 / One-time setup:
        1) 从 F-Droid 或 GitHub 安装 Termux（不要用 Play 商店旧版）。
        2) 在 Termux 里执行：
           mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
        3) 系统设置 → 应用 → Clawdy → 权限 → 其他权限 → 允许「在 Termux 环境中运行命令」。
        4) 推荐：在 Termux 里运行 Clawdy 自带的 bootstrap 脚本安装 python/node/chromium 等依赖。
    """.trimIndent()

    suspend fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val toolTitle = args.optString("tool_title", NAME)
        val command = args.optString("command", "")
        if (command.isBlank()) return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
        val workspace = args.optString("workspace", "default").ifBlank { "default" }
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(workspace)) {
            return ToolExecutionResult("Error: invalid workspace name '$workspace'", false, toolTitle = toolTitle)
        }
        val timeoutSec = args.optInt("timeout", 600).coerceIn(5, 3600)

        if (!isTermuxInstalled(context)) {
            return ToolExecutionResult("Termux is not installed.\n\n$SETUP_HINT", false, toolTitle = toolTitle)
        }
        if (!hasPermission(context)) {
            return ToolExecutionResult("Clawdy is not allowed to run commands in Termux yet.\n\n$SETUP_HINT", false, toolTitle = toolTitle)
        }

        val appCtx = context.applicationContext
        val requestId = requestCounter.getAndIncrement()
        val action = "${appCtx.packageName}.TERMUX_RESULT.$requestId.${System.nanoTime()}"
        val deferred = CompletableDeferred<android.os.Bundle?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                deferred.complete(intent?.getBundleExtra("result"))
            }
        }
        ContextCompat.registerReceiver(appCtx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            val resultIntent = Intent(action).setPackage(appCtx.packageName)
            var piFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags = piFlags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(appCtx, requestId, resultIntent, piFlags)

            val wsDir = "$TERMUX_HOME/clawdy/$workspace"
            val script = "mkdir -p '$wsDir' && cd '$wsDir' || exit 97\n$command"
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_PATH, TERMUX_BASH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-l", "-c", script))
                putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_COMMAND_LABEL, "Clawdy: $toolTitle")
                putExtra(EXTRA_PENDING_INTENT, pending)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appCtx.startForegroundService(intent)
                else appCtx.startService(intent)
            } catch (e: Exception) {
                return ToolExecutionResult(
                    "Failed to reach Termux RunCommandService: ${e.message}\n\n$SETUP_HINT", false, toolTitle = toolTitle,
                )
            }

            val bundle = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
                ?: return ToolExecutionResult(
                    "No result from Termux within ${timeoutSec}s (the command may still be running in Termux). " +
                        "If nothing ever returns, check allow-external-apps=true in ~/.termux/termux.properties.",
                    false, toolTitle = toolTitle, timedOut = true,
                )

            val stdout = bundle.getString("stdout").orEmpty()
            val stderr = bundle.getString("stderr").orEmpty()
            val exitCode = bundle.getInt("exitCode", -1)
            val err = bundle.getInt("err", -1)
            val errmsg = bundle.getString("errmsg").orEmpty()

            val sb = StringBuilder()
            if (stdout.isNotEmpty()) sb.append(stdout.trimEnd()).append('\n')
            if (stderr.isNotEmpty()) sb.append("[stderr]\n").append(stderr.trimEnd()).append('\n')
            if (errmsg.isNotEmpty() && err != -1) sb.append("[termux error $err] ").append(errmsg).append('\n')
            sb.append("(exit code: $exitCode, cwd: ~/clawdy/$workspace)")
            var out = sb.toString()
            if (out.length > MAX_OUTPUT_CHARS) {
                out = out.take(MAX_OUTPUT_CHARS / 2) + "\n…[truncated]…\n" + out.takeLast(MAX_OUTPUT_CHARS / 2)
            }
            // err == -1 is Termux's RESULT_CODE_OK
            val ok = exitCode == 0 && (err == -1 || err == 0)
            return ToolExecutionResult(out, ok, toolTitle = toolTitle)
        } finally {
            runCatching { appCtx.unregisterReceiver(receiver) }
        }
    }
}
