package io.github.nanomuse.community

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.cloud.NanoMuseCloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The relay's say over when the app may ask for a star (shared contract C1). The policy comes
 * from `GET /v1/nudges` (public, cached a day) and rides along in `/v1/me`; the operator edits
 * it from the console without an app update. Without a reachable relay — or with one's own
 * key and no account — the built-in [Policy.DEFAULT] applies, which is byte-for-byte what the
 * relay ships by default.
 *
 * Only the policy lives here. What was asked, when, and whether the person went to GitHub is
 * [StarPrompt]'s ledger.
 */
object Nudges {
    private const val TAG = "Nudges"
    private const val PREFS = "nm.nudges"
    private const val KEY_POLICY = "policy"
    private const val KEY_CHECKED_AT = "checked_at"

    /** The policy is re-read from the relay at most this often. */
    const val FRESH_MS = 24L * 60 * 60 * 1000

    /** Which moments may ask, and at which counts. */
    data class Moments(
        val signedIn: Boolean = true,
        val tasks: List<Int> = listOf(3, 10, 30),
        val newLook: Boolean = true,
        val exhausted: Boolean = true,
        val daysUsed: List<Int> = listOf(7, 30),
        val goalDone: Boolean = true,
    )

    data class Policy(
        val version: Int = 1,
        val enabled: Boolean = true,
        val url: String = StarPrompt.REPO_URL,
        val moments: Moments = Moments(),
        /** At least this many days between two asks of any kind. */
        val cooldownDays: Int = 7,
        /** Lifetime cap of asks on one device ("Not now" counts; a tap on the star ends them all anyway). */
        val maxAsks: Int = 4,
        /** The card's sentence set by the operator, in English; empty = the app's own line for the moment. */
        val text: String = "",
        /** The same in 简体中文; empty = [text], then the app's own line. */
        val textZh: String = "",
    ) {
        /**
         * The operator's sentence for the card's body, for a UI [language] (ISO 639-1, as
         * `Locale.language` gives it): `text_zh` first when the language is Chinese, then
         * `text`; null when neither is set, so the caller draws its own line. Only the body
         * is ever replaced — the title and the buttons stay the app's.
         */
        fun sentence(language: String?): String? {
            if (language?.lowercase()?.startsWith("zh") == true && textZh.isNotEmpty()) return textZh
            return text.takeIf { it.isNotEmpty() }
        }

        fun toJson(): JSONObject = JSONObject()
            .put("version", version)
            .put(
                "star",
                JSONObject()
                    .put("enabled", enabled)
                    .put("url", url)
                    .put("text", text)
                    .put("text_zh", textZh)
                    .put(
                        "moments",
                        JSONObject()
                            .put("signed_in", moments.signedIn)
                            .put("tasks", JSONArray(moments.tasks))
                            .put("new_look", moments.newLook)
                            .put("exhausted", moments.exhausted)
                            .put("days_used", JSONArray(moments.daysUsed))
                            .put("goal_done", moments.goalDone),
                    )
                    .put("cooldown_days", cooldownDays)
                    .put("max_asks", maxAsks),
            )

        companion object {
            /** What every client falls back to; identical to the relay's shipped default. */
            val DEFAULT = Policy()

            /** The longest sentence the card takes (code points); the relay enforces the same. */
            const val TEXT_MAX = 200

            /**
             * Reads a policy document. Missing keys keep the default; a document without a `star`
             * object is not a policy and yields null, so a stray error body never silences the asks.
             */
            fun parse(o: JSONObject): Policy? {
                val star = o.optJSONObject("star") ?: return null
                val m = star.optJSONObject("moments") ?: JSONObject()
                val d = DEFAULT
                return Policy(
                    version = o.optInt("version", d.version),
                    enabled = star.optBoolean("enabled", d.enabled),
                    url = star.optString("url").trim().takeIf { it.startsWith("https://") } ?: d.url,
                    moments = Moments(
                        signedIn = m.optBoolean("signed_in", d.moments.signedIn),
                        tasks = ints(m.optJSONArray("tasks")) ?: d.moments.tasks,
                        newLook = m.optBoolean("new_look", d.moments.newLook),
                        exhausted = m.optBoolean("exhausted", d.moments.exhausted),
                        daysUsed = ints(m.optJSONArray("days_used")) ?: d.moments.daysUsed,
                        goalDone = m.optBoolean("goal_done", d.moments.goalDone),
                    ),
                    cooldownDays = star.optInt("cooldown_days", d.cooldownDays).coerceAtLeast(0),
                    maxAsks = star.optInt("max_asks", d.maxAsks).coerceAtLeast(0),
                    text = textField(star, "text"),
                    textZh = textField(star, "text_zh"),
                )
            }

            /**
             * One of the operator's sentences: trimmed, and empty when the key is absent, not a
             * string, or longer than [TEXT_MAX] code points (a cached policy from before the
             * fields has neither key and reads as empty).
             */
            private fun textField(star: JSONObject, key: String): String {
                val raw = star.opt(key) as? String ?: return ""
                val s = raw.trim()
                return if (s.codePointCount(0, s.length) > TEXT_MAX) "" else s
            }

            fun parse(text: String?): Policy? {
                if (text.isNullOrBlank()) return null
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
                return parse(o)
            }

            /** A JSON array of counts → sorted distinct positive ints; null when the key is absent. */
            private fun ints(arr: JSONArray?): List<Int>? {
                if (arr == null) return null
                return (0 until arr.length()).mapNotNull { i -> arr.optInt(i, -1).takeIf { it > 0 } }.distinct().sorted()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = AtomicBoolean(false)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The policy in force: the last good copy from the relay, else the built-in default. */
    fun current(context: Context): Policy =
        Policy.parse(prefs(context).getString(KEY_POLICY, null)) ?: Policy.DEFAULT

    /** When the relay was last asked (0 = never). */
    fun checkedAt(context: Context): Long = prefs(context).getLong(KEY_CHECKED_AT, 0L)

    /** `/v1/me` carries the policy as its `nudges` field: take it when it is one. */
    fun accept(context: Context, nudges: JSONObject?) {
        val policy = nudges?.let { Policy.parse(it) } ?: return
        store(context, policy)
    }

    private fun store(context: Context, policy: Policy) {
        prefs(context).edit()
            .putString(KEY_POLICY, policy.toJson().toString())
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Asks the relay for the policy when the copy is older than a day. Runs in the background
     * and fails silently: a relay that is down leaves the last copy (or the default) in force.
     * Own-key users without an account fetch from the default relay base too.
     */
    fun refreshIfStale(context: Context) {
        if (System.currentTimeMillis() - checkedAt(context) < FRESH_MS) return
        if (!fetching.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                fetch(app)?.let { store(app, it) }
            } finally {
                fetching.set(false)
            }
        }
    }

    /** One `GET /v1/nudges`; null on any failure. */
    internal fun fetch(context: Context): Policy? {
        val url = NanoMuseCloud.baseUrl(context) + "/v1/nudges"
        return try {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", "nanoMuse-Android/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    AppLogger.info(TAG, "GET /v1/nudges → HTTP ${r.code}")
                    return null
                }
                Policy.parse(r.body?.string())
            }
        } catch (e: Exception) {
            AppLogger.info(TAG, "GET /v1/nudges failed: ${e.javaClass.simpleName}")
            null
        }
    }
}
