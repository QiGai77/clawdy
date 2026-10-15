package io.github.nanomuse.community

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The installed version and the latest one, side by side (shared contract C2). The latest
 * comes from the download index on nanomuse.cn first, then GitHub's latest release; it is
 * looked up at most once a day and the answer kept, with a "check now" for a stale or failed
 * check. Only the two lines of the Version row live on this; the in-app APK download on the
 * About page is the upstream UpdateChecker, untouched.
 */
object UpdateCheck {
    private const val TAG = "UpdateCheck"
    private const val PREFS = "nm.update"
    private const val KEY_LATEST = "latest"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_FAILED = "failed"

    /** Where "Update" goes on Android: the download page, with the APK and the store links. */
    const val DOWNLOAD_URL = "https://nanomuse.cn/dl/"
    const val INDEX_URL = "https://nanomuse.cn/dl/index.json"
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/nano-muse/nanoMuse/releases/latest"

    /** A check is repeated at most this often on its own. */
    const val FRESH_MS = 24L * 60 * 60 * 1000

    data class State(
        /** The newest release known, without the `v` (null = never found out). */
        val latest: String? = null,
        /** When the last check ran, epoch ms (0 = never). */
        val checkedAt: Long = 0L,
        /** The last check could not reach either source. */
        val failed: Boolean = false,
        val checking: Boolean = false,
    ) {
        /** The latest is newer than this build. */
        val newer: Boolean get() = latest != null && isNewer(latest, BuildConfig.VERSION_NAME)
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The state as the screens follow it; [load] fills it from the prefs first. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = AtomicBoolean(false)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "0.1.35 (36)" — the build the person has. */
    fun installed(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The kept state, read from prefs the first time. */
    fun load(context: Context): State {
        _state.value?.let { return it }
        val p = prefs(context)
        val s = State(latest = p.getString(KEY_LATEST, null), checkedAt = p.getLong(KEY_CHECKED_AT, 0L), failed = p.getBoolean(KEY_FAILED, false))
        _state.value = s
        return s
    }

    /** A check when the last one is older than a day, or did not get through and is older than an hour. */
    fun refreshIfStale(context: Context) {
        val s = load(context)
        val age = System.currentTimeMillis() - s.checkedAt
        if (age < if (s.failed) RETRY_MS else FRESH_MS) return
        checkNow(context)
    }

    private const val RETRY_MS = 60L * 60 * 1000

    /** A check right now (the row's tap). */
    fun checkNow(context: Context) {
        if (!checking.compareAndSet(false, true)) return
        val app = context.applicationContext
        _state.value = load(app).copy(checking = true)
        scope.launch {
            try {
                val latest = fetchLatest()
                val now = System.currentTimeMillis()
                val p = prefs(app)
                val kept = p.getString(KEY_LATEST, null)
                p.edit()
                    .putString(KEY_LATEST, latest ?: kept)
                    .putLong(KEY_CHECKED_AT, now)
                    .putBoolean(KEY_FAILED, latest == null)
                    .apply()
                _state.value = State(latest = latest ?: kept, checkedAt = now, failed = latest == null)
            } finally {
                checking.set(false)
            }
        }
    }

    /** The index on nanomuse.cn, then GitHub; null when neither answered with a version. */
    internal fun fetchLatest(): String? {
        get(INDEX_URL)?.let { parseIndex(it) }?.let { return it }
        return get(GITHUB_LATEST_URL)?.let { parseGitHubLatest(it) }
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "nanoMuse-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) {
                r.body?.string()
            } else {
                AppLogger.info(TAG, "GET $url → HTTP ${r.code}")
                null
            }
        }
    } catch (e: Exception) {
        AppLogger.info(TAG, "GET $url failed: ${e.javaClass.simpleName}")
        null
    }

    /** `{"releases":[{"tag":"v0.1.34",…},…]}`, newest first → "0.1.34". */
    fun parseIndex(text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val releases = o.optJSONArray("releases") ?: return null
        for (i in 0 until releases.length()) {
            val tag = releases.optJSONObject(i)?.optString("tag")?.let(::normalize) ?: continue
            if (tag.isNotEmpty()) return tag
        }
        return null
    }

    /** GitHub's `/releases/latest` → its `tag_name`, normalised. */
    fun parseGitHubLatest(text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return normalize(o.optString("tag_name")).takeIf { it.isNotEmpty() }
    }

    /** "v0.1.35" → "0.1.35"; a stray space or newline goes. */
    fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Semver-ish: numeric parts compared as numbers, a missing part is 0, and a pre-release
     * label ("0.1.35-rc1") sorts below the plain version it precedes.
     */
    fun compare(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String> {
            val core = v.substringBefore('-')
            val label = v.substringAfter('-', "")
            return core.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 } to label
        }
        val (an, al) = split(normalize(a))
        val (bn, bl) = split(normalize(b))
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrNull(i) ?: 0).compareTo(bn.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return when {
            al == bl -> 0
            al.isEmpty() -> 1
            bl.isEmpty() -> -1
            else -> al.compareTo(bl)
        }
    }

    fun isNewer(latest: String, installed: String): Boolean = compare(latest, installed) > 0
}
