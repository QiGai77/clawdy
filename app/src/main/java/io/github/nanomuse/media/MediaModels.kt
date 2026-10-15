package io.github.nanomuse.media

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.cloud.NanoMuseCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The three models nanoMuse runs on. Muse ships with all of them built in; here each is one of
 * the user's own providers, chosen in Settings → Image & video models:
 *
 *  - the **chat model** — OpenMinis' default model group, unchanged;
 *  - the **image model** — the avatar's four candidates and poses, and pictures the user asks
 *    for in the chat ([ImageGen]; any provider with the OpenAI images API, or Alibaba Cloud
 *    Model Studio's qwen-image);
 *  - the **video model** — the animated avatar and short clips the user asks for ([VideoGen];
 *    Model Studio's asynchronous video API, Wan 2.2 Flash by default: ¥0.10 a second at 480P).
 *
 * The recommended setup is one Model Studio key for all three — chat, qwen-image-3.0,
 * wan2.2-i2v-flash — but each model is chosen on its own, so any part can come from a different
 * provider. The image model is picked automatically from the first eligible provider so an
 * avatar change works out of the box; the video model follows the image provider when that one
 * is Model Studio, and can be switched off or moved to another Model Studio provider.
 * [promptParagraph] tells the agent what is and is not set, so it can explain and point at the
 * setting instead of pretending.
 */
object MediaModels {
    private const val PREFS = "nanomuse"
    private const val KEY_VIDEO_INSTANCE = "media.video.provider_id"
    private const val KEY_VIDEO_MODEL = "media.video.model"
    private const val KEY_ANIMATE = "media.animate_avatar"
    const val DEFAULT_VIDEO_MODEL = "wan2.2-i2v-flash"
    const val DEEP_LINK = "minis://settings/media"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── image ─────────────────────────────────────────────────────────────

    /** The image model when it is usable: a provider with a key *and* a model name (the catalogue may not know one). */
    fun imageEndpoint(context: Context): ImageGen.Endpoint? = ImageGen.endpoint(context)?.takeIf { it.model.isNotBlank() }

    // ── video ─────────────────────────────────────────────────────────────

    /**
     * Providers whose host speaks Model Studio's asynchronous video API: Model Studio itself, and
     * nanoMuse Cloud, which relays those same paths (the account's own key is used).
     */
    fun eligibleVideoInstances(context: Context): List<ProviderInstance> {
        val app = context.applicationContext as? MinisApp ?: return emptyList()
        val repo = app.providerRepositoryOrNull ?: return emptyList()
        val cloudId = NanoMuseCloud.instance(context)?.id
        return repo.config.value.instances.filter { inst ->
            inst.isEnabled && inst.credentialType == ProviderCredential.apiKey &&
                (VideoGen.speaksDashScope(ImageGen.baseUrlOf(inst)) || inst.id == cloudId) &&
                // C11: the catalogue's word on the vendor (Bailian has video models; a gateway on the same host is not gated)
                io.github.nanomuse.cloud.Capabilities.allows(context, inst, io.github.nanomuse.cloud.ProviderCatalogue.VIDEO)
        }
    }

    /** No configured provider can make clips: the one sentence that says so (C11), or null when one can. */
    fun videoUnavailableLine(context: Context): String? =
        if (eligibleVideoInstances(context).isEmpty()) io.github.nanomuse.cloud.Capabilities.unavailableLine(context, io.github.nanomuse.cloud.ProviderCatalogue.VIDEO) else null

    /** Stored instance id meaning "the user switched the video model off". */
    private const val VIDEO_OFF = ""

    /** True when the person switched the video model off (a choice, unlike "never chosen"). */
    fun videoSwitchedOff(context: Context): Boolean = prefs(context).getString(KEY_VIDEO_INSTANCE, null) == VIDEO_OFF

    /** The video model nanoMuse would pick for [inst]: the catalogue's `defaults.video` for its vendor, else Wan 2.2 Flash. */
    fun defaultVideoModel(context: Context, inst: ProviderInstance): String =
        io.github.nanomuse.models.ModelSlots.defaultOf(context, inst, io.github.nanomuse.models.ModelSlots.Slot.VIDEO) ?: DEFAULT_VIDEO_MODEL

    /**
     * The video model, or null when there is none. The one chosen (Settings → Models, or →
     * Image & video models); with nothing chosen, in the Models page's order (0.1.41,
     * [io.github.nanomuse.models.SlotOrder]): the chat provider's own video model when the
     * chat provider is the person's own and makes clips, else nanoMuse Cloud's when signed
     * in, else the first own provider that can. Off stays off. [automatic] leaves the choice
     * (and Off) aside and answers what that order gives now, for the picker's *Automatic* row.
     */
    fun videoEndpoint(context: Context, automatic: Boolean = false): VideoGen.Endpoint? {
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val p = prefs(context)
        val eligible = eligibleVideoInstances(context)
        val saved = if (automatic) null else p.getString(KEY_VIDEO_INSTANCE, null)
        val cloudId = NanoMuseCloud.instance(context)?.id
        val inst = when (saved) {
            VIDEO_OFF -> return null
            null -> io.github.nanomuse.models.SlotOrder.resolve(
                chosen = null,
                chatProvider = io.github.nanomuse.models.ModelSlots.ownChatInstance(context)?.takeIf { own -> eligible.any { it.id == own.id } },
                cloud = eligible.firstOrNull { it.id == cloudId },
                firstOwn = eligible.firstOrNull { it.id != cloudId },
            )?.value
            else -> eligible.firstOrNull { it.id == saved }
        } ?: return null
        val model = p.getString(KEY_VIDEO_MODEL, null)?.takeIf { it.isNotBlank() && saved == inst.id } ?: defaultVideoModel(context, inst)
        val key = repo.usableApiKey(inst) ?: return null
        return VideoGen.Endpoint(inst, key, model)
    }

    /** [instanceId] null switches the video model off; it is remembered, unlike "never chosen". */
    fun saveVideo(context: Context, instanceId: String?, model: String) {
        prefs(context).edit()
            .putString(KEY_VIDEO_INSTANCE, instanceId ?: VIDEO_OFF)
            .putString(KEY_VIDEO_MODEL, model.trim())
            .apply()
    }

    /** True when the person chose a video model or switched it off; false when the slot follows the automatic order. */
    fun videoChosen(context: Context): Boolean = prefs(context).getString(KEY_VIDEO_INSTANCE, null) != null

    /** Forgets the choice (a model, or Off): the slot follows the automatic order again. Nothing else moves. */
    fun clearVideo(context: Context) {
        prefs(context).edit().remove(KEY_VIDEO_INSTANCE).remove(KEY_VIDEO_MODEL).apply()
    }

    // ── which video models the key can use ────────────────────────────────

    private const val KEY_VIDEO_AVAILABLE = "media.video.available."
    private const val KEY_VIDEO_CHECKED = "media.video.checked."
    private const val VIDEO_CHECK_TTL_MS = 24 * 60 * 60_000L

    /**
     * The video models of [inst] that answered the last check, recommended first; null when it
     * has never been checked (the page then runs [checkVideoModels]). Model Studio does not list
     * video models on `/models`, so this is what "which models does this key have" means for
     * video: the known candidates, each probed once and remembered for a day.
     */
    fun availableVideoModels(context: Context, inst: ProviderInstance): List<String>? {
        val raw = prefs(context).getString(KEY_VIDEO_AVAILABLE + inst.id, null) ?: return null
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun videoCheckIsFresh(context: Context, inst: ProviderInstance): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_VIDEO_CHECKED + inst.id, 0L) < VIDEO_CHECK_TTL_MS

    /**
     * Asks the host which of the known video models exist for this key — plus anything on the
     * provider's own list that is named like a video model — and remembers the answer. Returns
     * the models found, or null when the host could not be reached at all.
     */
    suspend fun checkVideoModels(context: Context, inst: ProviderInstance): List<String>? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? MinisApp ?: return@withContext null
        val repo = app.providerRepositoryOrNull ?: return@withContext null
        val key = repo.usableApiKey(inst) ?: return@withContext null
        val host = VideoGen.Endpoint(inst, key, "").host
        val listed = repo.config.value.modelEntries
            .filter { it.providerInstanceId == inst.id && !it.isHidden && VideoGen.looksLikeVideoModel(it.model.id) }
            .map { it.model.id }
        val candidates = (VideoGen.KNOWN_DASHSCOPE_MODELS + listed).distinct()
        var reached = false
        val found = candidates.filter { model ->
            val ok = VideoGen.probe(host, key, model)
            if (ok != null) reached = true
            ok == true
        }
        if (!reached) return@withContext null
        prefs(context).edit()
            .putString(KEY_VIDEO_AVAILABLE + inst.id, JSONArray(found).toString())
            .putLong(KEY_VIDEO_CHECKED + inst.id, System.currentTimeMillis())
            .apply()
        found
    }

    /** Whether a new face is animated after its poses (four short clips through the video model). */
    fun animateAvatar(context: Context): Boolean = prefs(context).getBoolean(KEY_ANIMATE, true)
    fun setAnimateAvatar(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ANIMATE, on).apply() }

    // ── what the agent is told ────────────────────────────────────────────

    /** One line per model, for the settings page and the prompt: the model, "not set", or — when no provider can (C11) — the one sentence that says so. */
    fun imageLine(context: Context): String = imageEndpoint(context)?.let { "${it.model} · ${it.label}" }
        ?: ImageGen.unavailableLine(context)
        ?: context.getString(R.string.nm_media_not_set)

    fun videoLine(context: Context): String = videoEndpoint(context)?.let { "${it.model} · ${it.label}" }
        ?: videoUnavailableLine(context)
        ?: context.getString(R.string.nm_media_not_set)

    /**
     * The system-prompt paragraph. English on purpose (the prompt is), short, and honest about
     * what is missing so the agent explains and links the setting instead of inventing a picture.
     */
    fun promptParagraph(context: Context): String {
        val image = imageEndpoint(context)
        val video = videoEndpoint(context)
        return buildString {
            append("Media models. Unlike Muse, whose image and video models are built in, nanoMuse runs on three models the user configures: ")
            append("the chat model (you), an image model (avatar changes, pictures the user asks for) and a video model (the animated avatar, short clips). ")
            append("Image model: ").append(image?.let { "${it.model} via ${it.label}" } ?: "NOT SET").append(". ")
            append("Video model: ").append(video?.let { "${it.model} via ${it.label}" } ?: "NOT SET").append(".\n")
            append("- To make a picture the user asks for, run `nanomuse-media image --prompt \"...\" [--from <image path>]`; ")
            append("for a short clip, `nanomuse-media video --prompt \"...\" [--from <image path>] [--seconds 4-15]` (takes 1-5 minutes; say so first). ")
            append("Both print JSON with a `markdown` field — put that line in your reply so the file shows inline. `nanomuse-media status` prints what is configured.\n")
            append("- Avatar changes are handled by the app itself when the user writes \"change your avatar to ...\"; you only need to explain when it cannot work.\n")
            if (image == null) {
                append("- No image model is set: if the user asks to change your avatar or for a picture, say plainly that pictures need a provider with image models ")
                append("(Alibaba Cloud Bailian, OpenAI with an API key, Gemini or OpenRouter; a ChatGPT plan signed in through Codex, DeepSeek, Claude, Kimi and Groq have none), and give the link [Image & video models](")
                append(DEEP_LINK).append(") — it opens the setting. Never pretend to have drawn something.\n")
            }
            if (video == null) {
                append("- No video model is set: the avatar stays as still pictures, and clips cannot be made. If asked, explain that clips need a provider with video models ")
                append("(Alibaba Cloud Bailian: a Wan video model such as wan2.2-i2v-flash, picked in the setting) and give the link [Image & video models](").append(DEEP_LINK).append(").")
            }
        }.trimEnd()
    }

    /** The one-turn addendum when an avatar change was asked for but cannot be drawn. */
    fun missingImageAddendum(): String =
        "The user just asked you to change your avatar, but no image model is configured, so the app could not start the change. " +
            "Answer in the user's language: say that changing your look needs a provider with image models " +
            "(Alibaba Cloud Bailian, OpenAI with an API key, Gemini or OpenRouter — not a ChatGPT plan signed in through Codex, nor DeepSeek, Claude, Kimi or Groq), " +
            "that nanoMuse ships no image model of its own, so this is something they set up themselves, and give the link [Image & video models]($DEEP_LINK) to open the setting. " +
            "Keep it to a few sentences and do not describe or invent a new look."
}
