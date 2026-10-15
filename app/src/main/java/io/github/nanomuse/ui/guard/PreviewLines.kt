package io.github.nanomuse.ui.guard

/**
 * The approval card's shell preview shows a long command folded: the first [COLLAPSED] lines
 * and a "Show all (N lines)" button. Pure string work, kept apart from the card so it can be
 * tested without Compose. A line is what the command separates with a line break; a long line
 * without breaks still wraps on screen but counts as one.
 */
object PreviewLines {
    /** How many lines the folded preview shows. */
    const val COLLAPSED = 6

    /** The lines of [text]; an empty command has none. */
    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()

    /** How many lines [text] has in all. */
    fun count(text: String): Int = lines(text).size

    /** True when [text] has more than [limit] lines and the card should offer "Show all". */
    fun hasMore(text: String, limit: Int = COLLAPSED): Boolean = count(text) > limit

    /** The first [limit] lines of [text], joined back with line breaks; [text] itself when it fits. */
    fun collapsed(text: String, limit: Int = COLLAPSED): String {
        val all = lines(text)
        if (all.size <= limit) return text
        return all.take(limit).joinToString("\n")
    }
}
