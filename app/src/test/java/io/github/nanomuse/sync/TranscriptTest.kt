package io.github.nanomuse.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows of an OpenMinis chat are the conversation the other devices see. */
class TranscriptTest {
    private val chats = MemoryChats()

    private fun items(sessionId: String) = Transcript.items(chats.rows.getValue(sessionId).sortedBy { it.createdAt })

    @Test fun `a plain turn is the question and the answer`() {
        chats.addSession("s")
        chats.user("s", "What time is it?")
        chats.assistant("s", "Half past three.")
        val out = items("s")
        assertEquals(listOf("user" to "What time is it?", "assistant" to "Half past three."), out.map { it.role to it.text })
    }

    @Test fun `tool steps are skipped and only the final reply goes`() {
        chats.addSession("s")
        chats.user("s", "list my downloads")
        chats.assistantTool("s", "Let me look.")
        chats.toolResult("s")
        chats.assistantTool("s", "")
        chats.toolResult("s")
        chats.assistant("s", "Three files: a, b, c.")
        val out = items("s")
        assertEquals(listOf("user" to "list my downloads", "assistant" to "Three files: a, b, c."), out.map { it.role to it.text })
    }

    @Test fun `a turn still running or stopped mid-way sends the question only`() {
        chats.addSession("s")
        chats.user("s", "do the thing")
        chats.assistantTool("s", "On it.")
        chats.toolResult("s")
        assertEquals(listOf("user"), items("s").map { it.role })
        // the next question closes the turn: its last text, if any, goes along
        chats.user("s", "never mind")
        chats.assistant("s", "Stopped.")
        assertEquals(listOf("user" to "do the thing", "assistant" to "On it.", "user" to "never mind", "assistant" to "Stopped."), items("s").map { it.role to it.text })
    }

    @Test fun `pictures ride as names, system reminders are stripped`() {
        chats.addSession("s")
        val id = chats.userWithPicture("s", "<system-reminder>nudge</system-reminder>what is this?", "photo.png")
        chats.assistant("s", "A cat.")
        val out = items("s")
        assertEquals(id, out[0].messageId)
        assertEquals("what is this?", out[0].text)
        assertEquals(listOf(Attachment("photo.png", "image/png", 0)), out[0].attachments)
        assertTrue(out[1].attachments.isEmpty())
    }

    @Test fun `the continue nudge is not a message of the person`() {
        chats.addSession("s")
        chats.user("s", "go")
        chats.assistantTool("s", "")
        chats.user("s", "The user stopped the previous response. Continue.")
        chats.assistant("s", "Done.")
        assertEquals(listOf("user" to "go", "assistant" to "Done."), items("s").map { it.role to it.text })
    }

    @Test fun `pulled parts are one text part with the attachment lines`() {
        val arr = org.json.JSONArray(Transcript.partsJson("hello", listOf("[Attachment a.pdf]")))
        assertEquals(1, arr.length())
        assertEquals("text", arr.getJSONObject(0).getString("type"))
        assertEquals("hello\n\n[Attachment a.pdf]", arr.getJSONObject(0).getString("value"))
        val plain = org.json.JSONArray(Transcript.partsJson("hello", emptyList()))
        assertEquals("hello", plain.getJSONObject(0).getString("value"))
    }
}
