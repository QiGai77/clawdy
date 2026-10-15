package io.github.nanomuse.cloud

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.R

/**
 * Whether this build can sign in with a Claude account.
 *
 * Anthropic serves a Claude sign-in (OAuth) only when every request opens with an identifier
 * that is not in this repository: `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` is read at build time
 * from `app/provider-customization.properties`, and the public build leaves it empty.
 * Upstream's getter throws on the empty value, and the chat's send path read it outside any
 * handler, so a Claude sign-in on the public build took the app down with the first message
 * (#373). The app reads the value through here instead: a build without it does not offer
 * the Claude sign-in and lists Anthropic by API key only, and a sign-in saved by an earlier
 * build answers each send with one sentence.
 */
object ClaudeSignIn {
    /** True when the build carries the identifier; false on the public build. */
    val available: Boolean get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.isNotEmpty()

    /** The identifier, or an empty string on a build without one. Never throws. */
    val identifier: String get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT

    /**
     * The detail the Anthropic provider refuses a send with when the build has no identifier;
     * [userText] turns it into the sentence in the person's language.
     */
    const val MISSING = "This build cannot use a Claude sign-in; add Anthropic with an API key instead."

    /** [error] as the person reads it: the localized sentence for the missing-identifier refusal, else [error] itself. */
    fun userText(context: Context, error: String): String =
        if (error.contains(MISSING)) context.getString(R.string.nm_claude_sign_in_unavailable) else error
}
