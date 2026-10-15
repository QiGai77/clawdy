package io.github.nanomuse.sandbox

import com.openminis.app.sandbox.TerminalSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A carriage return in tool output moves the cursor to column 0; what follows overwrites from there
 * and a shorter rewrite keeps the old tail (#334). The model reads the result, so nothing may be lost.
 */
class TerminalCarriageReturnTest {

    @Test
    fun `a shorter rewrite keeps the tail of the longer text`() {
        assertEquals("BBAA", TerminalSanitizer.sanitize("AAAA\rBB"))
    }

    @Test
    fun `a progress line ends with its last state`() {
        assertEquals("100%", TerminalSanitizer.sanitize("10%\r50%\r100%"))
    }

    @Test
    fun `a newline ends the overwrite`() {
        assertEquals("LINE1\nline2", TerminalSanitizer.sanitize("line1\rLINE\nline2"))
    }

    @Test
    fun `CRLF is a plain line break`() {
        assertEquals("line1\nline2\nline3", TerminalSanitizer.sanitize("line1\r\nline2\r\nline3"))
    }

    @Test
    fun `a longer rewrite replaces the whole line`() {
        assertEquals("downloaded", TerminalSanitizer.sanitize("busy\rdownloaded"))
    }
}
