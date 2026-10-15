package io.github.nanomuse.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client's behaviour apart from the socket: what a call does when the socket is gone. */
class HubClientTest {
    private fun client(onState: (Boolean, String) -> Unit = { _, _ -> }) = HubClient(
        url = "wss://relay.example/v1/hub",
        key = "nm_test",
        hello = { JSONObject() },
        onCall = {},
        onDevices = {},
        onState = onState,
    )

    private fun call(client: HubClient) = IncomingCall(
        id = "c1",
        from = JSONObject().put("id", "pc-1").put("name", "Desk"),
        action = "task",
        args = JSONObject(),
        client = client,
    )

    @Test fun `an answer on a dropped socket is lost quietly, not thrown`() {
        val c = call(client())
        c.event(JSONObject().put("stage", "thinking"))
        c.result(JSONObject().put("text", "done"))
        assertTrue(c.answered)
        c.fail("failed", "late")
        assertTrue(c.answered)
    }

    @Test fun `a call is answered once`() {
        val c = call(client())
        assertFalse(c.answered)
        c.fail("not_allowed", "no")
        assertTrue(c.answered)
        c.result(JSONObject())
        assertTrue(c.answered)
    }

    @Test fun `our own call throws disconnected when there is no socket`() {
        val states = mutableListOf<String>()
        val c = client { _, detail -> states += detail }
        val e = runCatching { c.call("pc-1", "info", JSONObject(), timeoutMs = 1_000L) }.exceptionOrNull()
        assertTrue(e is HubException)
        assertEquals("disconnected", (e as HubException).code)
        assertTrue("no state change was reported for a client that never started", states.isEmpty())
    }

    @Test fun `a stopped client reports no state`() {
        val states = mutableListOf<Pair<Boolean, String>>()
        val c = client { on, detail -> states += on to detail }
        c.stop()
        assertFalse(c.connected)
        assertTrue(states.isEmpty())
    }
}
