package io.github.nanomuse.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract C12: whose chat a session is and what the lists leave out. The rules in
 * [AccountScope] decide; `AccountData` only applies them to the databases.
 */
class AccountScopeRulesTest {

    // -- the key -------------------------------------------------------------------------------

    @Test fun `the relay's account id is the key, never the number or the address`() {
        assertEquals("acc_7f3", AccountScope.key("acc_7f3", "nmk_secret"))
        assertEquals("acc_7f3", AccountScope.key("acc_7f3", null))
    }

    @Test fun `a relay without account ids falls back to a hash of the key itself`() {
        val k = AccountScope.key(null, "nmk_secret")
        assertNotEquals(AccountScope.LOCAL, k)
        assertFalse("the key itself must not be the folder name", k.contains("nmk_secret"))
        assertEquals(k, AccountScope.key("  ", "nmk_secret"))
        assertNotEquals(k, AccountScope.key(null, "nmk_other"))
    }

    @Test fun `signed out, the key is LOCAL`() {
        assertEquals(AccountScope.LOCAL, AccountScope.key(null, null))
        assertEquals(AccountScope.LOCAL, AccountScope.key("", ""))
    }

    @Test fun `the folder name is safe for a file system and distinct for LOCAL`() {
        assertEquals("_local", AccountScope.dirName(AccountScope.LOCAL))
        assertEquals("acc_7f3", AccountScope.dirName("acc_7f3"))
        assertEquals("a_b_c.d-e", AccountScope.dirName("a/b:c.d-e"))
        assertNotEquals(AccountScope.dirName("a/b"), AccountScope.dirName("_local"))
    }

    // -- reconcile -----------------------------------------------------------------------------

    @Test fun `sessions without an owner go to the signed-in account and are not hidden`() {
        val r = AccountScope.reconcile(listOf("s1", "s2"), emptyMap(), emptyMap(), "A")
        assertEquals(mapOf("s1" to "A", "s2" to "A"), r.claimed)
        assertTrue(r.stale.isEmpty())
        assertTrue(r.hidden.isEmpty())
    }

    @Test fun `a sync mapping names the owner before the signed-in account does (C10 rule)`() {
        val r = AccountScope.reconcile(
            sessionIds = listOf("synced-by-b", "fresh"),
            owners = emptyMap(),
            syncOwners = mapOf("synced-by-b" to "B", "fresh" to null),
            current = "A",
        )
        assertEquals(mapOf("synced-by-b" to "B", "fresh" to "A"), r.claimed)
        assertEquals(setOf("synced-by-b"), r.hidden)
    }

    @Test fun `signed out, every account's chats are hidden and the phone's own show`() {
        val r = AccountScope.reconcile(
            sessionIds = listOf("a1", "b1", "mine", "new"),
            owners = mapOf("a1" to "A", "b1" to "B", "mine" to AccountScope.LOCAL),
            syncOwners = emptyMap(),
            current = AccountScope.LOCAL,
        )
        assertEquals(mapOf("new" to AccountScope.LOCAL), r.claimed)
        assertEquals(setOf("a1", "b1"), r.hidden)
    }

    @Test fun `signed in, the other account's and the phone's own chats are hidden`() {
        val r = AccountScope.reconcile(
            sessionIds = listOf("a1", "b1", "mine"),
            owners = mapOf("a1" to "A", "b1" to "B", "mine" to AccountScope.LOCAL),
            syncOwners = emptyMap(),
            current = "A",
        )
        assertTrue(r.claimed.isEmpty())
        assertEquals(setOf("b1", "mine"), r.hidden)
    }

    @Test fun `owner rows of deleted sessions are stale and never hidden`() {
        val r = AccountScope.reconcile(
            sessionIds = listOf("a1"),
            owners = mapOf("a1" to "A", "gone" to "B"),
            syncOwners = emptyMap(),
            current = "A",
        )
        assertEquals(setOf("gone"), r.stale)
        assertTrue(r.hidden.isEmpty())
    }

    @Test fun `an existing owner row is never rewritten`() {
        val r = AccountScope.reconcile(
            sessionIds = listOf("a1"),
            owners = mapOf("a1" to "A"),
            syncOwners = mapOf("a1" to "B"),
            current = "B",
        )
        assertTrue(r.claimed.isEmpty())
        assertEquals(setOf("a1"), r.hidden)
    }

    // -- what else is the account's ------------------------------------------------------------

    @Test fun `the account's preferences are the home, the first conversation and the feed`() {
        assertTrue(AccountScope.isAccountPref("main_chat.session"))
        assertTrue(AccountScope.isAccountPref("first_conversation.phase"))
        assertTrue(AccountScope.isAccountPref("feed.task"))
        assertFalse(AccountScope.isAccountPref("hands.enabled"))
        assertFalse(AccountScope.isAccountPref("cloud.instance"))
    }

    // -- a key the relay refuses ---------------------------------------------------------------

    @Test fun `a refused key keeps the account's data aside — a sign-out nobody here asked for`() {
        assertTrue("revoked elsewhere, or by the person's own Sign out everywhere", AccountScope.keepOnRefusedKey("bad_key"))
        assertTrue("a relay reset or a relay bug answers like a revoked key", AccountScope.keepOnRefusedKey(null))
        assertTrue(AccountScope.keepOnRefusedKey(""))
        assertTrue("an older relay, or a code the phone does not know", AccountScope.keepOnRefusedKey("http_401"))
    }

    @Test fun `only a deleted account leaves nothing to come back to`() {
        assertFalse(AccountScope.keepOnRefusedKey(AccountScope.ACCOUNT_DELETED))
        assertEquals("the relay's code, as documented", "account_deleted", AccountScope.ACCOUNT_DELETED)
    }

    @Test fun `the account's files are under the app's files directory and never the grants`() {
        assertTrue(AccountScope.accountPaths.contains("minis-global/memory"))
        assertTrue(AccountScope.accountPaths.contains("minis-global/nanomuse/goals.json"))
        assertFalse(AccountScope.accountPaths.any { it.contains("grants") })
        assertTrue(AccountScope.accountPaths.none { it.startsWith("/") })
    }
}
