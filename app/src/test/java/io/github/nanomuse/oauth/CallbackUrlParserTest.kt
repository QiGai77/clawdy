package io.github.nanomuse.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pasted callback address of a sign-in whose loopback redirect a proxy swallowed (#441). */
class CallbackUrlParserTest {
    private fun ok(text: String): CallbackUrlParser.Callback =
        (CallbackUrlParser.parse(text) as? CallbackUrlParser.Result.Ok)?.callback
            ?: throw AssertionError("expected a code in: $text")

    @Test fun `a whole address gives code, state, port and path`() {
        val c = ok("http://localhost:1455/auth/callback?code=abc123&state=st9")
        assertEquals("abc123", c.code)
        assertEquals("st9", c.state)
        assertEquals(1455, c.port)
        assertEquals("/auth/callback", c.path)
    }

    @Test fun `the address without a scheme is read the same`() {
        val c = ok("localhost:54545/callback?code=k1&state=s1")
        assertEquals("k1", c.code)
        assertEquals(54545, c.port)
        assertEquals("/callback", c.path)
    }

    @Test fun `127 dot 0 dot 0 dot 1 and surrounding spaces are fine`() {
        val c = ok("  http://127.0.0.1:56121/callback?state=x&code=y  \n")
        assertEquals("y", c.code)
        assertEquals("x", c.state)
        assertEquals(56121, c.port)
    }

    @Test fun `a bare query string has no port and no path`() {
        val c = ok("code=abc&state=def")
        assertEquals("abc", c.code)
        assertEquals("def", c.state)
        assertNull(c.port)
        assertNull(c.path)
    }

    @Test fun `a query with its question mark is a query`() {
        val c = ok("?code=abc")
        assertEquals("abc", c.code)
        assertNull(c.state)
        assertNull(c.port)
    }

    @Test fun `percent escapes are decoded and a fragment is dropped`() {
        val c = ok("http://localhost:1455/auth/callback?code=a%2Fb%3Dc&state=s%20t#frag")
        assertEquals("a/b=c", c.code)
        assertEquals("s t", c.state)
    }

    @Test fun `an address without a code is refused`() {
        assertTrue(CallbackUrlParser.parse("http://localhost:1455/auth/callback?error=access_denied") is CallbackUrlParser.Result.NoCode)
        assertTrue(CallbackUrlParser.parse("https://auth.openai.com/oauth/authorize?client_id=x") is CallbackUrlParser.Result.NoCode)
        assertTrue(CallbackUrlParser.parse("") is CallbackUrlParser.Result.NoCode)
        assertTrue(CallbackUrlParser.parse("   ") is CallbackUrlParser.Result.NoCode)
        assertTrue(CallbackUrlParser.parse("just some words") is CallbackUrlParser.Result.NoCode)
        assertTrue(CallbackUrlParser.parse("code=") is CallbackUrlParser.Result.NoCode)
    }

    @Test fun `an out of range port is ignored, the code is kept`() {
        val c = ok("http://localhost:99999/callback?code=z")
        assertEquals("z", c.code)
        assertNull(c.port)
        assertEquals("/callback", c.path)
    }

    @Test fun `the first value wins when a key repeats`() {
        val c = ok("code=first&code=second")
        assertEquals("first", c.code)
    }
}
