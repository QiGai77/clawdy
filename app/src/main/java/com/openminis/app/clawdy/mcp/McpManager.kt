package com.openminis.app.clawdy.mcp

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.oauth.MCPOAuthStore
import com.openminis.app.tools.TermuxTool
import com.openminis.app.tools.ToolExecutionResult
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.guard.GuardKind
import io.github.nanomuse.guard.RiskAssessment
import io.github.nanomuse.guard.RiskClass
import io.github.nanomuse.guard.RiskGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the user's MCP servers for the agent (0.3.0).
 *
 *  - Remote servers (Streamable HTTP / SSE): connected directly from the app with [McpClient];
 *    headers may reference App environment variables as `$$NAME`; OAuth tokens (from
 *    [McpOAuth] / upstream [MCPOAuthStore]) become `Authorization: Bearer …`.
 *  - stdio servers (`npx …`, `uvx …`, `python …`): launched inside the user's Termux through the
 *    RUN_COMMAND bridge, wrapped by `supergateway` (stdio ↔ Streamable HTTP) listening on
 *    127.0.0.1:<port>, then connected over HTTP like a remote one. The port is derived from the
 *    server name so a running gateway is found again after an app restart. Logs: ~/clawdy/mcp/<name>.log.
 *
 * Agent tools: `mcp_tools` (list a server's tools) and `mcp_call` (call one). Tool names that look
 * like sending, posting, deleting or paying go through the RiskGate approval sheet first.
 */
object McpManager {
    private const val TAG = "McpManager"
    const val TOOLS = "mcp_tools"
    const val CALL = "mcp_call"
    private const val PORT_BASE = 38200
    private const val PORT_SPAN = 700

    private val clients = ConcurrentHashMap<String, McpClient>()
    private val toolCache = ConcurrentHashMap<String, List<McpClient.Tool>>()

    private fun repo(context: Context): MCPRepository? =
        runCatching { (context.applicationContext as MinisApp).mcpRepository }.getOrNull()

    fun server(context: Context, id: String): MCPRepository.MCPServerConfig? =
        repo(context)?.servers?.value?.firstOrNull { it.id == id }

    fun portFor(id: String): Int = PORT_BASE + Math.floorMod(id.hashCode(), PORT_SPAN)

    fun localUrl(id: String) = "http://127.0.0.1:${portFor(id)}/mcp"

    /** `$$NAME` → the App environment variable NAME. */
    fun expand(context: Context, v: String): String {
        if (!v.contains("$$")) return v
        val env = runCatching { (context.applicationContext as MinisApp).envVarRepository?.allAsDict() }.getOrNull().orEmpty()
        return Regex("""\$\$([A-Za-z_][A-Za-z0-9_]*)""").replace(v) { m -> env[m.groupValues[1]] ?: m.value }
    }

    private fun headersFor(context: Context, s: MCPRepository.MCPServerConfig): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        s.headers.forEach { (k, v) -> h[k] = expand(context, v) }
        if (h.keys.none { it.equals("Authorization", true) }) {
            McpOAuth.validToken(context, s)?.let { h["Authorization"] = "Bearer $it" }
        }
        return h
    }

    private suspend fun clientFor(context: Context, s: MCPRepository.MCPServerConfig): McpClient {
        clients[s.id]?.let { return it }
        val url = if (s.isStdio) { ensureStdio(context, s); localUrl(s.id) } else expand(context, s.url.orEmpty())
        if (url.isBlank()) throw McpClient.McpError("这个连接器没有地址")
        val c = McpClient(url, { headersFor(context, s) })
        clients[s.id] = c
        return c
    }

    fun forget(id: String) {
        clients.remove(id)?.let { c -> Thread { runCatching { c.close() } }.start() }
        toolCache.remove(id)
    }

    /** Lists the server's tools, connecting (and for stdio, starting it in Termux) as needed. */
    suspend fun tools(context: Context, id: String, refresh: Boolean = false): List<McpClient.Tool> = withContext(Dispatchers.IO) {
        if (!refresh) toolCache[id]?.let { return@withContext it }
        val s = server(context, id) ?: throw McpClient.McpError("没有名为 $id 的连接器")
        val list = retrying(context, s) { it.listTools() }
        toolCache[id] = list
        list
    }

    suspend fun call(context: Context, id: String, tool: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val s = server(context, id) ?: throw McpClient.McpError("没有名为 $id 的连接器")
        retrying(context, s) { it.callTool(tool, args) }
    }

    private suspend fun <T> retrying(context: Context, s: MCPRepository.MCPServerConfig, block: (McpClient) -> T): T {
        val first = clientFor(context, s)
        return try {
            block(first)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "${s.id}: ${e.message}; reconnecting once")
            forget(s.id)
            if (e is McpClient.McpError && e.httpCode == 401) McpOAuth.refresh(context, s)
            block(clientFor(context, s))
        }
    }

    /** A connection test for the settings page: tool count or the error, in Chinese. */
    suspend fun test(context: Context, id: String): Pair<Boolean, String> = runCatching {
        forget(id)
        val t = tools(context, id, refresh = true)
        true to "连接成功 · ${t.size} 个工具" + (t.take(6).joinToString("、", prefix = "：") { it.name }.takeIf { t.isNotEmpty() } ?: "")
    }.getOrElse { e -> false to "连接失败：${e.message?.take(240)}" }

    // ── stdio through Termux ──────────────────────────────────────────────

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }; true
    }.getOrDefault(false)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun ensureStdio(context: Context, s: MCPRepository.MCPServerConfig) {
        val port = portFor(s.id)
        if (portOpen(port)) return
        if (!TermuxTool.isTermuxInstalled(context)) throw McpClient.McpError("本地（stdio）连接器需要 Termux：先在 工具连接 里装好并授权 Termux")
        if (!TermuxTool.hasPermission(context)) throw McpClient.McpError("Clawdy 还没有「在 Termux 中运行命令」权限，去 工具连接 → Termux 授权")
        val cmd = (listOf(s.command.orEmpty()) + s.args).filter { it.isNotEmpty() }.joinToString(" ") { q(expand(context, it)) }
        val env = s.env.entries.joinToString("") { (k, v) -> "export $k=${q(expand(context, v))}\n" }
        val log = "\$HOME/clawdy/mcp/${s.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.log"
        val script = buildString {
            append("mkdir -p \$HOME/clawdy/mcp\n")
            append(env)
            append("command -v npx >/dev/null || { echo 'npx 不存在：请先在 Termux 运行 clawdy-setup.sh（或 pkg install nodejs-lts）'; exit 3; }\n")
            append("CMD=").append(q(cmd)).append("\n")
            append("setsid nohup npx -y supergateway --stdio \"\$CMD\" --outputTransport streamableHttp --port $port --host 127.0.0.1 > \"$log\" 2>&1 < /dev/null &\n")
            append("echo started $port\n")
        }
        val args = JSONObject().put("tool_title", "启动连接器 ${s.id}").put("command", script).put("workspace", "mcp").put("timeout", 40)
        val r = TermuxTool.execute(args.toString(), context)
        if (!r.success) throw McpClient.McpError("在 Termux 里启动失败：${r.output.take(300)}")
        val deadline = System.currentTimeMillis() + (s.startupTimeoutSeconds ?: 150) * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) { delay(600); return }
            delay(1500)
        }
        throw McpClient.McpError("等了 ${s.startupTimeoutSeconds ?: 150} 秒，连接器没有在 127.0.0.1:$port 上启动。日志：~/clawdy/mcp/${s.id}.log（首次运行 npx 要下载依赖，可以再试一次）")
    }

    /** Stops a stdio server's gateway in Termux. */
    suspend fun stopStdio(context: Context, id: String) {
        forget(id)
        if (!TermuxTool.hasPermission(context)) return
        val args = JSONObject().put("tool_title", "停止连接器 $id")
            .put("command", "pkill -f 'supergateway.*--port ${portFor(id)}' ; echo stopped").put("workspace", "mcp").put("timeout", 20)
        runCatching { TermuxTool.execute(args.toString(), context) }
    }

    // ── agent tools ───────────────────────────────────────────────────────

    fun toolsDefinition() = AgentToolDefinition(
        name = TOOLS,
        description = "List the tools of one of the user's MCP connectors (设置 → 连接器), with their input schemas. Call this before mcp_call when you have not seen that server's tools in this conversation.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, in the user's language."),
            "server" to AgentToolParam("string", "The connector name, exactly as listed under 'MCP connectors' in the system prompt."),
        ),
        required = listOf("tool_title", "server"),
        propertyOrdering = listOf("tool_title", "server"),
    )

    fun callDefinition() = AgentToolDefinition(
        name = CALL,
        description = "Call a tool on one of the user's MCP connectors. Arguments must follow the tool's input schema from mcp_tools. " +
            "Tools that send, post, delete or pay ask the user first.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, in the user's language."),
            "server" to AgentToolParam("string", "The connector name."),
            "tool" to AgentToolParam("string", "The tool name from mcp_tools."),
            "arguments" to AgentToolParam("string", "The tool arguments as a JSON object string, e.g. {\"city\":\"上海\"}. Use {} when there are none."),
        ),
        required = listOf("tool_title", "server", "tool"),
        propertyOrdering = listOf("tool_title", "server", "tool", "arguments"),
    )

    suspend fun executeTools(argsJson: String, context: Context): ToolExecutionResult {
        val a = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = a.optString("tool_title", TOOLS)
        val id = a.optString("server")
        return runCatching {
            val list = tools(context, id)
            val text = buildString {
                append("${list.size} tools on $id:\n")
                list.forEach { t ->
                    append("- ").append(t.name)
                    if (t.description.isNotBlank()) append(": ").append(t.description.replace('\n', ' ').take(300))
                    append("\n  input: ").append(t.inputSchema.toString().take(900)).append('\n')
                }
            }
            ToolExecutionResult(text.take(30_000), true, toolTitle = title)
        }.getOrElse { e -> ToolExecutionResult("MCP $id: ${e.message}", false, toolTitle = title) }
    }

    suspend fun executeCall(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val a = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = a.optString("tool_title", CALL)
        val id = a.optString("server")
        val tool = a.optString("tool")
        val raw = a.opt("arguments")
        val args = when (raw) {
            is JSONObject -> raw
            is String -> if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse {
                return ToolExecutionResult("arguments is not a JSON object: ${it.message}", false, toolTitle = title)
            }
            else -> JSONObject()
        }
        classify(tool)?.let { cls ->
            val gate = RiskGate.check(
                sessionId, GuardKind.BROWSER,
                RiskAssessment(cls, "调用连接器 $id 的 $tool", "mcp:$id"),
                preview = "$id · $tool\n" + args.toString(2).take(1500),
                pageUrl = "mcp:$id", elementText = tool,
            )
            if (gate is GateOutcome.Denied) return ToolExecutionResult(gate.message, false, toolTitle = title)
        }
        return runCatching {
            val r = call(context, id, tool, args)
            val text = McpClient.resultText(r)
            ToolExecutionResult(if (text.isBlank()) "(no content)" else text, !r.optBoolean("isError", false), toolTitle = title)
        }.getOrElse { e -> ToolExecutionResult("MCP $id/$tool failed: ${e.message}", false, toolTitle = title) }
    }

    private val DESTRUCTIVE = Regex("(?i)(delete|remove|destroy|drop|trash|archive|revoke|unsubscribe|删除|移除)")
    private val OUTBOUND = Regex("(?i)(send|post|publish|reply|comment|message|create_?issue|create_?pull|merge|invite|share|upload|tweet|email|pay|order|purchase|transfer|发送|发布|评论|回复|下单|支付)")

    private fun classify(tool: String): RiskClass? = when {
        DESTRUCTIVE.containsMatchIn(tool) -> RiskClass.DESTRUCTIVE
        OUTBOUND.containsMatchIn(tool) -> RiskClass.OUTBOUND
        else -> null
    }

    /** The system-prompt paragraph naming the enabled connectors. */
    fun promptFragment(context: Context, sessionId: String): String? {
        val r = repo(context) ?: return null
        val on = r.servers.value.filter { r.isEnabledForSession(it.id, sessionId) }.sortedByDescending { it.createdAt }.take(20)
        if (on.isEmpty()) return null
        return buildString {
            append("MCP connectors (设置 → 连接器). Use mcp_tools <server> to see a connector's tools, then mcp_call. ")
            append("Local (stdio) connectors start inside Termux on first use, which can take a minute the first time.\n")
            on.forEach { s ->
                append("- ").append(s.id)
                s.note?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(200)) }
                append(if (s.isStdio) " [local via Termux]" else " [remote]")
                append('\n')
            }
        }.trimEnd()
    }
}
