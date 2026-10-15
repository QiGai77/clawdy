package com.openminis.app.clawdy.mcp

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.oauth.MCPOAuthConfig
import com.openminis.app.mcp.oauth.MCPOAuthController
import com.openminis.app.mcp.oauth.MCPOAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * OAuth for remote MCP servers that use the MCP authorization spec (0.3.0): protected-resource
 * metadata (RFC 9728) → authorization-server metadata (RFC 8414) → dynamic client registration
 * (RFC 7591) → the upstream PKCE flow in [MCPOAuthController] (Custom Tab + loopback callback on
 * 127.0.0.1:54546). Tokens live in [MCPOAuthStore] (encrypted); refresh is done here.
 *
 * Servers that only allow pre-approved clients (Figma's remote MCP returns 403 to registration)
 * fail at the registration step with a clear message.
 */
object McpOAuth {
    private const val TAG = "McpOAuth"
    private val http = McpClient.DEFAULT_HTTP

    sealed class Result {
        object Ok : Result()
        data class Failed(val message: String) : Result()
    }

    fun authorized(context: Context, id: String) = MCPOAuthStore.isAuthorized(context, id)

    /** Discovers, registers if needed, and runs the browser sign-in. */
    suspend fun connect(context: Context, s: MCPRepository.MCPServerConfig): Result = withContext(Dispatchers.IO) {
        val url = s.url ?: return@withContext Result.Failed("这个连接器没有地址")
        val cfg = s.oauth?.takeIf { it.isConfigured } ?: runCatching { discoverAndRegister(context, s.id, url) }.getOrElse {
            return@withContext Result.Failed(it.message ?: "OAuth 发现失败")
        }
        if (s.oauth != cfg) runCatching { (context.applicationContext as MinisApp).mcpRepository.update(s.copy(oauth = cfg)) }
        when (val r = MCPOAuthController(context).authorize(s.id, url, cfg)) {
            is MCPOAuthController.Result.Success -> { McpManager.forget(s.id); Result.Ok }
            is MCPOAuthController.Result.Cancelled -> Result.Failed("已取消")
            is MCPOAuthController.Result.Failed -> Result.Failed(r.message)
        }
    }

    fun signOut(context: Context, id: String) { MCPOAuthStore.signOut(context, id); McpManager.forget(id) }

    private fun getJson(u: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(u).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun discoverAndRegister(context: Context, id: String, serverUrl: String): MCPOAuthConfig {
        val u = URI(serverUrl)
        val origin = "${u.scheme}://${u.authority}"
        val path = u.rawPath.orEmpty().trimEnd('/')
        val prm = getJson("$origin/.well-known/oauth-protected-resource$path") ?: getJson("$origin/.well-known/oauth-protected-resource")
        val issuer = prm?.optJSONArray("authorization_servers")?.optString(0)?.ifBlank { null } ?: origin
        val scopes = prm?.optJSONArray("scopes_supported")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optString(it) } }
        val iu = URI(issuer)
        val iOrigin = "${iu.scheme}://${iu.authority}"
        val iPath = iu.rawPath.orEmpty().trimEnd('/')
        val meta = getJson("$iOrigin/.well-known/oauth-authorization-server$iPath")
            ?: getJson("$iOrigin/.well-known/openid-configuration$iPath")
            ?: getJson("${issuer.trimEnd('/')}/.well-known/oauth-authorization-server")
            ?: throw IllegalStateException("找不到授权服务器信息（$issuer）。这个服务可能需要在请求头里填 Token。")
        val authz = meta.optString("authorization_endpoint")
        val token = meta.optString("token_endpoint")
        val reg = meta.optString("registration_endpoint")
        if (authz.isBlank() || token.isBlank()) throw IllegalStateException("授权服务器信息不完整")
        if (reg.isBlank()) throw IllegalStateException("这个服务不支持自动注册客户端，需要你在它的开发者后台创建 OAuth 应用，或改用 Token。")
        val body = JSONObject()
            .put("client_name", "Clawdy")
            .put("redirect_uris", JSONArray().put(MCPOAuthController.DEFAULT_REDIRECT_URI))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        val clientId: String
        http.newCall(Request.Builder().url(reg).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IllegalStateException(
                    if (r.code == 401 || r.code == 403) "服务拒绝了 Clawdy 注册（HTTP ${r.code}）：它只允许白名单里的客户端。"
                    else "注册客户端失败（HTTP ${r.code}）：${text.take(160)}",
                )
            }
            val j = JSONObject(text)
            clientId = j.optString("client_id")
            j.optString("client_secret").takeIf { it.isNotBlank() }?.let { MCPOAuthStore.setClientSecret(context, id, it) }
        }
        if (clientId.isBlank()) throw IllegalStateException("注册成功但没有返回 client_id")
        AppLogger.info(TAG, "registered OAuth client for $id at $reg")
        return MCPOAuthConfig(mode = "dynamic", clientId = clientId, authorizationEndpoint = authz, tokenEndpoint = token, scopes = scopes)
    }

    /** A usable access token, refreshing it when it is about to expire. */
    fun validToken(context: Context, s: MCPRepository.MCPServerConfig): String? {
        val t = MCPOAuthStore.tokens(context, s.id) ?: return null
        if (t.expiresAtMs > 0 && t.expiresAtMs - System.currentTimeMillis() < 60_000 && t.refreshToken != null) {
            refresh(context, s)
            return MCPOAuthStore.tokens(context, s.id)?.accessToken
        }
        return t.accessToken
    }

    fun refresh(context: Context, s: MCPRepository.MCPServerConfig): Boolean {
        val t = MCPOAuthStore.tokens(context, s.id) ?: return false
        val rt = t.refreshToken ?: return false
        val cfg = s.oauth ?: return false
        return runCatching {
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).add("client_id", cfg.clientId)
            MCPOAuthStore.clientSecret(context, s.id)?.let { form.add("client_secret", it) }
            http.newCall(Request.Builder().url(cfg.tokenEndpoint).post(form.build()).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                val j = JSONObject(r.body?.string().orEmpty())
                val access = j.optString("access_token").ifBlank { return false }
                val exp = j.optLong("expires_in", 0L)
                MCPOAuthStore.setTokens(context, s.id, MCPOAuthStore.StoredTokens(
                    accessToken = access,
                    refreshToken = j.optString("refresh_token").ifBlank { rt },
                    expiresAtMs = if (exp > 0) System.currentTimeMillis() + exp * 1000 else 0L,
                ))
                true
            }
        }.getOrDefault(false)
    }
}
