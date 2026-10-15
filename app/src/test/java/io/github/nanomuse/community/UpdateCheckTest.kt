package io.github.nanomuse.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test
    fun `versions compare semver-ish`() {
        assertTrue(UpdateCheck.isNewer("0.1.35", "0.1.34"))
        assertFalse(UpdateCheck.isNewer("0.1.34", "0.1.34"))
        assertFalse(UpdateCheck.isNewer("0.1.33", "0.1.34"))
        assertTrue(UpdateCheck.isNewer("0.1.10", "0.1.9"))
        assertTrue(UpdateCheck.isNewer("0.2", "0.1.99"))
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.9.9"))
        // The tag's v and a missing patch are no difference.
        assertFalse(UpdateCheck.isNewer("v0.1.34", "0.1.34"))
        assertFalse(UpdateCheck.isNewer("0.1.34.0", "0.1.34"))
        // A pre-release sits below the release it precedes.
        assertTrue(UpdateCheck.isNewer("0.1.35", "0.1.35-rc1"))
        assertFalse(UpdateCheck.isNewer("0.1.35-rc1", "0.1.35"))
        assertTrue(UpdateCheck.isNewer("0.1.35-rc1", "0.1.34"))
    }

    @Test
    fun `the download index is read newest first`() {
        val index = """
            {"repo":"nano-muse/nanoMuse","releases":[
              {"tag":"v0.1.35","name":"Turns+","published_at":"2026-10-05T00:00:00Z","assets":[{"name":"nanoMuse-0.1.35.apk","size":1}]},
              {"tag":"v0.1.34","name":"Turns","published_at":"2026-09-28T00:00:00Z","assets":[]}
            ]}
        """.trimIndent()
        assertEquals("0.1.35", UpdateCheck.parseIndex(index))
        assertNull(UpdateCheck.parseIndex("""{"repo":"x","releases":[]}"""))
        assertNull(UpdateCheck.parseIndex("""{"repo":"x"}"""))
        assertNull(UpdateCheck.parseIndex("<html>"))
        // An entry without a tag is skipped, not taken as empty.
        assertEquals("0.1.34", UpdateCheck.parseIndex("""{"releases":[{"name":"draft"},{"tag":"v0.1.34"}]}"""))
    }

    @Test
    fun `GitHub's latest release gives its tag`() {
        assertEquals("0.1.34", UpdateCheck.parseGitHubLatest("""{"tag_name":"v0.1.34","name":"Turns"}"""))
        assertNull(UpdateCheck.parseGitHubLatest("""{"message":"Not Found"}"""))
        assertNull(UpdateCheck.parseGitHubLatest(""))
    }

    @Test
    fun `tags are normalised`() {
        assertEquals("0.1.35", UpdateCheck.normalize(" v0.1.35\n"))
        assertEquals("0.1.35", UpdateCheck.normalize("V0.1.35"))
        assertEquals("0.1.35", UpdateCheck.normalize("0.1.35"))
    }
}
