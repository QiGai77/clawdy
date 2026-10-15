package io.github.nanomuse.chat

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedReplyTest {
    private fun intro(i: Int) = ChatMessage(
        id = "nm_intro_$i",
        role = "assistant",
        content = "Hello, I am nanoMuse. $i",
        toolBlocks = listOf(AssistantBlock(id = "nm_intro_${i}_text", kind = "text", content = "Hello, I am nanoMuse. $i")),
    )
    private val namingCard = ChatMessage(id = "nm_naming_card", role = "system", content = "")
    private val user = ChatMessage(id = "u-1", role = "user", content = "Call yourself Pip")
    private val placeholder = ChatMessage(id = "a-1", role = "assistant", content = "", isStreaming = true, isAwaitingModelResponse = true)
    private val reply = ChatMessage(id = "a-0", role = "assistant", content = "Pip it is.")

    @Test fun `the opening lines and the cards are never the interrupted reply`() {
        assertEquals(-1, InterruptedReply.lastRealAssistantIndex(listOf(intro(0), intro(1), intro(2), namingCard)))
        assertEquals(-1, InterruptedReply.lastRealAssistantIndex(listOf(intro(0), intro(1), intro(2), user)))
        assertEquals(-1, InterruptedReply.lastRealAssistantIndex(emptyList()))
    }

    @Test fun `the streaming placeholder after the opening is`() {
        val msgs = listOf(intro(0), intro(1), intro(2), user, placeholder)
        assertEquals(4, InterruptedReply.lastRealAssistantIndex(msgs))
    }

    @Test fun `the last reply of the model wins over an earlier one and over a later card`() {
        val msgs = listOf(user, reply, user.copy(id = "u-2"), placeholder, namingCard)
        assertEquals(3, InterruptedReply.lastRealAssistantIndex(msgs))
        assertEquals(1, InterruptedReply.lastRealAssistantIndex(listOf(user, reply, namingCard)))
    }

    @Test fun `only our prefix marks a message as virtual`() {
        assertTrue(InterruptedReply.isVirtual("nm_intro_2"))
        assertTrue(InterruptedReply.isVirtual("nm_avatar_share_card"))
        assertFalse(InterruptedReply.isVirtual("a-1"))
        assertFalse(InterruptedReply.isVirtual("0c6b7f2e-nm_"))
    }
}
