package io.github.nanomuse.ui.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The folded shell preview on the approval card (#436): six lines, then "Show all (N lines)". */
class PreviewLinesTest {
    private fun command(n: Int): String = (1..n).joinToString("\n") { "echo line $it" }

    @Test fun `an empty command has no lines`() {
        assertEquals(0, PreviewLines.count(""))
        assertFalse(PreviewLines.hasMore(""))
        assertEquals("", PreviewLines.collapsed(""))
    }

    @Test fun `a short command is shown whole`() {
        val cmd = command(6)
        assertEquals(6, PreviewLines.count(cmd))
        assertFalse(PreviewLines.hasMore(cmd))
        assertEquals(cmd, PreviewLines.collapsed(cmd))
    }

    @Test fun `a long command folds to the first six lines`() {
        val cmd = command(40)
        assertEquals(40, PreviewLines.count(cmd))
        assertTrue(PreviewLines.hasMore(cmd))
        assertEquals(command(6), PreviewLines.collapsed(cmd))
    }

    @Test fun `windows line endings count as one break`() {
        val cmd = (1..8).joinToString("\r\n") { "step $it" }
        assertEquals(8, PreviewLines.count(cmd))
        assertEquals((1..6).joinToString("\n") { "step $it" }, PreviewLines.collapsed(cmd))
    }

    @Test fun `one long line without breaks is one line`() {
        val cmd = "curl " + "x".repeat(5000)
        assertEquals(1, PreviewLines.count(cmd))
        assertFalse(PreviewLines.hasMore(cmd))
        assertEquals(cmd, PreviewLines.collapsed(cmd))
    }

    @Test fun `the limit is adjustable`() {
        val cmd = command(5)
        assertTrue(PreviewLines.hasMore(cmd, limit = 3))
        assertEquals(command(3), PreviewLines.collapsed(cmd, limit = 3))
    }
}
