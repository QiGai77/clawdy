package io.github.nanomuse.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every nanoMuse string in every language: each locale's `nm_strings.xml` under `res/values-…`
 * carries exactly the keys of the English file, with the same format placeholders (a locale that lacked a key would
 * silently fall back to English on the device). The plural quantities are the locale's own, so only
 * the plural names are compared. Runs from the module directory, as unit tests do.
 */
class NmStringsParityTest {
    private val res: File = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private val nameRx = Regex("""<(string|plurals|string-array) name="([^"]+)"""")
    private val stringRx = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholderRx = Regex("""%\d\$[sd]|%[sd]""")
    private val untranslatableRx = Regex("""<string name="([^"]+)" translatable="false"""")

    private val File.locale: String get() = parentFile?.name ?: path

    private fun names(f: File): Set<String> = nameRx.findAll(f.readText()).map { it.groupValues[1] + ":" + it.groupValues[2] }.toSet()

    /** A URL or a product name marked `translatable="false"` lives in the English file only. */
    private fun untranslatable(f: File): Set<String> = untranslatableRx.findAll(f.readText()).map { "string:" + it.groupValues[1] }.toSet()

    private fun placeholders(f: File): Map<String, List<String>> =
        stringRx.findAll(f.readText()).associate { m -> m.groupValues[1] to placeholderRx.findAll(m.groupValues[2]).map { it.value }.sorted().toList() }

    private fun localeFiles(): List<File> =
        (res.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "nm_strings.xml") }.filter { it.exists() }.sortedBy { it.path }

    @Test fun `every locale carries every key of the English file`() {
        val enFile = File(res, "values/nm_strings.xml")
        val fixed = untranslatable(enFile)
        val en = names(enFile) - fixed
        assertTrue(en.size > 800)
        val locales = localeFiles()
        assertTrue("expected the sixteen locale files, found ${locales.size}", locales.size >= 16)
        for (f in locales) {
            val keys = names(f)
            val missing = en - keys
            val extra = keys - en
            assertEquals("${f.locale} lacks ${missing.size} key(s): ${missing.sorted().take(8)}", emptySet<String>(), missing)
            assertEquals("${f.locale} has ${extra.size} key(s) English does not: ${extra.sorted().take(8)}", emptySet<String>(), extra)
        }
    }

    @Test fun `a string marked not translatable has no copy in a locale`() {
        val fixed = untranslatable(File(res, "values/nm_strings.xml"))
        assertTrue("the URL and product-name strings are expected here", fixed.isNotEmpty())
        for (f in localeFiles()) {
            val copies = names(f) intersect fixed
            assertEquals("${f.locale} translates ${copies.sorted()}", emptySet<String>(), copies)
        }
    }

    @Test fun `format placeholders match the English string`() {
        val en = placeholders(File(res, "values/nm_strings.xml"))
        for (f in localeFiles()) {
            val theirs = placeholders(f)
            val wrong = en.filter { (k, v) -> theirs[k] != null && theirs[k] != v }.keys
            assertEquals("${f.locale}: placeholders differ from English in ${wrong.sorted().take(8)}", emptySet<String>(), wrong)
        }
    }

    @Test fun `no exclamation marks in the strings`() {
        for (f in localeFiles() + File(res, "values/nm_strings.xml")) {
            val shouting = stringRx.findAll(f.readText()).filter { '!' in it.groupValues[2] || '！' in it.groupValues[2] }.map { it.groupValues[1] }.toList()
            assertEquals("${f.locale}: ${shouting.take(8)}", emptyList<String>(), shouting)
        }
    }

    /**
     * aapt strips a bare `"` from a string (it reads it as quoting, to keep whitespace), so
     * *answer "always allow"* reaches the screen as *answer always allow*. A quote inside a
     * string is written `\"`, or as the language's own marks (“” 「」 „“ «»); a string wrapped
     * whole in quotes, to keep a trailing space, is the one allowed use.
     */
    @Test fun `no bare double quote inside a string`() {
        for (f in localeFiles() + File(res, "values/nm_strings.xml")) {
            val bare = stringRx.findAll(f.readText()).filter { m ->
                val body = m.groupValues[2]
                val wrapped = body.length >= 2 && body.startsWith("\"") && body.endsWith("\"") && body.count { it == '"' } == 2
                !wrapped && Regex("""(?<!\\)"""").containsMatchIn(body)
            }.map { it.groupValues[1] }.toList()
            assertEquals("${f.locale}: a bare \" in ${bare.take(8)}", emptyList<String>(), bare)
        }
    }

    /** The house style: no em dashes in copy; a comma, colon or full stop instead. 非营利 is the word, not 非盈利. */
    @Test fun `no em dashes and no 非盈利 in the strings`() {
        val commentRx = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        for (f in localeFiles() + File(res, "values/nm_strings.xml")) {
            val body = f.readText().replace(commentRx, "")
            val dashed = Regex("""name="([^"]+)"[^<]*—""").findAll(body).map { it.groupValues[1] }.toList()
            assertEquals("${f.locale}: em dash in ${dashed.take(8)}", emptyList<String>(), dashed)
            assertTrue("${f.locale}: em dash in a plural or array item", '—' !in body)
            assertTrue("${f.locale}: 非盈利 should be 非营利 / 非營利", "非盈利" !in body)
        }
    }
}
