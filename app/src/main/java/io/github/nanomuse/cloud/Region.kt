package io.github.nanomuse.cloud

import android.content.Context
import java.util.Locale

/**
 * Which way on to put first when the free allowance is spent (contract C5).
 *
 * 阿里云百炼 only signs up accounts from mainland China; outside, OpenRouter is the one to
 * show first — one account, one key, pay as you go, and a sign-in without a paste. "Mainland"
 * is read from what the app already knows, nothing is looked up: the UI language is
 * simplified Chinese (zh-Hans, or zh-CN), the account signed in with a phone number (codes
 * reach mainland numbers only), or the relay said `region: "cn"` about the account.
 */
object Region {
    const val CN = "cn"

    /** True when 阿里云百炼 is the natural first choice for this person. */
    fun mainland(context: Context): Boolean {
        val account = NanoMuseCloud.account(context)
        if (account != null) {
            if (account.region.equals(CN, ignoreCase = true)) return true
            if (account.channel == "phone") return true
            if (account.region.isNotBlank()) return false
        }
        return simplifiedChinese(context.resources.configuration.locales[0] ?: Locale.getDefault())
    }

    /** zh-Hans in any country, or zh-CN / zh-SG with no script; zh-Hant, zh-TW and zh-HK are not it. */
    fun simplifiedChinese(locale: Locale): Boolean {
        if (locale.language != "zh") return false
        return when (locale.script) {
            "Hans" -> true
            "Hant" -> false
            else -> locale.country.isEmpty() || locale.country == "CN" || locale.country == "SG"
        }
    }
}
