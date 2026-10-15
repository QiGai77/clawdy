package io.github.nanomuse.sync

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import io.github.nanomuse.hub.Hub

/**
 * Which chat rows another device of the account wrote (contract C9).
 *
 * OpenMinis reads "the last row is a user message with no reply" as a turn this phone was
 * cut off in, and offers "Interrupted — tap Continue to resume". A line that arrived through
 * sync is not this phone's turn: the device that wrote it is answering it, and *Continue*
 * here would run it a second time, on the wrong device. Every site that detects an
 * interrupted tail, or resumes one on its own, asks here first.
 */
object RemoteRows {
    /** A database row id, with or without OpenMinis' `#n` block suffix. */
    fun isRemote(messageId: String?): Boolean = deviceOf(messageId) != null

    /** The id of the device that wrote the row, or null for this phone's own. */
    fun deviceOf(messageId: String?): String? {
        val id = messageId?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return null
        return ConversationSync.remoteRows.value[id]
    }

    /** An entry of the agent history: remote when its database row is. */
    fun isRemote(entry: LLMMessage?): Boolean = isRemote(entry?.dbMessageId)

    /**
     * The same question straight from the store, for a cold start where the in-memory set may
     * not be loaded yet (a chat opened in the first second after launch).
     */
    suspend fun isRemote(context: Context, sessionId: String, messageId: String?): Boolean {
        if (isRemote(messageId)) return true
        val id = messageId?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return false
        val ctx = context.applicationContext
        return runCatching {
            val me = Hub.deviceId(ctx)
            RoomSyncStore(ctx).messages(sessionId).any { it.messageId == id && it.device.isNotBlank() && it.device != me }
        }.getOrDefault(false)
    }
}
