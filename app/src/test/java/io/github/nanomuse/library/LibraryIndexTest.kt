package io.github.nanomuse.library

import io.github.nanomuse.account.AccountScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library lists the signed-in account's workspaces and no others (contract C12): the same
 * `hidden` set the chat list gets from [AccountScope.reconcile] keeps another account's files
 * off the screen of a shared phone.
 */
class LibraryIndexTest {
    private val sessions = listOf("a1", "a2", "b1", "local1")
    private val owners = mapOf("a1" to "acc_a", "a2" to "acc_a", "b1" to "acc_b", "local1" to AccountScope.LOCAL)

    private fun hiddenFor(current: String): Set<String> =
        AccountScope.reconcile(sessions, owners, emptyMap(), current).hidden

    @Test fun `signed in as one account, the other account's workspaces stay out`() {
        val known = sessions.toSet()
        val hiddenA = hiddenFor("acc_a")
        assertTrue(LibraryIndex.shows("a1", known, hiddenA))
        assertTrue(LibraryIndex.shows("a2", known, hiddenA))
        assertFalse(LibraryIndex.shows("b1", known, hiddenA))
        assertFalse("a local chat is not the account's either", LibraryIndex.shows("local1", known, hiddenA))

        val hiddenB = hiddenFor("acc_b")
        assertEquals(listOf("b1"), sessions.filter { LibraryIndex.shows(it, known, hiddenB) })
    }

    @Test fun `signed out, only the local workspaces show`() {
        val known = sessions.toSet()
        val hidden = hiddenFor(AccountScope.LOCAL)
        assertEquals(listOf("local1"), sessions.filter { LibraryIndex.shows(it, known, hidden) })
    }

    @Test fun `a workspace whose session is gone or not yet a chat is not listed`() {
        val known = setOf("a1")
        assertFalse(LibraryIndex.shows("deleted", known, emptySet()))
        assertFalse(LibraryIndex.shows("__new__draft", known, emptySet()))
        assertTrue(LibraryIndex.shows("a1", known, emptySet()))
    }
}
