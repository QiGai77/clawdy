package io.github.nanomuse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main chat and a draft follow the chat slot; a side chat with a row does not (MainChatFollow). */
class MainChatFollowTest {
    private val pick = MainChatFollow.Pick(groupId = "g-own", entryId = "e-qwen")

    @Test
    fun `the persisted main chat's row is written, a draft or none has no row`() {
        assertEquals("s-main", MainChatFollow.target("s-main"))
        assertNull(MainChatFollow.target("__new__draft"))
        assertNull(MainChatFollow.target(null))
    }

    @Test
    fun `the binding is the group pick the chat view-model would write`() {
        assertEquals("""{"type":"group","groupId":"g-own","lastEntryId":"e-qwen"}""", MainChatFollow.binding(pick))
    }

    @Test
    fun `nothing is written when the row already says so, so the round stops`() {
        assertTrue(MainChatFollow.alreadySays("""{"type":"group","groupId":"g-own","lastEntryId":"e-qwen"}""", pick))
        assertFalse(MainChatFollow.alreadySays("""{"type":"entry","entryId":"e-qwen"}""", pick))
        assertFalse(MainChatFollow.alreadySays("""{"type":"group","groupId":"g-cloud","lastEntryId":"e-ds"}""", pick))
        assertFalse(MainChatFollow.alreadySays(null, pick))
    }

    @Test
    fun `an open main chat moves unless it shows the pick through that group, a side chat never`() {
        // pinned to another model, or on another group: moves
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-ds", selectedGroupId = null, pick = pick))
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = "g-cloud", pick = pick))
        // the same entry pinned directly (the chat's own picker just chose it): the group binding takes over
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = null, pick = pick))
        // already there
        assertFalse(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = "g-own", pick = pick))
        // a side chat keeps its model whatever it shows
        assertFalse(MainChatFollow.shouldMove(isMain = false, activeEntryId = "e-ds", selectedGroupId = null, pick = pick))
    }

    @Test
    fun `a draft follows like the main chat, unless it was opened on a group of its own`() {
        // the main chat before its first message (after a Cloud sign-in it is one), or a new
        // chat not yet written to: its view-model resolved the old default and must move
        assertTrue(MainChatFollow.shouldMove(isMain = false, activeEntryId = "e-openai", selectedGroupId = "g-openai", pick = pick, isNew = true))
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-openai", selectedGroupId = "g-openai", pick = pick, isNew = true))
        // already on the pick: nothing to do
        assertFalse(MainChatFollow.shouldMove(isMain = false, activeEntryId = "e-qwen", selectedGroupId = "g-own", pick = pick, isNew = true))
        // a draft opened on a group of its own is not new in this sense (the caller passes false)
        assertFalse(MainChatFollow.shouldMove(isMain = false, activeEntryId = "e-openai", selectedGroupId = "g-mine", pick = pick, isNew = false))
    }

    @Test
    fun `a group picked in a chat's picker stays as it is when it is the person's own`() {
        // named by them, one provider: kept as is
        assertTrue(MainChatFollow.keepAsIs("Work", "OpenAI", listOf("i-openai", "i-openai")))
        // named after the provider but mixing providers: kept as is
        assertTrue(MainChatFollow.keepAsIs("OpenAI", "OpenAI", listOf("i-openai", "i-bailian")))
        // a member that is not known counts as another provider
        assertTrue(MainChatFollow.keepAsIs("OpenAI", "OpenAI", listOf("i-openai", null)))
        // ours: named after the provider, that provider alone; it may be led with the pick
        assertFalse(MainChatFollow.keepAsIs("OpenAI", "OpenAI", listOf("i-openai")))
        assertFalse(MainChatFollow.keepAsIs("nanoMuse Cloud", "nanoMuse Cloud", listOf("i-cloud", "i-cloud")))
    }
}
