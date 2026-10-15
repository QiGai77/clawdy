package io.github.nanomuse.models

/**
 * The order a slot is resolved in when the person has not chosen (0.1.41 "Choice", the shared
 * contract, section 3), and the model a provider is used with for a slot (section 2). Pure
 * Kotlin so the unit tests run it without a device; [ModelSlots] feeds it the app's state.
 *
 * - chat: signed in, the relay's recommended chat model; else the first own provider's `defaults.chat`.
 * - hands: the chat provider's `defaults.hands` when the chat provider is an own provider with
 *   `vision`; else the relay's model for the screen when signed in; else the first own model that sees.
 * - image / video: the chat provider's `defaults.image` / `defaults.video` when it has the
 *   capability; else the relay's model when signed in; else the first own provider with the capability.
 *
 * An explicit choice always wins. Cloud never jumps ahead of an own provider the person chose.
 */
object SlotOrder {
    /** How a value was arrived at, for the pages that say so. */
    enum class Why { CHOSEN, CHAT_PROVIDER, CLOUD, FIRST_OWN }

    data class Pick<T>(val value: T, val why: Why)

    /**
     * The first of the four rungs that has a value: what the person chose, the chat provider's
     * own model for the slot, nanoMuse Cloud's (null when signed out or the relay has none),
     * the first own provider's. Null when nothing can serve the slot.
     */
    fun <T : Any> resolve(chosen: T?, chatProvider: T?, cloud: T?, firstOwn: T?): Pick<T>? = when {
        chosen != null -> Pick(chosen, Why.CHOSEN)
        chatProvider != null -> Pick(chatProvider, Why.CHAT_PROVIDER)
        cloud != null -> Pick(cloud, Why.CLOUD)
        firstOwn != null -> Pick(firstOwn, Why.FIRST_OWN)
        else -> null
    }

    /**
     * The model a provider serves a slot with: the catalogue's `defaults.<slot>` when the
     * provider lists it (or lists nothing yet, so the catalogue's word stands), else the first
     * model of its list that has the capability; null when it has neither.
     */
    fun defaultModel(default: String?, capable: List<String>): String? {
        val wanted = default?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            capable.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
            if (capable.isEmpty()) return wanted
        }
        return capable.firstOrNull()
    }

    /**
     * The members of the group new chats start from, once [entryId] is the pick: the pick first,
     * the rest of the group in its order. Unchanged when the pick already leads.
     */
    fun leadWith(members: List<String>, entryId: String): List<String> =
        if (members.firstOrNull() == entryId) members else listOf(entryId) + members.filter { it != entryId }
}
