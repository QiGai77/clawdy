package io.github.nanomuse.models

import io.github.nanomuse.home.MainChat

/**
 * The main chat follows the chat slot. Every chat keeps the binding it was made with, and the
 * chat slot (Settings › Models, the "Use it for" card, a pick in a chat's own picker) is the
 * default for new ones; but the main chat is the one conversation the home always shows and is
 * never new, so on 0.1.41 the Models page could not move it off the provider it started on: a
 * person who saved a key of their own saw their side chats answer through it while the main chat
 * kept nanoMuse Cloud under the face. Now the main chat's binding moves with the slot, as the
 * iPhone does (`NanoMuseModelSlots.mainChatFollows`); side chats are left as they are.
 *
 * A chat that has no row yet (a draft: the main chat before its first message, a new chat
 * opened and not yet written to) follows too. Its view-model resolved the default when it was
 * built and keeps that provider until it is told otherwise, so without this a draft main chat
 * answered through the provider the default was on when the home opened: a person who signed
 * in to nanoMuse Cloud after a plan sign-in (the sign-in gives the account a fresh draft main
 * chat), then picked a Cloud model under Settings › Models, saw the row say nanoMuse Cloud
 * while the first message still went through the plan. A draft opened on a group of its own
 * (new chat in a group) is not new in this sense and keeps that group.
 *
 * The rule alone, so it can be tested without a database: which session gets its row written,
 * which open chat moves, and when nothing is (the binding already says so, which also keeps the
 * picker → `followPick` → main chat round from going on). The writes are in
 * [ModelSlots.mainChatFollows].
 */
object MainChatFollow {
    /** What the chat slot was last set to: the group that leads with the pick, and the pick. */
    data class Pick(val groupId: String, val entryId: String)

    /** The session whose row is written: the persisted main chat, never a draft (it has no row yet). */
    fun target(mainSessionId: String?): String? = mainSessionId?.takeIf { !MainChat.isDraftId(it) }

    /**
     * The binding the main chat gets: the group, leading with the pick, in the shape the chat
     * view-model writes for a group pick (`restoreFromBinding` reads it back).
     */
    fun binding(pick: Pick): String =
        """{"type":"group","groupId":"${pick.groupId}","lastEntryId":"${pick.entryId}"}"""

    /** Nothing to write when the session's binding already says what the slot says. */
    fun alreadySays(current: String?, pick: Pick): Boolean = current == binding(pick)

    /**
     * Whether a chat that is open now needs to move: it is the main chat, or a chat with no row
     * yet that was not opened on a group of its own ([isNew]), and it shows another entry, or
     * the same entry through another group. A side chat with a row keeps its model.
     */
    fun shouldMove(isMain: Boolean, activeEntryId: String?, selectedGroupId: String?, pick: Pick, isNew: Boolean = false): Boolean =
        (isMain || isNew) && !(activeEntryId == pick.entryId && selectedGroupId == pick.groupId)

    /**
     * Whether a group picked in a chat's picker becomes the default as it is: it is the
     * person's own, named by them ([groupName] is not the pick's provider label,
     * [providerLabel]) or holding more than one provider ([memberProviderIds], one per member;
     * an unknown member counts as another provider). A group named after the provider is ours
     * and may be led with the pick instead.
     */
    fun keepAsIs(groupName: String, providerLabel: String, memberProviderIds: List<String?>): Boolean =
        groupName != providerLabel || memberProviderIds.toSet().size > 1
}
