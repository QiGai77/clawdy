package io.github.nanomuse.cloud

import io.github.nanomuse.net.LanOnly
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The address of a relay someone runs themselves (`cloud/docker-compose.yml`), as the sign-in
 * screen takes it: what counts as an address, when plain http is acceptable, and what the
 * relay's `GET /healthz` says back. Pure functions apart from [check], so the rules are tested
 * without a phone.
 */
object RelayAddress {
    enum class Problem {
        /** Nothing typed. */
        EMPTY,
        /** No host in it, or a scheme other than http(s). */
        NOT_A_URL,
        /** http to a host that is not on a private network: the key would travel in the clear. */
        HTTP_PUBLIC,
    }

    /** What `/healthz` answered: the relay's version and the models it lists. */
    data class Health(val version: String, val models: Int)

    /**
     * The address as the app keeps it: scheme and host (and port, and a path prefix if the
     * relay sits under one), no trailing slash. `https://` is assumed when no scheme is typed.
     * Null when the text has no host.
     */
    fun normalize(input: String): String? {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val shownHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$shownHost$port$path"
    }

    /** Why [input] cannot be used, or null when it can. */
    fun problem(input: String): Problem? {
        if (input.isBlank()) return Problem.EMPTY
        val url = normalize(input) ?: return Problem.NOT_A_URL
        val uri = URI(url)
        if (uri.scheme == "http" && !isPrivateHost(uri.host.orEmpty())) return Problem.HTTP_PUBLIC
        return null
    }

    /**
     * A host that is only reachable from one's own network, where http is the usual way. The
     * rule is [LanOnly.isLocal], the same one the provider screens apply to a model server: the
     * private and carrier-grade ranges as parsed addresses (so `10.foo.example.com` is a public
     * name, not a 10/8 address), loopback, link-local, IPv6 ULA, and the local name suffixes,
     * Tailscale's `.ts.net` among them.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty()) return false
        return LanOnly.isLocal(h)
    }

    /** `cloud.nanomuse.cn`, `relay.example.org:8790`, `192.168.1.20:8790` — the address as the account page shows it. */
    fun display(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return baseUrl
        val host = uri.host ?: return baseUrl
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return host + port
    }

    /** True when [baseUrl] is nanoMuse Cloud itself rather than someone's own relay. */
    fun isDefault(baseUrl: String): Boolean = baseUrl.trimEnd('/') == NanoMuseCloud.DEFAULT_BASE

    /**
     * Asks the relay at [baseUrl] whether it is one: `GET /healthz` → `{"ok": true, "version":
     * "0.20", "models": [...]}`. Blocking; throws [IOException] when nothing answers or the answer
     * is not a relay's.
     */
    @Throws(IOException::class)
    fun check(baseUrl: String, http: OkHttpClient = client): Health {
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/healthz").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: throw IOException("not a relay")
            if (!body.optBoolean("ok", false)) throw IOException("not a relay")
            return Health(body.optString("version").ifBlank { "?" }, body.optJSONArray("models")?.length() ?: 0)
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }
}
