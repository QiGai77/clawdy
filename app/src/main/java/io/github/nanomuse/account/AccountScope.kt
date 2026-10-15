package io.github.nanomuse.account

/**
 * The rules of contract C12 (0.1.40) that need no phone: whose chat a session is, what the
 * lists leave out, and how an account's key is derived. [AccountData] applies them to the
 * databases and files; the unit tests exercise them here.
 *
 * Every local session has an **owner**: the key of the account that was signed in when the
 * session was first seen, or [LOCAL] for one made while nobody was. The lists, the search,
 * the home and the sync engine see the signed-in owner's sessions and no others — signed out,
 * only the local ones. Pre-existing sessions (from before 0.1.40) are claimed the same way the
 * first time the updated app runs: by the account signed in at that moment, or as local.
 */
object AccountScope {
    /** The owner of what was made with nobody signed in. */
    const val LOCAL = ""

    /** The directory name an account's put-aside files live under (`accounts/<name>/`). */
    fun dirName(account: String): String =
        if (account == LOCAL) "_local" else account.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

    /**
     * The key everything of the signed-in account is filed under: the relay's opaque id for
     * it (`/v1/me` → `account.id`), or — on a relay that gives none — a hash of the key
     * itself; [LOCAL] when there is no key (signed out). Never the number or the address.
     */
    fun key(accountId: String?, token: String?): String =
        accountId?.takeIf { it.isNotBlank() }
            ?: token?.takeIf { it.isNotBlank() }?.hashCode()?.toString()
            ?: LOCAL

    /** The relay's error code for a key whose account no longer exists (relay 0.1.40). */
    const val ACCOUNT_DELETED = "account_deleted"

    /**
     * What becomes of the account's data when the relay refuses its key — a 401 nobody on this
     * phone asked for (signed out from another device, a relay reset, a relay bug). Kept: put
     * aside as *Keep this account's chats on this device* would, for the next sign-in with the
     * same account. Only when the relay says the account itself is gone ([ACCOUNT_DELETED]) is
     * there nothing to come back to, and the data goes as *Delete the account* would.
     */
    fun keepOnRefusedKey(code: String?): Boolean = code != ACCOUNT_DELETED

    /** What [reconcile] decided: rows to write, rows to drop, and what to hide right now. */
    data class Reconciled(
        /** Sessions that had no owner, and the owner each gets. */
        val claimed: Map<String, String>,
        /** Owner rows whose session no longer exists. */
        val stale: Set<String>,
        /** Sessions the lists leave out: every owner's but [current]'s. */
        val hidden: Set<String>,
    )

    /**
     * Every session gets an owner. A session with no row yet goes to the account its sync
     * mapping names ([syncOwners], the C10 rule — it was pushed to or pulled from that
     * account), else to [current]. [owners] are the rows so far.
     */
    fun reconcile(
        sessionIds: Collection<String>,
        owners: Map<String, String>,
        syncOwners: Map<String, String?>,
        current: String,
    ): Reconciled {
        val claimed = LinkedHashMap<String, String>()
        for (id in sessionIds) {
            if (id in owners) continue
            claimed[id] = syncOwners[id]?.takeIf { it.isNotBlank() } ?: current
        }
        val live = sessionIds.toSet()
        val stale = owners.keys.filterNot { it in live }.toSet()
        val hidden = (owners + claimed).filter { (id, owner) -> owner != current && id in live }.keys
        return Reconciled(claimed, stale, hidden)
    }

    /** The pieces of the `nanomuse` preferences that belong to the account, by key prefix. */
    val accountPrefPrefixes: List<String> = listOf("main_chat.", "first_conversation.", "feed.")

    /** Whether a preference key is the account's rather than the phone's. */
    fun isAccountPref(key: String): Boolean = accountPrefPrefixes.any { key.startsWith(it) }

    /**
     * The files and folders under `files/` that belong to the account — the agent's memory of
     * the person, the feed, the goals, the face, the shared workspace — relative to the app's
     * files directory. Chats live in the database and are handled by their owner rows.
     */
    val accountPaths: List<String> = listOf(
        "minis-global/memory",
        "minis-global/nanomuse/feed",
        "minis-global/nanomuse/feed-preferences.md",
        "minis-global/nanomuse/goals.json",
        "minis-global/nanomuse/avatar",
        "minis-global/shared",
    )
}
