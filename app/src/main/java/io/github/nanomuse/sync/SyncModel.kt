package io.github.nanomuse.sync

import java.io.IOException

/*
 * The shapes of contract C7 (conversation sync across the account's devices), as the relay
 * sends and takes them. Timestamps are Unix seconds, as on the wire; the local side works in
 * milliseconds and converts at the edge.
 */

/** A file or picture that rode with a message — its name and kind only, never the bytes. */
data class Attachment(val name: String, val mime: String, val size: Long)

/** One conversation as the relay lists it in `GET /v1/sync/changes`. */
data class RemoteConversation(
    val cid: String,
    val kind: String,
    val title: String?,
    val device: String,
    val deviceName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val seq: Long,
)

/** One message as the relay lists it. */
data class RemoteMessage(
    val mid: String,
    val cid: String,
    val seq: Long,
    val device: String,
    val role: String,
    val text: String,
    val truncated: Boolean,
    val attachments: List<Attachment>,
    val createdAt: Long,
    val deleted: Boolean,
    /** The name of the device that wrote it (`device_name`), for the "From Pixel 8" caption. */
    val deviceName: String = "",
)

/** A page of changes since a cursor, in `seq` order. */
data class Changes(
    val cursor: Long,
    val more: Boolean,
    val conversations: List<RemoteConversation>,
    val messages: List<RemoteMessage>,
    /** A tail page (C9): how many older messages the relay kept back. 0 on an ordinary page. */
    val skipped: Int = 0,
)

/**
 * Another device is working on a synced conversation (contract C9): the relay's `working`
 * frame and the `working[]` of `GET /v1/sync/state`. [at] in Unix seconds, as the relay
 * stamped it; the line in the chat goes away ten minutes after it.
 */
data class WorkingPresence(val cid: String, val from: String, val deviceName: String, val at: Long) {
    /** Still worth showing: the relay forgets a `true` after ten minutes, and so do we. */
    fun live(nowSeconds: Long): Boolean = nowSeconds - at < TTL_SECONDS

    companion object {
        const val TTL_SECONDS = 10 * 60L
    }
}

/** A conversation as this device posts it. */
data class OutConversation(
    val cid: String,
    val kind: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A message as this device posts it; `deleted` makes it a tombstone for a known `mid`. */
data class OutMessage(
    val mid: String,
    val cid: String,
    val role: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long,
    val deleted: Boolean = false,
)

/** What the relay refused in a POST, and why. */
data class Rejection(val mid: String?, val cid: String?, val reason: String, val cidMain: String?)

data class PushResult(val cursor: Long, val accepted: Int, val rejected: List<Rejection>)

/** `GET /v1/sync/state`; [working] is who is on which conversation right now (relay 0.20, empty before). */
data class SyncState(
    val enabled: Boolean,
    val cursor: Long,
    val conversations: Int,
    val messages: Int,
    val working: List<WorkingPresence> = emptyList(),
)

/**
 * The relay said no, or could not be reached ([status] 0). `code` is the relay's stable word
 * (`sync_off`, `bad_key`) or `http_<status>`.
 */
class SyncException(val status: Int, val code: String, message: String) : IOException(message)
