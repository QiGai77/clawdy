package com.openminis.app.clawdy.mcp

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.ui.clawdy.ClawdyIcons

/**
 * Built-in connector presets (researched 2026-10-15; packages and endpoints from each project's own
 * README / docs, see HANDOFF §4). None of them was exercised on a phone yet: [status] says so.
 *
 * Secret fields are never written into servers.json: they go to the encrypted App environment
 * variables (设置 → 高级设置 → 环境变量) under [Field.envName] and the config refers to them as
 * `$$NAME`, which [McpManager.expand] resolves at connect time.
 */
object McpPresets {
    enum class Transport(val label: String) { REMOTE("远程"), STDIO("本地 · Termux"), LAN("局域网") }

    data class Field(
        val key: String,
        val label: String,
        val placeholder: String = "",
        val secret: Boolean = false,
        val optional: Boolean = false,
        /** Where the value goes: an env var of the stdio process, or (for remote) the env var behind a `$$` reference. */
        val envName: String = key,
    )

    data class Preset(
        val id: String,
        val name: String,
        val icon: ImageVector,
        val description: String,
        val transport: Transport,
        /** Remote / LAN: the URL; `{KEY}` placeholders are filled from fields (secrets as `$$ENV`). */
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** stdio: command + args; `{KEY}` placeholders are filled from fields. */
        val command: String? = null,
        val args: List<String> = emptyList(),
        /** stdio: env var name → `{KEY}` template. */
        val env: Map<String, String> = emptyMap(),
        val fields: List<Field> = emptyList(),
        val oauth: Boolean = false,
        val needs: List<String> = emptyList(),
        val tip: String = "",
        val status: String = "未测试",
        val source: String = "",
    )

    val ALL: List<Preset> by lazy { listOf(
        Preset(
            "amap", "高德地图", ClawdyIcons.Map, "地理编码、周边搜索、路线规划、天气（高德官方 MCP，12 个工具）",
            Transport.REMOTE, url = "https://mcp.amap.com/mcp?key={AMAP_KEY}",
            fields = listOf(Field("AMAP_KEY", "高德 Web 服务 Key", "在 lbs.amap.com 控制台创建「Web 服务」Key", secret = true, envName = "MCP_AMAP_KEY")),
            needs = listOf("API Key"), tip = "高德开放平台 → 应用管理 → 创建应用 → 添加 Key，服务平台选「Web 服务」。",
            source = "https://lbs.amap.com/api/mcp-server/gettingstarted",
        ),
        Preset(
            "baidu-maps", "百度地图", ClawdyIcons.MapPin, "地点检索、逆地理编码、路线规划、天气、路况（百度地图官方 MCP）",
            Transport.REMOTE, url = "https://mcp.map.baidu.com/mcp?ak={BAIDU_AK}",
            fields = listOf(Field("BAIDU_AK", "百度地图服务端 AK", "lbsyun.baidu.com 控制台创建服务端 AK", secret = true, envName = "MCP_BAIDU_MAPS_AK")),
            needs = listOf("API Key"), tip = "创建 AK 后记得在控制台给它勾选 MCP 服务。",
            source = "https://lbsyun.baidu.com/faq/api?title=mcpserver/quickstart",
        ),
        Preset(
            "github", "GitHub", ClawdyIcons.Code, "仓库、Issue、PR、代码搜索、Actions（GitHub 官方远程 MCP）",
            Transport.REMOTE, url = "https://api.githubcopilot.com/mcp/",
            headers = mapOf("Authorization" to "Bearer {GITHUB_PAT}"),
            fields = listOf(Field("GITHUB_PAT", "Personal Access Token", "github.com/settings/tokens 生成，按需勾选 repo 等权限", secret = true, envName = "MCP_GITHUB_PAT")),
            needs = listOf("Token"), tip = "推荐 fine-grained token，只给需要的仓库。",
            source = "https://github.com/github/github-mcp-server",
        ),
        Preset(
            "lark", "飞书 / Lark", ClawdyIcons.Message, "消息、云文档、多维表格、日历、通讯录（飞书官方 OpenAPI MCP）",
            Transport.STDIO, command = "npx", args = listOf("-y", "@larksuiteoapi/lark-mcp", "mcp"),
            env = mapOf("APP_ID" to "{APP_ID}", "APP_SECRET" to "{APP_SECRET}"),
            fields = listOf(
                Field("APP_ID", "App ID", "cli_xxxxxxxx"),
                Field("APP_SECRET", "App Secret", "", secret = true, envName = "MCP_LARK_APP_SECRET"),
            ),
            needs = listOf("Termux", "飞书自建应用"),
            tip = "在 open.feishu.cn 开发者后台创建企业自建应用，复制 App ID / Secret，并给应用开通需要的 API 权限。默认以应用身份调用；要访问个人文档需在 Termux 里先运行 lark-mcp login。",
            source = "https://github.com/larksuite/lark-openapi-mcp",
        ),
        Preset(
            "12306", "12306 火车票", ClawdyIcons.Train, "查余票、车次、经停站、中转方案（社区项目 12306-mcp）",
            Transport.STDIO, command = "npx", args = listOf("-y", "12306-mcp"),
            needs = listOf("Termux"), tip = "只查询，不能购票。", source = "https://github.com/Joooook/12306-mcp",
        ),
        Preset(
            "bilibili", "哔哩哔哩", ClawdyIcons.Tv, "搜索视频、查看视频与 UP 主信息（社区项目）",
            Transport.STDIO, command = "npx", args = listOf("-y", "@wangshunnn/bilibili-mcp-server"),
            needs = listOf("Termux"), tip = "不需要登录。", source = "https://github.com/wangshunnn/bilibili-mcp-server",
        ),
        Preset(
            "weather-china", "中国天气", ClawdyIcons.Cloud, "实时天气和 5 天预报（社区项目，数据来自中国天气网）",
            Transport.STDIO, command = "npx", args = listOf("-y", "weather-china-mcp"),
            needs = listOf("Termux"), source = "https://github.com/zaixiamaomaoyu/weather-china-mcp",
        ),
        Preset(
            "netease-music", "网易云音乐", ClawdyIcons.Music, "搜索歌曲、歌单、歌词（社区项目 cloudmusic-cli 的 MCP 模式）",
            Transport.STDIO, command = "npx", args = listOf("-y", "@laoshuikaixue/cloudmusic-cli", "mcp"),
            needs = listOf("Termux", "可能需要登录"),
            tip = "在 Termux 里播放音乐大概率不可用；搜索和歌单查询可以试试。需要登录的功能先在 Termux 里运行 npx @laoshuikaixue/cloudmusic-cli 按提示登录。",
            status = "未测试 · 可能部分可用", source = "https://www.npmjs.com/package/@laoshuikaixue/cloudmusic-cli",
        ),
        Preset(
            "xiaohongshu", "小红书", ClawdyIcons.Heart, "搜索笔记、看详情、发布图文（社区项目 xiaohongshu-mcp，需要一台电脑运行）",
            Transport.LAN, url = "http://{HOST}:18060/mcp", headers = mapOf("Authorization" to "Bearer {XHS_TOKEN}"),
            fields = listOf(
                Field("HOST", "电脑的局域网 IP", "例如 192.168.1.20"),
                Field("XHS_TOKEN", "AUTH_TOKEN（可选）", "启动时设置了 AUTH_TOKEN 才需要", secret = true, optional = true, envName = "MCP_XHS_TOKEN"),
            ),
            needs = listOf("电脑", "小红书登录"),
            tip = "它需要 Chrome 浏览器，手机 Termux 跑不了：在电脑上下载 xiaohongshu-mcp 发行版，先运行登录工具扫码，再启动服务（默认 18060 端口），手机和电脑连同一个 Wi-Fi。发布内容前 Clawdy 会先问你。",
            source = "https://github.com/xpzouying/xiaohongshu-mcp",
        ),
        Preset(
            "google-workspace", "Google 全家桶", ClawdyIcons.Mail, "Gmail、日历、云端硬盘、文档、表格（社区项目 workspace-mcp）",
            Transport.STDIO, command = "workspace-mcp", args = listOf("--tool-tier", "core", "--single-user"),
            env = mapOf("GOOGLE_OAUTH_CLIENT_ID" to "{GOOGLE_CLIENT_ID}", "GOOGLE_OAUTH_CLIENT_SECRET" to "{GOOGLE_CLIENT_SECRET}", "USER_GOOGLE_EMAIL" to "{GOOGLE_EMAIL}", "OAUTHLIB_INSECURE_TRANSPORT" to "1"),
            fields = listOf(
                Field("GOOGLE_CLIENT_ID", "OAuth Client ID", "Google Cloud 控制台 → 凭据 → 桌面应用"),
                Field("GOOGLE_CLIENT_SECRET", "OAuth Client Secret", "", secret = true, envName = "MCP_GOOGLE_CLIENT_SECRET"),
                Field("GOOGLE_EMAIL", "你的 Google 邮箱", "you@gmail.com", optional = true),
            ),
            needs = listOf("Termux", "Python", "Google OAuth 客户端"),
            tip = "先在 Termux 运行 pip install workspace-mcp（依赖较多，Termux 上可能需要先 pkg install rust）。第一次调用会给出 Google 登录链接，在浏览器里授权即可。",
            status = "未测试 · 安装较复杂", source = "https://github.com/taylorwilsdon/google_workspace_mcp",
        ),
        Preset(
            "canva", "Canva 可画", ClawdyIcons.Brush, "生成和编辑设计、搜索素材、导出（Canva 官方远程 MCP）",
            Transport.REMOTE, url = "https://mcp.canva.com/mcp", oauth = true,
            needs = listOf("Canva 账号登录"), tip = "保存后点「登录」，在浏览器里授权 Canva。如果 Canva 拒绝注册新客户端，说明它暂时只开放给认证过的应用。",
            source = "https://www.canva.dev/docs/mcp",
        ),
        Preset(
            "canvas-lms", "Canvas LMS", ClawdyIcons.Book, "课程、作业、公告（学校用的 Canvas 学习平台，社区项目 mcp-canvas-lms）",
            Transport.STDIO, command = "npx", args = listOf("-y", "mcp-canvas-lms"),
            env = mapOf("CANVAS_ACCESS_TOKEN" to "{CANVAS_TOKEN}", "CANVAS_BASE_URL" to "{CANVAS_URL}"),
            fields = listOf(
                Field("CANVAS_URL", "学校 Canvas 地址", "https://canvas.xxx.edu"),
                Field("CANVAS_TOKEN", "Access Token", "Canvas → 账户 → 设置 → 新建访问令牌", secret = true, envName = "MCP_CANVAS_TOKEN"),
            ),
            needs = listOf("Termux", "Token"), tip = "如果你说的 Canvas 是设计工具，请用上面的「Canva 可画」。",
            source = "https://github.com/MisterCommand/mcp-canvas-lms",
        ),
        Preset(
            "figma", "Figma", ClawdyIcons.Brush, "读取设计稿、组件和变量（Figma 官方远程 MCP）",
            Transport.REMOTE, url = "https://mcp.figma.com/mcp", oauth = true,
            needs = listOf("Figma 账号登录"),
            tip = "Figma 目前只允许它目录里的客户端（VS Code、Cursor、Claude Code 等）连接，Clawdy 注册客户端会被拒绝（HTTP 403）。等 Figma 开放后可直接点登录。",
            status = "目前不可用（Figma 白名单）", source = "https://developers.figma.com/docs/figma-mcp-server/",
        ),
    ) }

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    private fun fill(template: String, values: Map<String, String>, preset: Preset): String {
        var out = template
        preset.fields.forEach { f ->
            val v = values[f.key].orEmpty()
            out = out.replace("{${f.key}}", if (f.secret && v.isNotEmpty()) "$$${f.envName}" else v)
        }
        return out
    }

    /**
     * Builds the server entry from a preset and the form values. Secret values are saved as App
     * environment variables first; an optional empty header is dropped.
     */
    fun build(context: Context, preset: Preset, name: String, values: Map<String, String>): MCPRepository.MCPServerConfig {
        preset.fields.filter { it.secret }.forEach { f -> values[f.key]?.takeIf { it.isNotBlank() }?.let { saveSecret(context, f.envName, it, "连接器 ${preset.name}") } }
        val headers = preset.headers.mapValues { (_, t) -> fill(t, values, preset) }
            .filterValues { v -> !(v.endsWith("Bearer ") || v.isBlank()) }
        return MCPRepository.MCPServerConfig(
            id = name,
            note = preset.description,
            url = preset.url?.let { fill(it, values, preset) },
            headers = if (preset.transport == Transport.STDIO) emptyMap() else headers,
            command = preset.command,
            args = preset.args.map { fill(it, values, preset) },
            env = preset.env.mapValues { (_, t) -> fill(t, values, preset) }.filterValues { it.isNotBlank() },
            startupTimeoutSeconds = if (preset.transport == Transport.STDIO) 180 else null,
        )
    }

    fun saveSecret(context: Context, key: String, value: String, note: String) {
        val repo = runCatching { (context.applicationContext as MinisApp).envVarRepository }.getOrNull() ?: return
        val existing = repo.entries.value.firstOrNull { it.key == key }
        if (existing != null) repo.update(existing.id, key, value, note) else repo.add(key, value, note)
    }
}
