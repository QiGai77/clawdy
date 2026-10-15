package io.github.nanomuse.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract C10: a phone shows and syncs the signed-in account's conversations only. One store
 * and one set of local chats, two accounts (`a`, `b`) taking turns on them, each with its own
 * relay — as two sign-ins on one phone.
 */
class AccountScopeTest {
    private val store = MemorySyncStore()
    private val chats = MemoryChats()
    private var changes = 0

    private fun engine(relay: FakeRelay, account: String, sideChats: Boolean = true) =
        SyncEngine(store, chats, relay, "phone-a", account, sideChats = { sideChats }, onAccountChanged = { changes++ })

    private suspend fun texts(sessionId: String) = chats.messages(sessionId).map { Transcript.items(listOf(it)).first().text }

    @Test fun `the first push records the account as owner, and the first pull does too`() = runTest {
        val relayA = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.addSession("side", "Trip")
        chats.user("main", "hi"); chats.user("side", "plan")
        engine(relayA, "a").push()
        assertEquals("a", store.convs.getValue("main").owner)
        assertEquals("a", store.convs.getValue("side").owner)

        // a chat pulled from the relay carries the account it came from
        relayA.seed("desk-1", "cid-s2", "side", "From the desk", 1_700_000_000)
        relayA.seedMessage("desk-1", "cid-s2", "mid-1", "user", "hello", 1_700_000_001)
        engine(relayA, "a").pull()
        assertEquals("a", store.conversationByCid("cid-s2")!!.owner)
    }

    @Test fun `a mapping with no owner (before 0_1_39) is adopted by the account that pushes it`() = runTest {
        val relay = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.user("main", "hi")
        store.meta[SyncStore.ACCOUNT] = "a"
        store.meta[SyncStore.CURSOR] = "0"
        store.convs["main"] = SyncConversation("main", "cid-old", "main", "phone-a", "", null, pushed = false, owner = null)
        engine(relay, "a").push()
        assertEquals("a", store.convs.getValue("main").owner)
        assertEquals("cid-old", relay.convs.keys.single())
    }

    @Test fun `signed in as b, a's chats are hidden, not pushed, and come back when a returns`() = runTest {
        val relayA = FakeRelay()
        val relayB = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.addSession("side", "A's trip")
        chats.user("main", "a main"); chats.user("side", "a side")
        engine(relayA, "a").push()
        val cursorA = store.meta[SyncStore.CURSOR]
        assertNotNull(cursorA)

        // b signs in: a's two chats are hidden and the cursor starts over
        val b = engine(relayB, "b")
        b.accountSignedIn()
        assertEquals(1, changes)
        assertNull(store.meta[SyncStore.CURSOR])
        assertEquals(setOf("main", "side"), b.hidden())
        assertNull(chats.main) // a's home is not b's
        b.push()
        assertTrue(relayB.convs.isEmpty() && relayB.msgs.isEmpty()) // nothing of a's went into b
        // b writes: a chat made under b is b's and goes to b's relay only
        chats.addSession("b-side", "B's list"); chats.user("b-side", "milk")
        b.push()
        assertEquals("b", store.convs.getValue("b-side").owner)
        assertEquals(listOf("milk"), relayB.msgs.values.map { it.text })
        assertEquals(2, relayA.msgs.size) // a's relay is as a left it
        // and a's rows are not touched by b's pull
        relayB.seed("desk-2", "cid-bm", "main", null, 1_700_000_000)
        relayB.seedMessage("desk-2", "cid-bm", "mid-b1", "user", "b from the desk", 1_700_000_001)
        b.pull()
        assertEquals(listOf("a main"), texts("main"))
        // b's main arrived as a new chat and is the home now
        val bMain = chats.main
        assertNotNull(bMain)
        assertNotEquals("main", bMain)
        assertEquals(listOf("b from the desk"), texts(bMain!!))
        assertEquals("b", store.convs.getValue(bMain).owner)

        // a signs back in: a's chats are visible again, b's are hidden, a's main is the home, a's cursor is fresh
        val a2 = engine(relayA, "a")
        a2.accountSignedIn()
        assertEquals(2, changes)
        assertEquals(setOf("b-side", bMain), a2.hidden())
        // every list on the phone applies the same set: the drawer, the search, the move-to sheet see a's chats only
        val hidden = a2.hidden()
        assertEquals(setOf("main", "side"), chats.sessions().map { it.id }.filter { it !in hidden }.toSet())
        assertEquals("main", chats.main)
        assertNull(store.meta[SyncStore.CURSOR])
        // a's push sends only what a's relay lacks; b's chats are never offered to a's relay
        chats.user("side", "a again")
        a2.push()
        assertEquals(setOf("a main", "a side", "a again"), relayA.msgs.values.map { it.text }.toSet())
        assertFalse(relayA.msgs.values.any { it.text == "milk" })
    }

    @Test fun `a chat made while signed out joins the account that pushes first`() = runTest {
        val relayA = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.user("main", "written before any sign-in")
        // no mapping yet: nobody's
        assertTrue(SyncEngine.hidden(store, "a").isEmpty())
        assertTrue(SyncEngine.hidden(store, null).isEmpty())
        engine(relayA, "a").push()
        assertEquals("a", store.convs.getValue("main").owner)
        assertEquals(1, relayA.msgs.size)
    }

    @Test fun `a deleted chat of a is not tombstoned into b and waits for a`() = runTest {
        val relayA = FakeRelay()
        val relayB = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.addSession("side", "A's trip"); chats.user("side", "x")
        engine(relayA, "a").push()
        val cidSide = store.convs.getValue("side").cid

        val b = engine(relayB, "b")
        b.accountSignedIn()
        chats.deleteSession("side") // deleted here while b is signed in
        b.push()
        assertTrue(relayB.deletes.isEmpty())
        assertNotNull(store.convs["side"]) // the mapping waits
        assertFalse(relayA.convs.getValue(cidSide).deleted)

        val a2 = engine(relayA, "a")
        a2.accountSignedIn()
        a2.push()
        assertEquals(listOf(cidSide), relayA.deletes)
        assertNull(store.convs["side"])
    }

    @Test fun `presence and captions are the account's - the other account's rows do not count as remote here`() = runTest {
        val relayA = FakeRelay(mapOf("desk-1" to "Mac"))
        relayA.seed("desk-1", "cid-m", "main", null, 1_700_000_000)
        relayA.seedMessage("desk-1", "cid-m", "mid-1", "user", "from the mac", 1_700_000_001)
        val a = engine(relayA, "a")
        a.pull()
        val aMain = chats.main!!
        assertEquals(1, a.captions().size)
        assertEquals(1, a.remoteRows().size)
        assertTrue(a.working(aMain, true))

        val b = engine(FakeRelay(), "b")
        b.accountSignedIn()
        assertTrue(b.captions().isEmpty())
        assertTrue(b.remoteRows().isEmpty())
        assertFalse(b.working(aMain, true)) // a's chat: no presence goes out under b
        assertNull(b.sessionOf("cid-m"))
    }

    @Test fun `the same account signing in again changes nothing`() = runTest {
        val relay = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.user("main", "hi")
        engine(relay, "a").push()
        val cursor = store.meta[SyncStore.CURSOR]
        val again = engine(relay, "a")
        again.accountSignedIn()
        assertEquals(0, changes)
        assertEquals(cursor, store.meta[SyncStore.CURSOR])
        assertEquals("main", chats.main)
        assertTrue(again.hidden().isEmpty())
    }

    @Test fun `with side chats off the main chat still follows the account`() = runTest {
        val relayA = FakeRelay()
        val relayB = FakeRelay()
        chats.addSession("main"); chats.main = "main"
        chats.user("main", "a main")
        engine(relayA, "a", sideChats = false).push()

        val b = engine(relayB, "b", sideChats = false)
        b.push() // ensureAccount runs inside: a's main is not b's; nothing to push yet
        assertTrue(relayB.convs.isEmpty())
        assertNull(chats.main)
        // b writes in the new home: it becomes b's main on the relay
        val home = chats.createSession(null, 1_700_000_000_000L, 1_700_000_000_000L, main = true)
        chats.user(home, "b main")
        b.push()
        assertEquals("main", relayB.convs.values.single().kind)
        assertEquals(listOf("b main"), relayB.msgs.values.map { it.text })
        assertEquals(setOf("main"), b.hidden())
    }
}
