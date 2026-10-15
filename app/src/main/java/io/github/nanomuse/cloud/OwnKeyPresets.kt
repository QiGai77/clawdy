package io.github.nanomuse.cloud

import android.content.Context
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate

/**
 * "Use your own key": the provider form pre-filled for a vendor of the catalogue
 * ([ProviderCatalogue], contract C11), so that the way on from a spent allowance is one tap
 * plus a paste — or one tap plus a sign-in. Only public endpoints are written here; the key
 * is the person's own.
 *
 * The form's pre-fill hook is the voice-template slot (name, protocol, base URL, `/v1`), so a
 * preset is expressed as one — with no models of its own, the vendor's list is fetched live.
 * Every `protocol: openai` vendor goes through the OpenAI-compatible form (OpenRouter and xAI
 * through upstream's own types, which know their hosts); Anthropic and Gemini through
 * upstream's provider types for them; a Kimi sign-in through upstream's `kimiCode` device
 * flow. The deep link is `minis://settings/providers/add?preset=<id>`, and
 * `?preset=<id>:oauth` opens the form on the vendor's sign-in instead of the key field —
 * `OpenAIOAuthManager` (a ChatGPT plan), `ClaudeOAuthManager`, `OpenRouterOAuthManager`,
 * `KimiOAuthManager` — the same buttons `AddProviderScreen` has always had.
 */
object OwnKeyPresets {
    /** Alibaba Cloud Bailian (阿里云百炼): OpenAI-compatible, one key for chat, the screen, pictures and clips. */
    const val BAILIAN = ProviderCatalogue.BAILIAN

    /** OpenRouter: one account, one key, most models, pay as you go; signs up anywhere. */
    const val OPENROUTER = ProviderCatalogue.OPENROUTER

    /** `?preset=<id>:oauth` — the form opens on the vendor's sign-in rather than the key field. */
    const val SIGN_IN_SUFFIX = ":oauth"

    private const val DEEP_LINK = "minis://settings/providers/add?preset="

    /** The in-app link that opens "add a provider" pre-filled for [id]; with [signIn], on its sign-in. */
    fun deepLink(id: String, signIn: Boolean = false): String = DEEP_LINK + id + if (signIn) SIGN_IN_SUFFIX else ""

    /** The catalogue id in a `preset` value (`openai:oauth` → `openai`). */
    fun id(preset: String?): String? = preset?.trim()?.lowercase()?.substringBefore(':')?.takeIf { it.isNotEmpty() }

    /** The `preset` asks for the vendor's sign-in rather than a pasted key. */
    fun wantsSignIn(preset: String?): Boolean = preset?.trim()?.lowercase()?.endsWith(SIGN_IN_SUFFIX) == true

    /**
     * The form opens on the vendor's sign-in: asked for, the vendor has one, and this build can
     * run it (the Claude sign-in needs an identifier the public build does not carry, #373).
     */
    fun signIn(context: Context, preset: String?): Boolean {
        if (!wantsSignIn(preset)) return false
        val auth = ProviderCatalogue.byId(context, id(preset))?.signIn ?: return false
        return auth != ProviderCatalogue.AUTH_CLAUDE || ClaudeSignIn.available
    }

    /** The form's pre-fill for a `preset` value, or null when it names no vendor of the catalogue. */
    fun template(context: Context, preset: String?): VoiceProviderTemplate? {
        val provider = ProviderCatalogue.byId(context, id(preset)) ?: return null
        return template(provider, Region.mainland(context), ProviderCatalogue.chinese(context), signIn(context, preset))
    }

    /** The upstream provider type a catalogue vendor is added as. */
    fun providerType(provider: CatalogueProvider): ProviderType = when (provider.id) {
        ProviderCatalogue.OPENROUTER -> ProviderType.openRouter
        ProviderCatalogue.XAI -> ProviderType.xAI
        else -> when (provider.protocol) {
            "anthropic" -> ProviderType.anthropic
            "gemini" -> ProviderType.gemini
            else -> ProviderType.openAI
        }
    }

    /** The type the vendor's sign-in runs under: Kimi's device code is upstream's `kimiCode`; the rest are their key type. */
    fun signInProviderType(provider: CatalogueProvider): ProviderType =
        if (provider.signIn == ProviderCatalogue.AUTH_KIMI) ProviderType.kimiCode else providerType(provider)

    /**
     * The template for [provider]: its endpoint for the region, split into the base and the
     * `/v1` toggle the form has (`…/compatible-mode/v1` → `…/compatible-mode` + `/v1` on;
     * `…/api/paas/v4` → as is, toggle off). Gemini's catalogue endpoint is Google's
     * OpenAI-compatible layer; upstream's Gemini type speaks the native API at `/v1beta`, so
     * the form opens on that.
     */
    fun template(provider: CatalogueProvider, mainland: Boolean, chinese: Boolean, signIn: Boolean = false): VoiceProviderTemplate {
        val type = if (signIn) signInProviderType(provider) else providerType(provider)
        val url = provider.baseUrl(mainland).trimEnd('/')
        val (base, appendV1) = when {
            type == ProviderType.gemini -> url.removeSuffix("/openai") to false
            // upstream's Anthropic type adds /v1 itself (…/v1/messages), as its own default form does
            type == ProviderType.anthropic -> url to true
            url.endsWith("/v1") -> url.removeSuffix("/v1") to true
            url.isEmpty() -> "" to true
            else -> url to false
        }
        return VoiceProviderTemplate(
            id = provider.id,
            name = provider.displayName(chinese),
            providerType = type,
            baseURL = base,
            appendV1 = appendV1,
            capability = VoiceProviderTemplate.Capability.BOTH,
            baseURLMarkers = emptyList(),
            mockModels = emptyList(),
        )
    }
}
