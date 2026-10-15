package io.github.nanomuse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's collapsed groups and search field: ordering, the eight-row cap, the filter. */
class PickerListTest {
    private data class Row(val id: String, val name: String? = null)
    private data class Group(val provider: String, val rows: List<Row>)

    private fun rows(vararg ids: String) = ids.map { Row(it) }
    private fun many(n: Int, prefix: String = "m") = (1..n).map { Row("$prefix$it") }

    // ── order ─────────────────────────────────────────────────────────

    @Test fun `the catalogue default leads, then the chosen model, then the rest as listed`() {
        val list = rows("a", "b", "c", "d", "e")
        val out = PickerList.order(list, isDefault = { it.id == "d" }, isCurrent = { it.id == "b" })
        assertEquals(rows("d", "b", "a", "c", "e"), out)
    }

    @Test fun `a chosen model that is the default is shown once`() {
        val list = rows("a", "b", "c")
        val out = PickerList.order(list, isDefault = { it.id == "c" }, isCurrent = { it.id == "c" })
        assertEquals(rows("c", "a", "b"), out)
    }

    @Test fun `only the chosen model moves when the catalogue has no default here`() {
        val list = rows("a", "b", "c")
        assertEquals(rows("c", "a", "b"), PickerList.order(list, isDefault = { false }, isCurrent = { it.id == "c" }))
    }

    @Test fun `neither default nor choice leaves the list as it came`() {
        val list = rows("a", "b", "c")
        assertSame(list, PickerList.order(list, isDefault = { false }, isCurrent = { false }))
    }

    @Test fun `the cloud group keeps its recommended model first`() {
        val list = rows("deepseek-v4.1-flash", "qwen3.8-27b", "kimi-k3")
        val out = PickerList.order(list, isDefault = { it.id == "qwen3.8-27b" }, isCurrent = { it.id == "kimi-k3" })
        assertEquals(rows("qwen3.8-27b", "kimi-k3", "deepseek-v4.1-flash"), out)
    }

    // ── collapse ──────────────────────────────────────────────────────

    @Test fun `a group of eight or fewer shows every row and no more-row`() {
        val eight = many(8)
        assertEquals(PickerList.Shown(eight, 0), PickerList.collapse(eight, expanded = false))
        assertEquals(PickerList.Shown(many(3), 0), PickerList.collapse(many(3), expanded = false))
    }

    @Test fun `a group of more than eight shows eight and counts the rest`() {
        val shown = PickerList.collapse(many(200), expanded = false)
        assertEquals(8, shown.rows.size)
        assertEquals(many(8), shown.rows)
        assertEquals(192, shown.hidden)
        assertEquals(PickerList.Shown(many(9).take(8), 1), PickerList.collapse(many(9), expanded = false))
    }

    @Test fun `an expanded group shows everything`() {
        val all = many(42)
        assertEquals(PickerList.Shown(all, 0), PickerList.collapse(all, expanded = true))
    }

    @Test fun `the default and the chosen model are inside the eight`() {
        val list = many(100)
        val ordered = PickerList.order(list, isDefault = { it.id == "m77" }, isCurrent = { it.id == "m31" })
        val shown = PickerList.collapse(ordered, expanded = false)
        assertEquals(listOf("m77", "m31", "m1", "m2", "m3", "m4", "m5", "m6"), shown.rows.map { it.id })
        assertEquals(92, shown.hidden)
    }

    // ── search ────────────────────────────────────────────────────────

    @Test fun `the search field appears past eight rows in all`() {
        assertFalse(PickerList.searchable(0))
        assertFalse(PickerList.searchable(8))
        assertTrue(PickerList.searchable(9))
    }

    @Test fun `a blank field is not a search`() {
        assertFalse(PickerList.searching(""))
        assertFalse(PickerList.searching("   "))
        assertTrue(PickerList.searching("q"))
    }

    @Test fun `matching is a case-insensitive substring on the id or the display name`() {
        assertTrue(PickerList.matches("QWEN", "qwen3-vl-plus", null))
        assertTrue(PickerList.matches("vl-p", "qwen3-vl-plus", null))
        assertTrue(PickerList.matches("sonnet", "anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"))
        assertTrue(PickerList.matches("claude sonnet", "anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"))
        assertFalse(PickerList.matches("gpt", "qwen3-vl-plus", "Qwen3 VL Plus"))
        assertTrue(PickerList.matches("  ", "anything", null))
    }

    @Test fun `a query shows every match of every group with no cap and hides groups without one`() {
        val groups = listOf(
            Group("nanoMuse Cloud", rows("deepseek-v4.1-flash", "qwen3.8-27b")),
            Group("OpenRouter", many(50, "openai/gpt-") + many(30, "qwen/qwen-")),
            Group("SiliconFlow", many(20, "Qwen/Qwen3-")),
        )
        val out = PickerList.filter(groups, "qwen", { it.rows }, { it.id }, { it.name }, { g, r -> g.copy(rows = r) })
        assertEquals(listOf("nanoMuse Cloud", "OpenRouter", "SiliconFlow"), out.map { it.provider })
        assertEquals(1, out[0].rows.size)
        assertEquals(30, out[1].rows.size)
        assertEquals(20, out[2].rows.size)
        assertEquals(PickerList.Shown(out[1].rows, 0), PickerList.collapse(out[1].rows, expanded = true))

        val gpt = PickerList.filter(groups, "GPT", { it.rows }, { it.id }, { it.name }, { g, r -> g.copy(rows = r) })
        assertEquals(listOf("OpenRouter"), gpt.map { it.provider })
        assertEquals(50, gpt[0].rows.size)
    }

    @Test fun `the display name counts too`() {
        val groups = listOf(Group("OpenRouter", listOf(Row("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"), Row("openai/o5", "o5"))))
        val out = PickerList.filter(groups, "Sonnet", { it.rows }, { it.id }, { it.name }, { g, r -> g.copy(rows = r) })
        assertEquals(listOf("anthropic/claude-sonnet-5.5"), out.single().rows.map { it.id })
    }

    @Test fun `nothing matching is an empty list for the one line to say so`() {
        val groups = listOf(Group("OpenRouter", many(20)))
        assertTrue(PickerList.filter(groups, "zzz", { it.rows }, { it.id }, { it.name }, { g, r -> g.copy(rows = r) }).isEmpty())
    }

    @Test fun `clearing the field gives the groups back whole`() {
        val groups = listOf(Group("OpenRouter", many(20)))
        assertSame(groups, PickerList.filter(groups, "", { it.rows }, { it.id }, { it.name }, { g, r -> g.copy(rows = r) }))
        assertEquals(8, PickerList.collapse(groups[0].rows, expanded = false).rows.size)
    }
}
