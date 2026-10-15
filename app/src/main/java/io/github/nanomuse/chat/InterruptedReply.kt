package io.github.nanomuse.chat

import com.openminis.app.ui.chat.ChatMessage

/**
 * Which message Stop interrupted. The chat list holds a few messages of ours that only exist
 * on screen: the first conversation's opening lines (`nm_intro_*`, assistant), the naming card
 * and the avatar cards (`nm_*`, system). None of them is in the database or in the model's
 * history, so none can be the reply the model was writing. Before this, Stop during the first
 * send's setup (the session being created, the user row written) found no reply of the
 * model's yet and took the opening line as the interrupted one: it was persisted under the
 * session as a model turn, or under the draft id when Stop landed inside the session insert
 * (#340).
 */
object InterruptedReply {
    /** Our on-screen-only messages carry ids with this prefix. */
    private const val VIRTUAL_PREFIX = "nm_"

    /** True for a message that is on screen only, never in the database or the history. */
    fun isVirtual(id: String): Boolean = id.startsWith(VIRTUAL_PREFIX)

    /** The index of the last assistant message the model wrote (or is writing), or -1 when there is none. */
    fun lastRealAssistantIndex(messages: List<ChatMessage>): Int =
        messages.indexOfLast { it.role == "assistant" && !isVirtual(it.id) }
}
