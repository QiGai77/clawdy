package io.github.nanomuse.oauth

import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * The loopback half of a provider's OAuth sign-in. The browser is meant to call
 * `http://localhost:<port><path>?code=…` on the app's [com.openminis.app.auth.OAuthCallbackServer];
 * when a proxy or VPN on the phone routes `localhost` into its tunnel the call never arrives
 * (#441). This knows each provider's port and path, tells whether the system proxy would take
 * the loopback address, and hands a pasted callback to the waiting server without a proxy.
 */
object LoopbackCallback {
    private const val TAG = "LoopbackCallback"

    /** Where a provider's callback server listens. */
    data class Target(val port: Int, val path: String)

    /**
     * The ports and paths the managers under `com.openminis.app.auth` register with the
     * providers (each is fixed by the provider's client id, so none can move). Kimi uses a
     * device code and has no loopback. OpenRouter falls back to 3001 and 3002 when 3000 is
     * taken; the pasted address carries the port that was bound.
     */
    fun target(type: ProviderType): Target? = when (type) {
        ProviderType.openAI -> Target(1455, "/auth/callback")
        ProviderType.anthropic -> Target(54545, "/callback")
        ProviderType.gemini -> Target(8085, "/oauth2callback")
        ProviderType.antigravity -> Target(8086, "/oauth2callback")
        ProviderType.xAI -> Target(56121, "/callback")
        ProviderType.openRouter -> Target(3000, "/callback")
        else -> null
    }

    /** True when the system proxy settings would send `http://localhost:<port>/` through a proxy. */
    fun proxied(port: Int): Boolean = runCatching {
        ProxySelector.getDefault()?.select(URI("http://localhost:$port/"))
            ?.any { it.type() != Proxy.Type.DIRECT } ?: false
    }.getOrDefault(false)

    /** The request the server would have had from the browser, rebuilt from the paste. */
    fun forwardUrl(target: Target, callback: CallbackUrlParser.Callback): String {
        val port = callback.port ?: target.port
        val path = callback.path?.takeIf { it.startsWith("/") } ?: target.path
        val query = buildString {
            append("code=").append(URLEncoder.encode(callback.code, "UTF-8"))
            callback.state?.let { append("&state=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        return "http://127.0.0.1:$port$path?$query"
    }

    /**
     * Calls the waiting callback server with the pasted code the way the browser would have,
     * off the main thread and with [Proxy.NO_PROXY] so the request cannot reach the system
     * proxy. True when the server answered 2xx; the sign-in then goes on by itself.
     */
    suspend fun deliver(target: Target, callback: CallbackUrlParser.Callback): Boolean = withContext(Dispatchers.IO) {
        val url = forwardUrl(target, callback)
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            val ok = conn.responseCode in 200..299
            AppLogger.info(TAG, "pasted callback forwarded to port ${callback.port ?: target.port}: ${conn.responseCode}")
            ok
        } catch (e: IOException) {
            AppLogger.warning(TAG, "pasted callback not delivered: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
