package io.github.nanomuse.chat

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.models.ModelSlots

/**
 * "Use nanoMuse Cloud this time" (0.1.41 "Choice", contract section 4). A turn on a model of
 * the person's own that failed never falls back to the relay by itself: that would spend the
 * allowance without a word and hide a key that is wrong. The rejection card offers one button
 * instead, shown only when signed in and the turn ran on a provider that is not the relay; it
 * retries that one turn on the relay's recommended chat model and changes no slot.
 */
object CloudRetry {
    /** Whether the card should offer the button for a turn that ran on [activeEntryId]'s provider. */
    fun offered(context: Context, activeEntryId: String?): Boolean {
        if (!NanoMuseCloud.isSignedIn(context)) return false
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return false
        val cfg = repo.config.value
        val entry = activeEntryId?.let { id -> cfg.modelEntries.firstOrNull { it.id == id } }
            // a chat bound to a group: the group's first member is what answered
            ?: ModelSlots.chatEntry(context)
            ?: return false
        return !NanoMuseCloud.owns(context, entry)
    }

    /**
     * The relay's recommended chat model as a provider for one turn, or null when signed out or
     * the menu is empty. The button is the person's explicit consent, so it works with Cloud
     * models switched off ([NanoMuseCloud.modelsOn]); nothing else does.
     */
    fun provider(context: Context): LLMProvider? {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
        val inst = NanoMuseCloud.instance(context) ?: return null
        val key = repo.usableApiKey(inst)?.takeIf { it.isNotBlank() } ?: return null
        val entries = ModelSlots.chatEntriesOf(context, inst)
        val wanted = NanoMuseCloud.recommendedModelId(context)
        val entry = entries.firstOrNull { it.model.id == wanted } ?: entries.firstOrNull() ?: return null
        return runCatching { ProviderFactory.create(inst, key, entry.model, context) }.getOrNull()
    }
}
