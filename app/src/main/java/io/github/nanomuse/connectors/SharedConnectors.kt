package io.github.nanomuse.connectors

import android.content.Context
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hub.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Which device of the account connected what — the one part of a connection that travels.
 *
 * The relay's profile (`/v1/me/profile`, see [io.github.nanomuse.cloud.ProfileSync]) carries a
 * `connectors` list: for every entry its catalogue id, a label, the server's address (without
 * any query string, where a key might ride), how it signs in (`oauth` / `key` / `open`), whether
 * it is enabled, when, and which device holds it. **Never a token, a header or a key**: those
 * stay on the device that signed in. The relay keeps every device's entries side by side and
 * replaces only the writer's own, so this phone PUTs what *it* holds and reads back what the
 * others hold; the Connectors page lists theirs as "Connected on <device> — sign in here to use
 * it on this phone", with the connector's normal sign-in as the action.
 */
object SharedConnectors {
    private const val TAG = "SharedConnectors"
    private const val PREFS = "nanomuse_shared_connectors"
    private const val KEY_OTHERS = "others"
    /** The relay caps the list at 64 entries for the whole account; this phone keeps its share modest. */
    private const val MAX_MINE = 32

    /** One connection as another device reports it (or as this phone reports its own). */
    data class Entry(
        val id: String,
        val label: String,
        val url: String,
        /** `oauth`, `key` or `open`. */
        val auth: String,
        /** The device's human label ("Li's MacBook"), and the hub id behind it. */
        val device: String,
        val deviceId: String,
        val enabled: Boolean,
        /** ISO-8601, as the relay keeps it. */
        val at: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _others = MutableStateFlow<List<Entry>?>(null)
    private var watching = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The other devices' connections, as last read from the account; kept across restarts. */
    fun others(context: Context): StateFlow<List<Entry>> {
        if (_others.value == null) {
            _others.value = runCatching { parse(JSONArray(prefs(context).getString(KEY_OTHERS, "[]"))) }.getOrDefault(emptyList())
        }
        @Suppress("UNCHECKED_CAST")
        return _others.asStateFlow() as StateFlow<List<Entry>>
    }

    /** The other devices' entries for one catalogue connector, newest first. */
    fun elsewhere(context: Context, connectorId: String): List<Entry> =
        others(context).value.filter { it.id == connectorId && it.enabled }.sortedByDescending { it.at }

    /**
     * This phone's entries for the profile body — the catalogue connectors and the person's own
     * remote servers (a stdio command is not a service anyone else can sign in to). Only names,
     * addresses and kinds; nothing that opens anything.
     */
    fun mine(context: Context): JSONArray {
        val out = JSONArray()
        val repo = Connectors.repo(context) ?: return out
        val (_, catalogue) = ConnectorsCatalogue.load(context)
        val device = Hub.name(context).take(80)
        val deviceId = Hub.deviceId(context).take(80)
        for (server in repo.servers.value.sortedByDescending { it.createdAt }.take(MAX_MINE)) {
            val url = server.url?.takeIf { it.isNotBlank() } ?: continue
            val connector = catalogue.firstOrNull { it.serverId == server.id }
            val auth = when {
                server.oauth?.isConfigured == true || connector?.auth is ConnectorAuth.OAuth -> "oauth"
                server.headers.isNotEmpty() || connector?.auth is ConnectorAuth.Key || url.contains('?') -> "key"
                else -> "open"
            }
            out.put(
                JSONObject()
                    .put("id", server.id.take(64))
                    .put("label", (connector?.name ?: server.note?.takeIf { it.isNotBlank() } ?: server.id).take(80))
                    .put("url", url.substringBefore('?').take(256))
                    .put("auth", auth)
                    .put("device", device)
                    .put("device_id", deviceId)
                    .put("enabled", server.enabled)
                    .put("at", Instant.ofEpochMilli(server.createdAt).toString()),
            )
        }
        return out
    }

    /** A profile read from the relay: keep what the other devices hold, drop our own echo. */
    fun absorb(context: Context, profile: JSONObject) {
        val arr = profile.optJSONArray("connectors") ?: return
        val ours = Hub.deviceId(context)
        val theirs = parse(arr).filter { it.deviceId.isNotBlank() && it.deviceId != ours }
        _others.value = theirs
        prefs(context).edit().putString(KEY_OTHERS, serialize(theirs).toString()).apply()
    }

    /** Signed out: another account's devices are not ours to list. */
    fun forget(context: Context) {
        _others.value = emptyList()
        prefs(context).edit().clear().apply()
    }

    /**
     * Follow the MCP entries: when what this phone connected changes (an entry added, removed
     * or switched off), the account hears about it a moment later through [onChange] — the
     * same debounce the name and the face use.
     */
    fun watch(context: Context, onChange: () -> Unit) {
        if (watching) return
        val repo = Connectors.repo(context) ?: return
        watching = true
        scope.launch {
            repo.servers
                .map { list -> list.filter { !it.url.isNullOrBlank() }.map { "${it.id}|${it.enabled}|${it.oauth?.isConfigured == true}|${it.headers.isNotEmpty()}" }.sorted() }
                .distinctUntilChanged()
                .drop(1) // the first value is what is already shared
                .collect {
                    AppLogger.info(TAG, "this phone's connections changed")
                    onChange()
                }
        }
    }

    private fun parse(arr: JSONArray): List<Entry> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").trim()
        if (id.isEmpty()) return@mapNotNull null
        Entry(
            id = id,
            label = o.optString("label").ifBlank { id },
            url = o.optString("url"),
            auth = o.optString("auth", "open"),
            device = o.optString("device"),
            deviceId = o.optString("device_id"),
            enabled = o.optBoolean("enabled", true),
            at = o.optString("at"),
        )
    }

    private fun serialize(list: List<Entry>): JSONArray = JSONArray().apply {
        for (e in list) {
            put(
                JSONObject().put("id", e.id).put("label", e.label).put("url", e.url).put("auth", e.auth)
                    .put("device", e.device).put("device_id", e.deviceId).put("enabled", e.enabled).put("at", e.at),
            )
        }
    }
}
