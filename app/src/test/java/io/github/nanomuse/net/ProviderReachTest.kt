package io.github.nanomuse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReachTest {
    @Test fun `the maintainer's socket text becomes an unreachable chatgpt dot com`() {
        val text = "Network error: failed to connect to chatgpt.com/203.0.113.5 (port 443) from /192.0.2.10 (port 46152) after 30000ms"
        val r = ProviderReach.classify(text)
        assertNotNull(r)
        assertEquals(ProviderReach.Kind.UNREACHABLE, r!!.kind)
        assertEquals("chatgpt.com", r.host)
        assertTrue(r.isChatGptPlan)
        assertEquals(text, r.detail)
    }

    @Test fun `DNS, TLS, timeouts and resets are unreachable too, with the host when it is named`() {
        assertEquals("api.openai.com", ProviderReach.classify("Network error: Unable to resolve host \"api.openai.com\": No address associated with hostname")!!.host)
        val tls = ProviderReach.classify("Network error: SSL handshake aborted: ssl=0x7 I/O error during system call, Connection reset by peer", "chatgpt.com")!!
        assertEquals(ProviderReach.Kind.UNREACHABLE, tls.kind)
        assertEquals("chatgpt.com", tls.host)
        assertEquals(ProviderReach.Kind.UNREACHABLE, ProviderReach.classify("Network error: timeout")!!.kind)
        assertEquals(ProviderReach.Kind.UNREACHABLE, ProviderReach.classify("Network error: unexpected end of stream on https://chatgpt.com/...")!!.kind)
        assertEquals("chatgpt.com", ProviderReach.classify("Network error: unexpected end of stream on https://chatgpt.com/...")!!.host)
        assertEquals("", ProviderReach.classify("Network error: Software caused connection abort")!!.host)
    }

    @Test fun `ordinary provider errors keep upstream's text`() {
        assertNull(ProviderReach.classify("Invalid API key"))
        assertNull(ProviderReach.classify("[400] Unsupported parameter: 'max_tokens'"))
        assertNull(ProviderReach.classify("HTTP 503: upstream connect error or disconnect/reset before headers"))
        assertNull(ProviderReach.classify("Rate limited"))
        assertNull(ProviderReach.classify(""))
    }

    @Test fun `the vendor's codes win over the network words`() {
        val region = ProviderReach.classify("""[403] {"error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported"}}""", "chatgpt.com")!!
        assertEquals(ProviderReach.Kind.REGION_BLOCKED, region.kind)
        assertEquals("Country, region, or territory not supported", region.detail)
        val quota = ProviderReach.classify("""{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""", "api.openai.com")!!
        assertEquals(ProviderReach.Kind.QUOTA, quota.kind)
        assertEquals("api.openai.com", quota.host)
    }

    @Test fun `the side channel keeps what mapHttpError drops`() {
        ReachSignal.clear()
        assertNull(ReachSignal.takeFresh())
        // an API-key 401 is upstream's business
        ReachSignal.noteHttpError(401, "{\"error\":{\"message\":\"bad key\"}}", "api.openai.com", oauth = false)
        assertNull(ReachSignal.takeFresh())
        // the plan's 401 is "sign in again"
        ReachSignal.noteHttpError(401, "", "chatgpt.com", oauth = true)
        val out = ReachSignal.takeFresh()!!
        assertEquals(ProviderReach.Kind.SIGNED_OUT, out.kind)
        assertNull(ReachSignal.takeFresh()) // consumed
        // the region page as OpenAI serves it
        ReachSignal.noteHttpError(403, """{"error":{"code":"unsupported_country_region_territory","message":"not here"}}""", "chatgpt.com", oauth = true)
        assertEquals(ProviderReach.Kind.REGION_BLOCKED, ReachSignal.takeFresh()!!.kind)
        // an interception page where JSON was due
        ReachSignal.noteHttpError(403, "<!DOCTYPE html><html><body>blocked</body></html>", "chatgpt.com", oauth = true)
        assertEquals(ProviderReach.Kind.UNREACHABLE, ReachSignal.takeFresh()!!.kind)
        // the plan with nothing left, and OpenAI's own words kept
        ReachSignal.noteHttpError(429, """{"error":{"code":"usage_limit_reached","message":"Resets in 3 hours."}}""", "chatgpt.com", oauth = true, retryAfter = "7200")
        val quota = ReachSignal.takeFresh()!!
        assertEquals(ProviderReach.Kind.QUOTA, quota.kind)
        assertEquals("Resets in 3 hours.", quota.detail)
        assertEquals(7200, quota.retryAfterS)
        // a burst 429 on the plan is a wait, on an own key it is upstream's "Rate limited"
        ReachSignal.noteHttpError(429, "{}", "chatgpt.com", oauth = true)
        assertEquals(ProviderReach.Kind.RATE_LIMITED, ReachSignal.takeFresh()!!.kind)
        ReachSignal.noteHttpError(429, "{}", "api.deepseek.com", oauth = false)
        assertNull(ReachSignal.takeFresh())
    }

    @Test fun `the canonical line survives a round trip`() {
        val reach = ProviderReach.Reach(ProviderReach.Kind.QUOTA, "chatgpt.com", "Resets in 3 hours.", 7200)
        val line = ProviderReach.canonical(reach)
        assertTrue(line.startsWith("nm_reach:quota:chatgpt.com:7200|"))
        assertEquals(reach, ProviderReach.classify(line))
        val bare = ProviderReach.Reach(ProviderReach.Kind.SIGNED_OUT, "chatgpt.com")
        assertEquals(bare, ProviderReach.classify(ProviderReach.canonical(bare)))
        assertNull(ProviderReach.classify("nm_reach:nonsense"))
        // an IPv6 host carries colons of its own (a LAN server at http://[fd00::1]:8080)
        val v6 = ProviderReach.Reach(ProviderReach.Kind.UNREACHABLE, "fd00::1", "failed to connect", null)
        assertEquals(v6, ProviderReach.classify(ProviderReach.canonical(v6)))
        val v6retry = ProviderReach.Reach(ProviderReach.Kind.RATE_LIMITED, "[fd00::1]", "", 30)
        assertEquals(v6retry, ProviderReach.classify(ProviderReach.canonical(v6retry)))
        // a line written before the retry field existed still reads
        assertEquals(ProviderReach.Reach(ProviderReach.Kind.QUOTA, "chatgpt.com", "x", null), ProviderReach.classify("nm_reach:quota:chatgpt.com|x"))
    }

    @Test fun `plan hosts`() {
        assertTrue(ProviderReach.isPlanHost("chatgpt.com"))
        assertTrue(ProviderReach.isPlanHost("auth.openai.com"))
        assertFalse(ProviderReach.isPlanHost("api.openai.com"))
        assertTrue(ProviderReach.looksLikeHtml("  <!doctype HTML><html>"))
        assertFalse(ProviderReach.looksLikeHtml("{\"a\":1}"))
    }
}
