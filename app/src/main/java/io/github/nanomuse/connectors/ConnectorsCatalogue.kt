package io.github.nanomuse.connectors

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * The services Settings → Connectors offers — the same list the desktop keeps in
 * `harness/dsh-nanomuse/src/connectors-catalogue.ts`, written to `assets/nanomuse/connectors.json`
 * by `scripts/connectors-json.mjs`. Each entry is a remote MCP server and how it lets a client in:
 * the MCP authorization flow (discovery, dynamic client registration, PKCE), a key the person
 * pastes, nothing at all, or "probe first".
 */
data class Connector(
    val id: String,
    val name: String,
    val category: String,
    /** The Streamable HTTP endpoint. */
    val url: String,
    val auth: ConnectorAuth,
    /** The vendor's page about the server. */
    val docs: String,
    private val aboutEn: String,
    private val aboutZh: String,
    /** Brand colour for the letter mark, `#rrggbb`. */
    val color: String,
) {
    /** One line about the service, in the phone's language. */
    fun about(locale: Locale = Locale.getDefault()): String =
        if (locale.language == "zh" && aboutZh.isNotBlank()) aboutZh else aboutEn

    /** The MCP server id this connector adds — the catalogue id, so the two stay paired. */
    val serverId: String get() = id
}

sealed class ConnectorAuth {
    /**
     * Discovery → dynamic client registration → PKCE: nothing to type. Some services do not
     * register clients by themselves ([clientIdRequired]): the person creates an OAuth app in the
     * vendor's developer settings ([developer]) with our redirect URI and pastes its client id.
     */
    data class OAuth(val clientIdRequired: Boolean = false, val developer: String? = null) : ConnectorAuth()

    /** A key the person pastes; sent as a header (with an optional prefix) or a query parameter. */
    data class Key(val header: String?, val prefix: String, val query: String?, val where: String) : ConnectorAuth()

    /** No credential at all. */
    object None : ConnectorAuth()

    /** Probe first: open if `initialize` answers, OAuth if it asks for it. */
    object Auto : ConnectorAuth()
}

object ConnectorsCatalogue {
    private const val ASSET = "nanomuse/connectors.json"

    @Volatile
    private var cached: Pair<List<String>, List<Connector>>? = null

    /** The category order the desktop uses, then every connector in catalogue order. */
    fun load(context: Context): Pair<List<String>, List<Connector>> {
        cached?.let { return it }
        val parsed = runCatching { parse(context.assets.open(ASSET).bufferedReader().readText()) }
            .getOrElse { emptyList<String>() to emptyList() }
        cached = parsed
        return parsed
    }

    fun entry(context: Context, id: String): Connector? = load(context).second.firstOrNull { it.id == id }

    internal fun parse(text: String): Pair<List<String>, List<Connector>> {
        val root = JSONObject(text)
        val categories = root.optJSONArray("categories")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val list = root.optJSONArray("connectors") ?: return categories to emptyList()
        val out = ArrayList<Connector>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val auth = o.optJSONObject("auth")
            val about = o.optJSONObject("about")
            out += Connector(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category", "misc"),
                url = o.getString("url"),
                auth = when (auth?.optString("kind")) {
                    "key" -> ConnectorAuth.Key(
                        header = auth.optString("header", "").ifBlank { null },
                        prefix = auth.optString("prefix", ""),
                        query = auth.optString("query", "").ifBlank { null },
                        where = auth.optString("where", ""),
                    )
                    "none" -> ConnectorAuth.None
                    "auto" -> ConnectorAuth.Auto
                    else -> ConnectorAuth.OAuth(
                        clientIdRequired = auth?.optBoolean("clientIdRequired", false) == true,
                        developer = auth?.optString("developer", "")?.ifBlank { null },
                    )
                },
                docs = o.optString("docs", ""),
                aboutEn = about?.optString("en", "") ?: "",
                aboutZh = about?.optString("zh", "") ?: "",
                color = o.optString("color", "#6B6B6B"),
            )
        }
        return categories to out
    }
}
