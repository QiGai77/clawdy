package io.github.nanomuse.chat

/**
 * Whether a model most likely takes pictures, judged from its name alone.
 *
 * Used only when nobody said: the provider's `/models` reply had no
 * `architecture.input_modalities` and models.dev has no entry for the id (a
 * model typed in by hand, a brand-new release, a relay that lists ids only).
 * Until now "unknown" was read as "cannot see", so the system prompt told a
 * perfectly sighted `qwen3.8-27b` that it could not process images and every
 * attached picture was swapped for a text placeholder — the model then told
 * the person it did not support image input. Declared modalities always win
 * over this guess; a wrong guess here costs one clear error from the
 * provider ("image_url not supported"), a wrong "no" costs a silent downgrade.
 */
object VisionFamilies {

    /** Names that mean text only however the rest of the name reads. */
    private val textOnly = listOf(
        "embed", "whisper", "tts", "rerank", "moderation", "transcri", "speech",
        "-coder", "codestral", "deepseek-chat", "deepseek-reasoner", "deepseek-v3", "deepseek-r1",
        "qwen2.5-", "qwen2-", "qwen1.5", "qwq", "qwen-long", "qwen-math", "qwen-coder", "qwen3-coder", "qwen3-235b", "qwen3-32b", "qwen3-30b", "qwen3-14b", "qwen3-8b", "qwen3-4b",
        "llama-3", "llama3", "mistral-7b", "mixtral", "phi-", "gemma-2", "gemma2", "glm-4-", "glm-4.5-air", "glm-4.6", "glm-4.7",
        "text-", "davinci", "babbage", "kimi-k2-", "kimi-k2.1", "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k",
        "minimax-m", "minimax-text", "abab", "hunyuan-lite", "hunyuan-turbo", "ernie-speed", "ernie-lite",
    )

    /** Families that see. Checked after [textOnly], so `qwen2.5-vl` still passes via "-vl". */
    private val sees = listOf(
        "kimi-vl", "glm-4v", "glm-4.5v", "glm-4.6v", "glm-5",
        "qwen3", "qwen-max", "qwen-plus", "qwen-turbo", "qwen-flash", "qvq",
        "gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "gpt-4-turbo", "chatgpt-4o",
        "claude-3", "claude-4", "claude-opus", "claude-sonnet", "claude-haiku",
        "gemini", "gemma-3", "gemma3", "grok-2-vision", "grok-3", "grok-4",
        "llama-4", "llama4", "mistral-medium", "mistral-large-2", "mistral-small-3", "magistral",
        "minimax-h", "minimax-vl", "kimi-k2.5", "kimi-k3", "doubao-seed", "doubao-1.5-vision", "doubao-1.6", "seed-1",
        "step-1v", "step-1o", "step-3", "hunyuan-vision", "hunyuan-t1", "hunyuan-large-vision", "ernie-4.5",
    )

    /** OpenAI's o-series (`o1`, `o3`, `o4-mini`), as a whole token so "moonshot-v1" or "glm-4-0414" do not match. */
    private val oSeries = Regex("(^|[^a-z0-9])o[134](?=$|[^a-z0-9])")

    /** `v4`, `v4.1`, `v5` … in a DeepSeek id; what it says decides whether the model sees. */
    private val deepSeekVersion = Regex("v(\\d+)(?:\\.(\\d+))?")

    /**
     * DeepSeek's rule (contract C4): text only unless the id names a version from `v4.1` on,
     * or says `vision` / `ocr` — `deepseek-v4.1-flash` sees, `deepseek-v4-pro` and
     * `deepseek-chat` do not. Null when the id is not DeepSeek's.
     */
    fun deepSeekSees(hay: String): Boolean? {
        if (!hay.contains("deepseek")) return null
        if (hay.contains("vision") || hay.contains("ocr")) return true
        val m = deepSeekVersion.find(hay) ?: return false
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return major > 4 || (major == 4 && minor >= 1)
    }

    fun looksLikeItSees(id: String, displayName: String = ""): Boolean {
        val hay = "$id $displayName".lowercase()
        // "-vl" / "vision" / "omni" in the name settle it before the text-only list
        // (qwen2.5-vl, deepseek-vl, minimax-vl).
        if (listOf("-vl", "vl-", "vision", "omni", "llava", "pixtral", "internvl").any { hay.contains(it) }) return true
        deepSeekSees(hay)?.let { return it }
        if (textOnly.any { hay.contains(it) }) return false
        return sees.any { hay.contains(it) } || oSeries.containsMatchIn(hay)
    }
}
