package io.github.nanomuse.models

/**
 * How a slot's picker keeps a provider with hundreds of models from becoming an endless list
 * (the shared contract, same on iOS, desktop and web). Pure Kotlin so the unit tests run it
 * without a device; [ModelSlots] and the picker screen feed it the rows.
 *
 * - A group shows at most [COLLAPSED] rows until it is expanded: the provider's catalogue
 *   default for the slot first, then the chosen model when it is in the group, then the rest
 *   in the order the list came. Past the cap, one row says how many more there are.
 * - Once the groups together hold more than [COLLAPSED] rows, a search field filters them live
 *   by a case-insensitive substring of the model id or its display name. While a query is
 *   present every group shows all its matches, a group without one is hidden, and no match
 *   at all is one line that says so.
 */
object PickerList {
    /** Rows a group shows before it is expanded, and the number of rows a search field appears above. */
    const val COLLAPSED = 8

    /** A group's rows as shown: [rows] now, [hidden] more behind the *Show N more* row (0 when none). */
    data class Shown<T>(val rows: List<T>, val hidden: Int)

    /**
     * The order a group's rows take: the catalogue default first, then the chosen one when
     * it is in the group and is not the default, then the rest as they came. Neither
     * predicate matching leaves the list as it was.
     */
    fun <T> order(rows: List<T>, isDefault: (T) -> Boolean, isCurrent: (T) -> Boolean): List<T> {
        val default = rows.firstOrNull(isDefault)
        val current = rows.firstOrNull(isCurrent)?.takeIf { it != default }
        val head = listOfNotNull(default, current)
        if (head.isEmpty()) return rows
        return head + rows.filter { it !== default && it !== current }
    }

    /** The first [COLLAPSED] rows unless [expanded]; [Shown.hidden] counts what the cap left out. */
    fun <T> collapse(rows: List<T>, expanded: Boolean): Shown<T> =
        if (expanded || rows.size <= COLLAPSED) Shown(rows, 0)
        else Shown(rows.take(COLLAPSED), rows.size - COLLAPSED)

    /** Whether the picker offers a search field: only once the groups together hold more rows than one collapsed group. */
    fun searchable(totalRows: Int): Boolean = totalRows > COLLAPSED

    /** True when [query] has text to filter by; a blank field is the collapsed view. */
    fun searching(query: String): Boolean = query.isNotBlank()

    /** Case-insensitive substring match of [query] on the model id or its display name. */
    fun matches(query: String, modelId: String, displayName: String?): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return modelId.contains(q, ignoreCase = true) || (displayName?.contains(q, ignoreCase = true) ?: false)
    }

    /**
     * Each group's rows for [query]: every match, no cap, in the group's order; a group
     * without a match is dropped. With a blank query every group comes back whole.
     */
    fun <G, T> filter(
        groups: List<G>,
        query: String,
        rowsOf: (G) -> List<T>,
        idOf: (T) -> String,
        nameOf: (T) -> String?,
        rebuild: (G, List<T>) -> G,
    ): List<G> {
        if (!searching(query)) return groups
        return groups.mapNotNull { g ->
            val kept = rowsOf(g).filter { matches(query, idOf(it), nameOf(it)) }
            if (kept.isEmpty()) null else rebuild(g, kept)
        }
    }
}
