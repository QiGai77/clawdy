package io.github.nanomuse.cloud

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance

/**
 * The rule of contract C11: what the person's configured providers can do decides what the app
 * offers. Chat needs `chat`; the hands need `vision`; pictures need `image`; clips need `video`.
 * A feature no configured provider has the capability for is unavailable with one sentence
 * ([unavailableLine]), never a raw error; the pickers list only what has the capability.
 *
 * The catalogue ([ProviderCatalogue]) says what a known vendor covers — and what its sign-in
 * covers when that is less: a ChatGPT plan through the Codex OAuth is chat and vision only.
 * An endpoint the catalogue does not know (a gateway, a relay of one's own, nanoMuse Cloud)
 * is not gated here; its models' own modalities decide, as before.
 */
object Capabilities {
    /** What [inst] can do by the catalogue, or null when the catalogue does not know it. */
    fun of(context: Context, inst: ProviderInstance): Set<String>? {
        val list = ProviderCatalogue.load(context)
        val provider = ProviderCatalogue.match(list, inst) ?: return null
        if (provider.userCapabilities) return null
        return provider.capabilitiesFor(ProviderCatalogue.authOf(inst, provider))
    }

    /** True unless the catalogue knows [inst] and says it lacks [capability]. */
    fun allows(context: Context, inst: ProviderInstance, capability: String): Boolean =
        of(context, inst)?.contains(capability) ?: true

    /** The one sentence for a feature none of the configured providers can serve. */
    fun unavailableLine(context: Context, capability: String): String = context.getString(
        when (capability) {
            ProviderCatalogue.IMAGE -> R.string.nm_cap_pictures_unavailable
            ProviderCatalogue.VIDEO -> R.string.nm_cap_clips_unavailable
            ProviderCatalogue.VISION -> R.string.nm_cap_hands_unavailable
            else -> R.string.nm_cap_chat_unavailable
        },
    )

    /** "chat · screen · pictures · clips" — what a vendor covers, in the UI's words and the catalogue's order. */
    fun covers(context: Context, capabilities: Set<String>): String =
        listOf(
            ProviderCatalogue.CHAT to R.string.nm_cap_chat,
            ProviderCatalogue.VISION to R.string.nm_cap_screen,
            ProviderCatalogue.IMAGE to R.string.nm_cap_pictures,
            ProviderCatalogue.VIDEO to R.string.nm_cap_clips,
        ).filter { it.first in capabilities }.joinToString(" · ") { context.getString(it.second) }
}
