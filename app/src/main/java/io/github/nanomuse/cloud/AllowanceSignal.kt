package io.github.nanomuse.cloud

import android.content.Context
import org.json.JSONObject

/**
 * The relay's refusals, caught on their way through the model client. nanoMuse Cloud refuses
 * a model call with a stable `error.code` (docs/cloud.md): `allowance_exhausted` with what is
 * left, the pool, the invite link, what an invitation adds to each side, the guide for one's
 * own key and — relay 0.21 — the ways-on card as data (`guidance`); `daily_cap`; and the rest
 * ([RelayRefusal]: 413 `too_large`, `too_many_in_flight`, `provider_busy`, the operator's
 * switches …). The generic client maps every 429 to "rate limited" and every 401/403 to "bad
 * key", so the body would be lost; this keeps the last one for a few seconds so the chat can
 * show the ways on, or one plain sentence, instead of a bare error.
 */
object AllowanceSignal {
    /** What the relay said, in yuan. */
    data class Exhausted(
        val leftCny: Double,
        val grantCny: Double,
        val inviteUrl: String,
        /** What a sign-up with the link adds to the inviter and to the new account; 0 = unknown. */
        val inviteBonusCny: Double,
        val inviteeBonusCny: Double,
        val ownKeyDocs: String,
        /** True for the relay's `daily_cap`: today's share is spent, the pool is not; it comes back with the day. */
        val dailyCap: Boolean = false,
        /** Relay 0.22: the free allowance is paused by the operator — not used up; the card leads with that. */
        val paused: Boolean = false,
        /** Relay 0.21: the ways on as the relay lists them (contract C11); null from an older relay. */
        val guidance: Guidance? = null,
        val at: Long = System.currentTimeMillis(),
    )

    @Volatile private var last: Exhausted? = null
    @Volatile private var lastRefusal: RelayRefusal.Refusal? = null
    @Volatile private var lastRefusalAt: Long = 0

    /**
     * Call for every failed HTTP reply of a model request; only the relay's refusals are kept
     * — the allowance ones as [Exhausted] for the card, every other as a [RelayRefusal.Refusal]
     * for the sentence. [fromRelay] says the call went to the relay's host.
     */
    fun noteHttpError(status: Int, body: String?, fromRelay: Boolean = false) {
        val refusal = RelayRefusal.parse(status, body, fromRelay) ?: return
        if (refusal.isAllowance) {
            val err = RelayRefusal.errorObject(body) ?: JSONObject()
            last = Exhausted(
                dailyCap = refusal.kind == RelayRefusal.Kind.DAILY_CAP,
                paused = refusal.kind == RelayRefusal.Kind.ALLOWANCE_PAUSED,
                leftCny = err.optDouble("left", 0.0),
                grantCny = err.optDouble("grant", 0.0),
                inviteUrl = err.optString("invite_url", ""),
                inviteBonusCny = err.optDouble("invite_bonus_cny", 0.0),
                inviteeBonusCny = err.optDouble("invitee_bonus_cny", 0.0),
                ownKeyDocs = err.optString("own_key_docs", ""),
                guidance = Guidance.parse(err.optJSONObject("guidance")),
            )
            return
        }
        lastRefusal = refusal
        lastRefusalAt = System.currentTimeMillis()
    }

    /** The exhaustion behind the error the chat is about to show, if it was the relay's; consumed. */
    fun takeFresh(maxAgeMs: Long = 30_000): Exhausted? {
        val e = last ?: return null
        last = null
        return e.takeIf { System.currentTimeMillis() - it.at <= maxAgeMs }
    }

    /** Any other refusal of the relay's behind the error the chat is about to show; consumed. */
    fun takeFreshRefusal(maxAgeMs: Long = 30_000): RelayRefusal.Refusal? {
        val r = lastRefusal ?: return null
        lastRefusal = null
        return r.takeIf { System.currentTimeMillis() - lastRefusalAt <= maxAgeMs }
    }

    /** The same, from the cached account (the account page shows it whenever the pool is spent). */
    fun fromAccount(context: Context): Exhausted? {
        val a = NanoMuseCloud.account(context) ?: return null
        if (!a.exhausted) return null
        return Exhausted(
            leftCny = a.leftCny.coerceAtLeast(0.0),
            grantCny = a.grantCny,
            inviteUrl = a.inviteUrl,
            inviteBonusCny = a.inviteBonusCny,
            inviteeBonusCny = a.inviteeBonusCny,
            ownKeyDocs = a.ownKeyDocs,
            guidance = NanoMuseCloud.guidance(context),
        )
    }
}
