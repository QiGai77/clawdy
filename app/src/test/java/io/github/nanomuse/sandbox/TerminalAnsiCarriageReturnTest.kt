package io.github.nanomuse.sandbox

import com.openminis.app.sandbox.TerminalSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Terminal styling takes no columns when a carriage return overwrites visible text. */
class TerminalAnsiCarriageReturnTest {

    @Test
    fun `a longer plain rewrite leaves no fragment of the old color reset`() {
        assertEquals(
            "downloaded",
            TerminalSanitizer.sanitize("\u001B[31mbusy\u001B[0m\rdownloaded"),
        )
    }

    @Test
    fun `a plain shorter rewrite keeps the visible tail of a colored line`() {
        assertEquals("BBAA", TerminalSanitizer.sanitize("\u001B[31mAAAA\u001B[0m\rBB"))
    }

    @Test
    fun `a colored shorter rewrite keeps the visible tail of a plain line`() {
        assertEquals("BBAA", TerminalSanitizer.sanitize("AAAA\r\u001B[32mBB\u001B[0m"))
    }

    @Test
    fun `OSC titles take no columns before a carriage return`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            assertEquals(
                "BBAA",
                TerminalSanitizer.sanitize("\u001B]0;title${terminator}AAAA\rBB"),
            )
        }
    }
}
