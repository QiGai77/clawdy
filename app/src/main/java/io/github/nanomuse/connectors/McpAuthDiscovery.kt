package io.github.nanomuse.connectors

import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.oauth.MCPOAuthConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The MCP authorization flow's first half, as the desktop host does it (`connectors.ts`): ask
 * the server what it wants with a bare `initialize`; on a 401, follow `WWW-Authenticate` to the
 * protected-resource metadata, from there to the authorization server's metadata (RFC 8414 /
 * OpenID), and register nanoMuse as a client there (RFC 7591) — so a person connects without
 * ever seeing a client id. The result is the same static [MCPOAuthConfig] the upstream PKCE
 * flow ([com.openminis.app.mcp.oauth.MCPOAuthController]) runs with.
 */
object McpAuthDiscovery {
    private const val TAG = "McpAuthDiscovery"
    private const val CLIENT_NAME = "nanoMuse"
    private const val CLIENT_URI = "https://nanomuse.cn"
    private const val PROTOCOL = "2025-06-18"
    private const val TIMEOUT_S = 15L

    sealed class Probe {
        /** `initialize` answered: no credential needed. */
        object Open : Probe()

        /** A 401 without a usable authorization server: the server wants a key. */
        data class Key(val hint: String) : Probe()

        /** The MCP authorization flow, with everything the PKCE step needs. */
        data class OAuth(val config: MCPOAuthConfig, val clientSecret: String?, val resource: String) : Probe()

        /** OAuth, but the authorization server registers no clients: a client id of the person's own is needed. */
        object NeedsClient : Probe()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val initialize: String = JSONObject()
        .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
        .put(
            "params",
            JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", "0.1")),
        ).toString()

    /**
     * What [url] wants, registering nanoMuse at its authorization server when it is OAuth.
     * [redirectUri] is the loopback the PKCE step listens on. Blocking; call off the main thread.
     * Throws with a readable message when the server misbehaves.
     */
    fun probe(url: String, redirectUri: String, clientId: String? = null, clientSecret: String? = null): Probe {
        val req = Request.Builder().url(url)
            .post(initialize.toRequestBody("application/json".toMediaType()))
            .header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", PROTOCOL)
            .build()
        val (status, challengeHeader) = http.newCall(req).execute().use { resp ->
            resp.code to (resp.header("WWW-Authenticate") ?: "")
        }
        if (status in 200..299) return Probe.Open
        if (status != 401) throw IllegalStateException("The server answered $status to initialize")
        val challenge = parseChallenge(challengeHeader)
        val target = URI(url)
        val origin = "${target.scheme}://${target.authority}"
        val path = target.path?.takeIf { it != "/" }?.trimEnd('/') ?: ""
        val candidates = listOfNotNull(
            challenge["resource_metadata"],
            challenge["resource_metadata_uri"],
            "$origin/.well-known/oauth-protected-resource$path",
            "$origin/.well-known/oauth-protected-resource",
        )
        var prm: JSONObject? = null
        for (candidate in candidates) {
            val json = getJson(candidate) ?: continue
            if ((json.optJSONArray("authorization_servers")?.length() ?: 0) > 0) { prm = json; break }
        }
        val asBase = prm?.optJSONArray("authorization_servers")?.optString(0)?.takeIf { it.isNotBlank() } ?: origin
        val meta = authorizationServer(asBase)
            ?: return Probe.Key(challenge["error_description"] ?: "")
        val scope = challenge["scope"]
            ?: prm?.optJSONArray("scopes_supported")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.getString(it) } }?.takeIf { it.isNotBlank() }
        val reg = if (!clientId.isNullOrBlank()) {
            // an OAuth app of the person's own (services without dynamic client registration)
            clientId.trim() to clientSecret?.trim()?.ifBlank { null }
        } else {
            val registration = meta.optString("registration_endpoint", "").takeIf { it.isNotBlank() }
                ?: return Probe.NeedsClient
            register(registration, redirectUri, scope)
        }
        val config = MCPOAuthConfig(
            mode = "static",
            clientId = reg.first,
            authorizationEndpoint = meta.getString("authorization_endpoint"),
            tokenEndpoint = meta.getString("token_endpoint"),
            scopes = scope,
            redirectUri = redirectUri,
        )
        return Probe.OAuth(config, reg.second, prm?.optString("resource", "")?.takeIf { it.isNotBlank() } ?: url)
    }

    /** The authorization server's metadata by the well-known paths RFC 8414 and OpenID give it. */
    private fun authorizationServer(base: String): JSONObject? {
        val u = runCatching { URI(base) }.getOrNull() ?: return null
        val origin = "${u.scheme}://${u.authority}"
        val path = u.path?.takeIf { it != "/" }?.trimEnd('/') ?: ""
        val candidates = buildList {
            add("$origin/.well-known/oauth-authorization-server$path")
            add("$origin/.well-known/openid-configuration$path")
            if (path.isNotEmpty()) {
                add("$origin$path/.well-known/openid-configuration")
                add("$origin$path/.well-known/oauth-authorization-server")
            }
        }
        for (candidate in candidates) {
            val meta = getJson(candidate) ?: continue
            if (meta.optString("authorization_endpoint").isNotBlank() && meta.optString("token_endpoint").isNotBlank()) return meta
        }
        return null
    }

    /** RFC 7591: a public client first, whatever the server will take otherwise → (client id, client secret). */
    private fun register(endpoint: String, redirectUri: String, scope: String?): Pair<String, String?> {
        val body = JSONObject()
            .put("client_name", CLIENT_NAME)
            .put("client_uri", CLIENT_URI)
            .put("redirect_uris", JSONArray().put(redirectUri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        scope?.let { body.put("scope", it) }
        var (code, text) = postJson(endpoint, body)
        if (code !in 200..299) {
            // some servers insist on a confidential client
            body.remove("token_endpoint_auth_method")
            val again = postJson(endpoint, body)
            code = again.first
            text = again.second
        }
        if (code !in 200..299) throw IllegalStateException("The authorization server refused to register nanoMuse ($code): ${text.take(200)}")
        val reg = JSONObject(text)
        val clientId = reg.optString("client_id", "").ifBlank { throw IllegalStateException("The authorization server returned no client id") }
        AppLogger.info(TAG, "registered at $endpoint (secret=${reg.has("client_secret")})")
        return clientId to reg.optString("client_secret", "").ifBlank { null }
    }

    private fun postJson(url: String, body: JSONObject): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("accept", "application/json")
            .build()
        return http.newCall(req).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val req = Request.Builder().url(url).header("accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty())
        }
    }.getOrNull()

    /** `Bearer realm="x", resource_metadata="…", scope="a b"` → its parameters, keys lower-cased. */
    internal fun parseChallenge(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("([A-Za-z_]+)=\"([^\"]*)\"").findAll(header).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return out
    }
}
