package io.github.nanomuse.reach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reach, as it is since the hub became the one way a computer joins (0.1.23): a computer
 * is a hub device, and `nanomuse-pc` names every verb in its help. (The address parser of
 * the local-network pairing went with the pairing.)
 */
class ReachTest {

    @Test fun `a computer is a hub device`() {
        val c = Computers.Computer(id = "hub:desk-1", name = "desk", os = "Linux", hubId = "desk-1")
        assertEquals("hub", c.address)
        assertEquals("computer", c.kind)
        assertTrue(c.online)
        val phone = c.copy(kind = "phone", online = false)
        assertFalse(phone.online)
        assertEquals("hub", phone.address)
    }

    @Test fun `the help names every verb`() {
        for (verb in listOf("devices", "run", "ls", "get", "put", "open", "screen", "notify", "task")) {
            assertTrue(verb, ReachOffloadHandler.HELP.contains("nanomuse-pc $verb"))
        }
        assertTrue(ReachOffloadHandler.HELP.contains("--on <device>"))
    }
}
