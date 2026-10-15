package io.github.nanomuse.net

import java.util.Locale

/**
 * Why a provider did not answer, read off the error the model client raised, so the chat can
 * show a card that says what happened and what helps instead of the socket's own words
 * (`failed to connect to chatgpt.com/203.0.113.5 (port 443) from /192.0.2.10 …`).
 *
 * Two sources feed it. Most failures are classified from the text of the error itself
 * ([classify]): OkHttp's connect, DNS, TLS and timeout sentences name the host, so the card can
 * too. The ones whose text upstream flattens — a 401 or 403 becomes "Invalid API key", a 429
 * "Rate limited" — are kept aside by [ReachSignal] on their way through `mapHttpError`, with
 * the status, the body and the host, and the view model turns them into a [canonical] line the
 * classifier reads back. The canonical line is what is persisted, so the card survives a reload.
 *
 * Pure Kotlin: the unit tests run it without Android.
 */
object ProviderReach {
    /** What happened, in the order a person would want to know. */
    enum class Kind {
        /** The host did not answer: DNS, connect, TLS, a timeout, an interception page. */
        UNREACHABLE,

        /** The vendor refuses the region (`unsupported_country_region_territory`). */
        REGION_BLOCKED,

        /** The sign-in's token is no longer valid (a 401 on an OAuth instance). */
        SIGNED_OUT,

        /** The plan has nothing left for now (a 429 that names a usage limit). */
        QUOTA,

        /** Too many requests at once (any other 429). */
        RATE_LIMITED,
    }

    data class Reach(
        val kind: Kind,
        /** The host that did not answer, lower-case; empty when the text named none. */
        val host: String,
        /** What the vendor said, when it said something worth repeating; else the raw line. */
        val detail: String = "",
        /** How long the vendor asked us to wait, when it said (`Retry-After`). */
        val retryAfterS: Int? = null,
    ) {
        /** Whether the host is the ChatGPT plan's (the card names the plan, not an API). */
        val isChatGptPlan: Boolean get() = isPlanHost(host)
    }

    /** The hosts of the ChatGPT plan: the Codex backend and the token endpoint. */
    val PLAN_HOSTS: Set<String> = setOf("chatgpt.com", "auth.openai.com")

    const val REGION_CODE = "unsupported_country_region_territory"

    private const val CANONICAL_PREFIX = "nm_reach:"

    private val QUOTA_MARKERS = listOf(
        "usage_limit_reached", "usage_not_included", "insufficient_quota", "quota_exceeded",
        "plan_limit", "exceeded your current quota", "reached your usage limit", "usage limit",
    )

    // OkHttp / java.net sentences that carry the host
    private val CONNECT_RX = Regex("""failed to connect to ([A-Za-z0-9.\-]+)(?:/[0-9a-fA-F:.]+)? \(port \d+\)""")
    private val RESOLVE_RX = Regex("""(?:resolve host|hostname) ["']?([A-Za-z0-9.\-]+)["']?""", RegexOption.IGNORE_CASE)
    private val HOST_IN_URL_RX = Regex("""https?://([A-Za-z0-9.\-]+)""")
    private val TLS_RX = Regex("""ssl|tls|handshake|certificate|secure connection""", RegexOption.IGNORE_CASE)
    private val TIMEOUT_RX = Regex("""timed? ?out|timeout|ttfb|no response from server""", RegexOption.IGNORE_CASE)
    private val RESET_RX = Regex("""connection reset|connection refused|unreachable|network is unreachable|software caused connection abort|unexpected end of stream|stream was reset|broken pipe|eof""", RegexOption.IGNORE_CASE)
    private val OFFLINE_RX = Regex("""internet connection appears to be offline|not connected to the internet|could not connect to the server|network connection was lost|server with the specified hostname could not be found""", RegexOption.IGNORE_CASE)

    fun isPlanHost(host: String): Boolean = host.lowercase(Locale.ROOT).let { h -> PLAN_HOSTS.any { h == it || h.endsWith(".$it") } }

    /**
     * The failure behind [text], or null when it is not one this card is for. [hintHost] is the
     * host of the provider the turn ran on, for the sentences that name none (iOS-style
     * "Could not connect to the server", a timeout); it is never used to turn an ordinary
     * provider error into a reach card.
     */
    fun classify(text: String, hintHost: String? = null): Reach? {
        val t = text.trim()
        if (t.isEmpty()) return null
        parseCanonical(t)?.let { return it }
        val low = t.lowercase(Locale.ROOT)
        val hint = hintHost?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }.orEmpty()

        // the vendor's own codes first — they are not network failures
        if (REGION_CODE in low) return Reach(Kind.REGION_BLOCKED, hostIn(t) ?: hint, vendorMessage(t))
        if (QUOTA_MARKERS.any { it in low }) return Reach(Kind.QUOTA, hostIn(t) ?: hint, vendorMessage(t))

        CONNECT_RX.find(t)?.let { return Reach(Kind.UNREACHABLE, it.groupValues[1].lowercase(Locale.ROOT), t) }
        RESOLVE_RX.find(t)?.let { return Reach(Kind.UNREACHABLE, it.groupValues[1].lowercase(Locale.ROOT), t) }
        val looksNetwork = low.startsWith("network error") || RESET_RX.containsMatchIn(low) ||
            OFFLINE_RX.containsMatchIn(low) || TIMEOUT_RX.containsMatchIn(low) || TLS_RX.containsMatchIn(low)
        if (looksNetwork) {
            // a provider's 5xx carries a status; a transport failure never does
            if (Regex("""\b(500|502|503|504|529)\b""").containsMatchIn(low) && !TIMEOUT_RX.containsMatchIn(low)) return null
            return Reach(Kind.UNREACHABLE, hostIn(t) ?: hint, t)
        }
        return null
    }

    /**
     * The side channel's record as a reach, when the status and the body say it is one; the
     * ordinary "invalid key on an API-key provider" stays null and keeps upstream's text.
     */
    fun fromHttp(status: Int, body: String?, host: String, oauth: Boolean, retryAfterS: Int? = null): Reach? {
        val b = body.orEmpty()
        val low = b.lowercase(Locale.ROOT)
        val h = host.lowercase(Locale.ROOT)
        return when {
            status == 403 && REGION_CODE in low -> Reach(Kind.REGION_BLOCKED, h, vendorMessage(b))
            status in setOf(403, 404, 503) && looksLikeHtml(b) -> Reach(Kind.UNREACHABLE, h, "")
            status == 401 && oauth -> Reach(Kind.SIGNED_OUT, h, "")
            status == 429 && QUOTA_MARKERS.any { it in low } -> Reach(Kind.QUOTA, h, vendorMessage(b), retryAfterS)
            status == 429 && isPlanHost(h) -> Reach(Kind.RATE_LIMITED, h, "", retryAfterS)
            else -> null
        }
    }

    /** `nm_reach:<kind>:<host>:<retry_after>|<detail>` — what the view model stores for a side-channel reach. */
    fun canonical(reach: Reach): String =
        CANONICAL_PREFIX + reach.kind.name.lowercase(Locale.ROOT) + ":" + reach.host + ":" + (reach.retryAfterS ?: "") + "|" + reach.detail.replace('\n', ' ')

    private fun parseCanonical(t: String): Reach? {
        if (!t.startsWith(CANONICAL_PREFIX)) return null
        val rest = t.removePrefix(CANONICAL_PREFIX)
        val head = rest.substringBefore('|')
        val detail = rest.substringAfter('|', "")
        // kind, host, retry: the host may carry colons itself (an IPv6 literal), so it is what
        // sits between the first colon and the last, and the retry is what follows the last.
        val kindName = head.substringBefore(':', "")
        if (kindName.isEmpty() || !head.contains(':')) return null
        val kind = runCatching { Kind.valueOf(kindName.uppercase(Locale.ROOT)) }.getOrNull() ?: return null
        val hostAndRetry = head.substringAfter(':')
        val host = if (hostAndRetry.contains(':')) hostAndRetry.substringBeforeLast(':') else hostAndRetry
        val retry = if (hostAndRetry.contains(':')) hostAndRetry.substringAfterLast(':').toIntOrNull() else null
        return Reach(kind, host, detail, retry)
    }

    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(64).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun hostIn(t: String): String? =
        CONNECT_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: RESOLVE_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: HOST_IN_URL_RX.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** The `error.message` of an OpenAI-shaped body inside [t], or empty. */
    private fun vendorMessage(t: String): String {
        val start = t.indexOf('{')
        if (start >= 0) {
            runCatching {
                val json = org.json.JSONObject(t.substring(start))
                val err = json.optJSONObject("error") ?: json
                val m = err.optString("message")
                if (m.isNotBlank()) return m.take(300)
            }
        }
        return ""
    }
}

/**
 * The HTTP failures upstream flattens, kept for the chat for a few seconds: the status, the
 * body and the host, noted in `mapHttpError` before "Invalid API key" / "Rate limited" is all
 * that is left. [takeFresh] is consumed by the view model when the error is about to be shown.
 */
object ReachSignal {
    private class Note(val reach: ProviderReach.Reach, val at: Long)

    @Volatile private var last: Note? = null

    /** Call for every non-2xx answer of a model request; only the ones the card is for are kept. */
    fun noteHttpError(status: Int, body: String?, host: String, oauth: Boolean, retryAfter: String? = null) {
        val reach = ProviderReach.fromHttp(status, body, host, oauth, retryAfter?.trim()?.toDoubleOrNull()?.toInt()) ?: return
        last = Note(reach, System.currentTimeMillis())
    }

    /** The reach behind the error the chat is about to show, if there is a fresh one; consumed. */
    fun takeFresh(maxAgeMs: Long = 30_000): ProviderReach.Reach? {
        val n = last ?: return null
        last = null
        return n.reach.takeIf { System.currentTimeMillis() - n.at <= maxAgeMs }
    }

    /** Tests only. */
    fun clear() { last = null }
}
