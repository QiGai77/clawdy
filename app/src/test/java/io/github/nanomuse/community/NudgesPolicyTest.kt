package io.github.nanomuse.community

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgesPolicyTest {

    /** The relay's shipped default, verbatim from the shared contract (C1). */
    private val contractDefault = """
        {
          "version": 1,
          "star": {
            "enabled": true,
            "url": "https://github.com/nano-muse/nanoMuse",
            "moments": {
              "signed_in": true,
              "tasks": [3, 10, 30],
              "new_look": true,
              "exhausted": true,
              "days_used": [7, 30],
              "goal_done": true
            },
            "cooldown_days": 7,
            "max_asks": 4
          }
        }
    """.trimIndent()

    @Test
    fun `the built-in default is the contract's default`() {
        assertEquals(Nudges.Policy.parse(contractDefault), Nudges.Policy.DEFAULT)
    }

    @Test
    fun `the default round-trips through JSON`() {
        val json = Nudges.Policy.DEFAULT.toJson().toString()
        assertEquals(Nudges.Policy.DEFAULT, Nudges.Policy.parse(json))
    }

    @Test
    fun `missing keys keep the default and listed counts are sorted and cleaned`() {
        val p = Nudges.Policy.parse("""{"star":{"enabled":false,"moments":{"tasks":[30,3,"x",3,0,-1,10]}}}""")!!
        assertFalse(p.enabled)
        assertEquals(listOf(3, 10, 30), p.moments.tasks)
        assertEquals(listOf(7, 30), p.moments.daysUsed)
        assertTrue(p.moments.signedIn)
        assertEquals(7, p.cooldownDays)
        assertEquals(4, p.maxAsks)
        assertEquals(StarPrompt.REPO_URL, p.url)
    }

    @Test
    fun `an operator can switch moments off and change the counts`() {
        val p = Nudges.Policy.parse(
            """{"version":2,"star":{"moments":{"signed_in":false,"tasks":[5],"days_used":[],"goal_done":false},"cooldown_days":14,"max_asks":2}}""",
        )!!
        assertEquals(2, p.version)
        assertFalse(p.moments.signedIn)
        assertEquals(listOf(5), p.moments.tasks)
        assertTrue(p.moments.daysUsed.isEmpty())
        assertFalse(p.moments.goalDone)
        assertEquals(14, p.cooldownDays)
        assertEquals(2, p.maxAsks)
    }

    @Test
    fun `only an https url replaces the repository`() {
        assertEquals(StarPrompt.REPO_URL, Nudges.Policy.parse("""{"star":{"url":"javascript:alert(1)"}}""")!!.url)
        assertEquals("https://example.org/star", Nudges.Policy.parse("""{"star":{"url":"https://example.org/star"}}""")!!.url)
    }

    // ── the operator's sentence (star.text, star.text_zh) ──────────────────

    @Test
    fun `a policy without the text fields reads as empty and keeps the app's own line`() {
        val p = Nudges.Policy.parse(contractDefault)!!
        assertEquals("", p.text)
        assertEquals("", p.textZh)
        assertNull(p.sentence("en"))
        assertNull(p.sentence("zh"))
        assertNull(p.sentence(null))
        // A cached copy from before the fields round-trips to the same empty values.
        assertEquals(p, Nudges.Policy.parse(p.toJson().toString()))
    }

    @Test
    fun `the text fields are read, trimmed and round-trip through the cache`() {
        val p = Nudges.Policy.parse("""{"star":{"text":"  Stars help.  ","text_zh":"\n点个 star。\t"}}""")!!
        assertEquals("Stars help.", p.text)
        assertEquals("点个 star。", p.textZh)
        assertEquals(p, Nudges.Policy.parse(p.toJson().toString()))
        // Not a string: empty, never a crash.
        val odd = Nudges.Policy.parse("""{"star":{"text":42,"text_zh":["x"]}}""")!!
        assertEquals("", odd.text)
        assertEquals("", odd.textZh)
    }

    @Test
    fun `a sentence longer than 200 code points is dropped`() {
        val ok = "a".repeat(200)
        val tooLong = "a".repeat(201)
        val p = Nudges.Policy.parse(JSONObject().put("star", JSONObject().put("text", ok).put("text_zh", tooLong)))!!
        assertEquals(ok, p.text)
        assertEquals("", p.textZh)
        // Code points, not UTF-16 units: 200 characters outside the BMP still fit.
        val astral = "\uD83C\uDF1F".repeat(200)
        assertEquals(astral, Nudges.Policy.parse(JSONObject().put("star", JSONObject().put("text", astral)))!!.text)
        // The limit applies after trimming.
        val padded = " ".repeat(10) + ok + " ".repeat(10)
        assertEquals(ok, Nudges.Policy.parse(JSONObject().put("star", JSONObject().put("text", padded)))!!.text)
    }

    @Test
    fun `chinese takes text_zh first and then text, other languages take text only`() {
        val both = Nudges.Policy(text = "English line", textZh = "中文句子")
        assertEquals("中文句子", both.sentence("zh"))
        assertEquals("中文句子", both.sentence("ZH"))
        assertEquals("English line", both.sentence("en"))
        assertEquals("English line", both.sentence("de"))
        assertEquals("English line", both.sentence(null))

        val englishOnly = Nudges.Policy(text = "English line")
        assertEquals("English line", englishOnly.sentence("zh"))
        assertEquals("English line", englishOnly.sentence("en"))

        val chineseOnly = Nudges.Policy(textZh = "中文句子")
        assertEquals("中文句子", chineseOnly.sentence("zh"))
        assertNull(chineseOnly.sentence("en"))
    }

    @Test
    fun `a document without a star object is not a policy`() {
        assertNull(Nudges.Policy.parse("""{"error":{"code":"x"}}"""))
        assertNull(Nudges.Policy.parse("not json"))
        assertNull(Nudges.Policy.parse(""))
        assertNull(Nudges.Policy.parse(null as String?))
        assertNull(Nudges.Policy.parse(JSONObject()))
    }
}
