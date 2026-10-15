package com.openminis.app.clawdy

import android.content.Context

/**
 * Clawdy 0.3.0 persona bits that are not SOUL.md: the 对话方式 (reply style) picked on the 人设 page,
 * and the identity fence for the assembled system prompt.
 *
 * The device test of 0.2.0 had the agent call itself "nanoMuse 里的 AI 助手 Clawdy": the upstream
 * prompt fragments (Hands ladder, risk policy, tool notes) still say "nanoMuse". [finalizePrompt]
 * renames those mentions and [IDENTITY_FENCE] (in SoulStore's identity section) states plainly that
 * the agent is Clawdy and nothing else.
 */
object ClawdyPersona {
    const val KEY_STYLE = "chat_style"
    const val KEY_STYLE_CUSTOM = "chat_style_custom"

    data class Style(val id: String, val label: String, val blurb: String, val prompt: String)

    val STYLES = listOf(
        Style("default", "默认", "自然、友好，按问题需要决定长短", ""),
        Style("concise", "简洁", "先给结论，少寒暄，能一句话就一句话",
            "Be concise: lead with the answer, skip pleasantries and filler, use short sentences and as few words as the question allows; expand only when asked."),
        Style("warm", "温柔陪伴", "耐心、体贴，多一点关心和鼓励",
            "Be warm and caring: patient, gentle and encouraging, acknowledge how the user feels before solving the problem, and keep a soft, companionable tone — without becoming verbose."),
        Style("pro", "专业严谨", "条理清楚，讲依据，说明不确定的地方",
            "Be professional and rigorous: structured answers, precise terminology, state assumptions and sources, flag uncertainty explicitly, avoid casual jokes."),
        Style("lively", "活泼", "轻松幽默，有活力",
            "Be lively and playful: upbeat, light humour and a casual voice, while staying accurate and useful. No emoji."),
        Style("custom", "自定义", "用你自己写的要求", ""),
    )

    fun styleId(context: Context): String = ClawdyPrefs.str(context, KEY_STYLE) ?: "default"
    fun setStyle(context: Context, id: String) = ClawdyPrefs.setStr(context, KEY_STYLE, id)
    fun customStyle(context: Context): String = ClawdyPrefs.str(context, KEY_STYLE_CUSTOM).orEmpty()
    fun setCustomStyle(context: Context, text: String) = ClawdyPrefs.setStr(context, KEY_STYLE_CUSTOM, text.take(800))

    /** The reply-style paragraph for the system prompt, or "" for 默认. */
    fun stylePrompt(context: Context): String {
        val id = styleId(context)
        val text = if (id == "custom") customStyle(context).trim() else STYLES.firstOrNull { it.id == id }?.prompt.orEmpty()
        if (text.isBlank()) return ""
        return "\n\nConversation style (chosen by the user in 人设 → 对话方式; apply to every reply unless the user asks otherwise):\n$text"
    }

    const val IDENTITY_FENCE =
        "Identity: you are {name}, the Clawdy app's own agent. Clawdy is the product's name; never call yourself an assistant \"in\" or \"of\" nanoMuse, OpenMinis, Minis or any other app, and do not mention those names when introducing yourself. If asked which model you run on, say you do not see the model name and point to 设置 → 模型. "

    /** Renames upstream product mentions in the assembled system prompt. */
    fun finalizePrompt(prompt: String): String = prompt
        .replace("nanoMuse Cloud", "Clawdy cloud (not available)")
        .replace("nanoMuse", "Clawdy")
        .replace("OpenMinis", "Clawdy")
}
