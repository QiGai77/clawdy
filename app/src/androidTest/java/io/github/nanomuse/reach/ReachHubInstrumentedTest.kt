package io.github.nanomuse.reach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.sandbox.NativeOffloadRequest
import io.github.nanomuse.hub.Hub
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `nanomuse-pc` against a real computer on the hub — the way the phone's agent drives one,
 * minus the sandbox hop. Needs this phone signed in to nanoMuse Cloud with a computer named
 * "desk" online on the same account; otherwise the tests are skipped, not failed.
 *
 *   ./gradlew :app:connectedDebugAndroidTest -Pnm.abi=x86_64 \
 *       -Pandroid.testInstrumentationRunnerArguments.class=io.github.nanomuse.reach.ReachHubInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class ReachHubInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val handler = ReachOffloadHandler(context)

    private fun pc(vararg argv: String) = handler.handle(NativeOffloadRequest(pid = 1, argv = listOf("nanomuse-pc", *argv), env = emptyMap(), cwd = "/root", sessionId = null))

    private fun ready(): Boolean {
        Hub.autoStart(context)
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            if (Hub.isConnected && Hub.others(context).any { it.online && it.name == "desk" }) return true
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun devicesListsTheComputer() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("devices")
        assertEquals(r.output, 0, r.exitCode)
        val body = JSONObject(r.output)
        assertTrue(body.getBoolean("hub"))
        val names = (0 until body.getJSONArray("devices").length()).map { body.getJSONArray("devices").getJSONObject(it).getString("name") }
        assertTrue(names.toString(), "desk" in names)
    }

    @Test
    fun runsASafeCommandOnTheComputer() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("run", "uname -a && echo phone-was-here", "--on", "desk")
        assertEquals(r.output, 0, r.exitCode)
        val body = JSONObject(r.output)
        assertEquals("desk", body.getString("computer"))
        assertEquals(0, body.getInt("exit_code"))
        assertTrue(body.getString("stdout"), body.getString("stdout").contains("phone-was-here"))
    }

    @Test
    fun listsAFolderOnTheComputer() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("ls", "/tmp", "--on", "desk")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(JSONObject(r.output).getJSONArray("entries").length() > 0)
    }

    @Test
    fun notifiesTheComputer() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("notify", "Hello from the phone — the hub works both ways", "--on", "desk")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(JSONObject(r.output).getBoolean("ok"))
    }

    @Test
    fun handsATaskToTheComputersMuse() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("task", "Reply with exactly the single word PONG and nothing else.", "--on", "desk")
        assertEquals(r.output, 0, r.exitCode)
        val body = JSONObject(r.output)
        assertEquals("desk", body.getString("computer"))
        assertTrue(body.getString("answer"), body.getString("answer").uppercase().contains("PONG"))
    }

    @Test
    fun unknownDeviceIsRefusedWithTheListOfKnownOnes() {
        assumeTrue("phone not on the hub with a 'desk' online", ready())
        val r = pc("run", "true", "--on", "no-such-device")
        assertEquals(3, r.exitCode)
        val body = JSONObject(r.output)
        assertEquals("no_computer", body.getString("error"))
        assertTrue(body.getString("message"), body.getString("message").contains("desk"))
    }
}
