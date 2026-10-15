package io.github.nanomuse.oauth

import java.net.URLDecoder

/**
 * Reads the address a browser was left on after a provider's sign-in page redirected it to
 * the app's loopback callback (`http://localhost:1455/auth/callback?code=…&state=…`). A proxy
 * or VPN on the phone can swallow that redirect (#441), so the person copies the address and
 * pastes it; this accepts the whole address, the address without its scheme, or just the
 * query string, and pulls out `code` and `state`. Pure Kotlin, no Android.
 */
object CallbackUrlParser {
    /** What a pasted address carried. [port] and [path] are null when the paste had no host part. */
    data class Callback(
        val code: String,
        val state: String?,
        val port: Int?,
        val path: String?,
    )

    sealed class Result {
        data class Ok(val callback: Callback) : Result()

        /** Nothing that looks like a sign-in code in the text. */
        data object NoCode : Result()
    }

    fun parse(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.NoCode
        val q = trimmed.indexOf('?')
        val (locator, query) = when {
            q >= 0 -> trimmed.substring(0, q) to trimmed.substring(q + 1)
            // A bare query: "code=…&state=…" with no address around it.
            looksLikeQuery(trimmed) -> "" to trimmed
            else -> trimmed to ""
        }
        val params = parseQuery(query.substringBefore('#'))
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Result.NoCode
        val (port, path) = splitLocator(locator)
        return Result.Ok(Callback(code = code, state = params["state"]?.takeIf { it.isNotBlank() }, port = port, path = path))
    }

    private fun looksLikeQuery(s: String): Boolean =
        s.split('&').all { pair -> pair.contains('=') && !pair.substringBefore('=').contains('/') }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = if (eq >= 0) decode(pair.substring(eq + 1)) else ""
            if (key !in out) out[key] = value
        }
        return out
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** "http://localhost:1455/auth/callback" or "localhost:1455/auth/callback" or "/auth/callback" to (port, path). */
    private fun splitLocator(locator: String): Pair<Int?, String?> {
        if (locator.isEmpty()) return null to null
        var rest = locator
        val scheme = rest.indexOf("://")
        if (scheme >= 0) rest = rest.substring(scheme + 3)
        if (rest.startsWith("/")) return null to rest
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else null
        val colon = authority.lastIndexOf(':')
        val port = if (colon >= 0) authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } else null
        return port to path
    }
}
