package com.openminis.app.sandbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for ExecutionCoordinator.
 * Tests session mount switching, command serialization, and lifecycle.
 *
 * Run on real device: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExecutionCoordinatorInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetKernel()
        ExecutionCoordinator.init(context)

        if (canBoot()) {
            PRootKernel.boot(context)
        }
    }

    @After
    fun tearDown() {
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
        cleanupSessionDirs()
    }

    // ==================== Session mounting ====================

    @Test
    fun executeSetsMountedSessionId() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-mount-test"
        val result = ExecutionCoordinator.execute(sessionId, "echo hello")

        // nanoMuse: the coordinator keeps one shell per session; there is no single mounted id.
        assertEquals(0, result.exitCode)
        assertTrue(ExecutionCoordinator.hasShell(sessionId))
    }

    @Test
    fun executeSwitchesSessionMounts() = runBlocking {
        skipIfNoBoot()

        // nanoMuse: sessions no longer swap one mount; each keeps its own shell side by side.
        val resultA = ExecutionCoordinator.execute("session-A", "echo a")
        assertEquals(0, resultA.exitCode)
        assertTrue(ExecutionCoordinator.hasShell("session-A"))

        val resultB = ExecutionCoordinator.execute("session-B", "echo b")
        assertEquals(0, resultB.exitCode)
        assertTrue(ExecutionCoordinator.hasShell("session-B"))
        assertTrue(ExecutionCoordinator.hasShell("session-A"))
    }

    @Test
    fun executeSameSessionDoesNotRemount() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-X", "echo first")

        // Get current bind mount count
        val mountCount = PRootKernel.bindMounts.size

        ExecutionCoordinator.execute("session-X", "echo second")

        // Mounts should remain the same (not cleared and re-added)
        assertEquals(mountCount, PRootKernel.bindMounts.size)
        // nanoMuse: the session's shell is reused, not remounted.
        assertTrue(ExecutionCoordinator.hasShell("session-X"))
    }

    @Test
    fun executeCreatesSessionBindMounts() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-mounts", "echo test")

        // Should have session-level + global bind mounts
        // Session: attachments, offloads, workspace, browser (4)
        // Global: memory, skills (2)
        // nanoMuse: the coordinator's global set is memory, skills, shared and mcp-servers, so eight mounts in all.
        assertEquals(8, PRootKernel.bindMounts.size)

        // Verify session-level mounts
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/attachments"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/offloads"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/workspace"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/browser"))

        // Verify global mounts
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/memory"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/skills"))
    }

    @Test
    fun executeCreatesHostDirectories() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-dirs-test"
        ExecutionCoordinator.execute(sessionId, "echo test")

        // Verify session host directories exist
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        for (subdir in listOf("attachments", "offloads", "workspace", "browser")) {
            assertTrue("$subdir should exist", File(sessionBase, subdir).isDirectory)
        }

        // Verify global host directories exist
        val globalBase = File(context.filesDir, "minis-global")
        for (subdir in listOf("memory", "skills")) {
            assertTrue("$subdir should exist", File(globalBase, subdir).isDirectory)
        }
    }

    @Test
    fun sessionBindMountsPointToCorrectHostDirs() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-verify-paths"
        ExecutionCoordinator.execute(sessionId, "echo test")

        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        assertEquals(
            File(sessionBase, "workspace").absolutePath,
            PRootKernel.bindMounts["/var/minis/workspace"]
        )

        val globalBase = File(context.filesDir, "minis-global")
        assertEquals(
            File(globalBase, "memory").absolutePath,
            PRootKernel.bindMounts["/var/minis/memory"]
        )
    }

    // ==================== Command execution ====================

    @Test
    fun executeReturnsCorrectOutput() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-output", "echo hello world")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello world"))
    }

    @Test
    fun executeAppendsExitCodeOnFailure() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-fail", "exit 1")
        assertEquals(1, result.exitCode)
        assertTrue("Should append exit code", result.output.contains("(exit code: 1)"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnSuccess() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-ok", "echo ok")
        assertEquals(0, result.exitCode)
        assertFalse("Should not contain exit code marker", result.output.contains("(exit code:"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnTimeout() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-timeout", "sleep 60", timeout = 2_000)
        assertEquals(124, result.exitCode)
        assertFalse("Should not double-append exit code", result.output.contains("(exit code: 124)"))
    }

    @Test
    fun executeWithLineCallback() = runBlocking {
        skipIfNoBoot()

        val lines = mutableListOf<String>()
        ExecutionCoordinator.execute(
            "session-callback",
            "echo one; echo two",
            lineCallback = { lines.add(it) }
        )

        assertTrue(lines.size >= 2)
        assertTrue(lines.contains("one"))
        assertTrue(lines.contains("two"))
    }

    // ==================== Serialization ====================

    @Test
    fun executeSerializesConcurrentCalls() = runBlocking {
        skipIfNoBoot()

        // Launch two commands concurrently — they should serialize
        val startTime = System.currentTimeMillis()

        val deferred1 = async {
            ExecutionCoordinator.execute("session-serial", "sleep 1; echo first")
        }
        val deferred2 = async {
            // Small delay to ensure ordering
            delay(50)
            ExecutionCoordinator.execute("session-serial", "echo second")
        }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed
        assertEquals(0, result1.exitCode)
        assertEquals(0, result2.exitCode)

        // Total time should be > 1s (serialized, not parallel)
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Commands should be serialized (elapsed: ${elapsed}ms)", elapsed >= 900)
    }

    // ==================== sessionDidTerminate ====================

    @Test
    fun sessionDidTerminateClearsMountsForMatchingSession() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-terminate", "echo test")
        // nanoMuse: observe the session's shell instead of the removed mountedSessionId.
        assertTrue(ExecutionCoordinator.hasShell("session-terminate"))
        assertTrue(PRootKernel.bindMounts.isNotEmpty())

        ExecutionCoordinator.sessionDidTerminate("session-terminate")

        // nanoMuse: sessionDidTerminate stops and drops the shell; it does not touch the
        // kernel's bind mount table, so only the registry entry is asserted.
        assertFalse(ExecutionCoordinator.hasShell("session-terminate"))
    }

    @Test
    fun sessionDidTerminateIgnoresMismatchedSession() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-keep", "echo test")
        val mountsBefore = PRootKernel.bindMounts.size

        ExecutionCoordinator.sessionDidTerminate("session-other")

        // nanoMuse: terminating another session leaves this session's shell in place.
        assertTrue(ExecutionCoordinator.hasShell("session-keep"))
        assertFalse(ExecutionCoordinator.hasShell("session-other"))
        assertEquals(mountsBefore, PRootKernel.bindMounts.size)
    }

    @Test
    fun sessionDidTerminateIsNoOpWhenNoSession() {
        ExecutionCoordinator.sessionDidTerminate("any-session")
        // nanoMuse: nothing was registered for this id, so nothing is registered afterwards.
        assertFalse(ExecutionCoordinator.hasShell("any-session"))
    }

    // ==================== stopCurrentCommand ====================

    @Test
    fun stopCurrentCommandIsNoOpWhenIdle() {
        ExecutionCoordinator.stopCurrentCommand() // Should not throw
    }

    // ==================== Cross-session file isolation ====================

    @Test
    fun differentSessionsHaveIsolatedWorkspaces() = runBlocking {
        skipIfNoBoot()

        // Write file in session A's workspace
        ExecutionCoordinator.execute(
            "session-iso-A",
            "echo 'from A' > /var/minis/workspace/test.txt"
        )

        // Check it exists in session A
        val resultA = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA.output.contains("from A"))

        // Switch to session B — workspace should be empty
        val resultB = ExecutionCoordinator.execute(
            "session-iso-B",
            "ls /var/minis/workspace/"
        )
        assertFalse("Session B should not see session A's file", resultB.output.contains("test.txt"))

        // Switch back to A — file should still be there
        val resultA2 = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA2.output.contains("from A"))
    }

    @Test
    fun globalDirsAreSharedAcrossSessions() = runBlocking {
        skipIfNoBoot()

        // Write file to global memory in session A
        ExecutionCoordinator.execute(
            "session-global-A",
            "echo 'shared' > /var/minis/memory/shared.txt"
        )

        // Read from session B — should see the same file
        val result = ExecutionCoordinator.execute(
            "session-global-B",
            "cat /var/minis/memory/shared.txt"
        )
        assertTrue("Global dirs should be shared", result.output.contains("shared"))
    }

    // ==================== Helpers ====================

    private fun canBoot(): Boolean {
        return try {
            context.assets.open("alpine-minirootfs.tar.gz").use { }
            context.assets.open("proot-aarch64").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun skipIfNoBoot() {
        if (!PRootKernel.isBooted) {
            println("SKIP: PRoot not booted (assets not available)")
            return
        }
    }

    private fun resetKernel() {
        try {
            val field = PRootKernel::class.java.getDeclaredField("isBooted")
            field.isAccessible = true
            field.set(PRootKernel, false)
        } catch (_: Exception) { }
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()

        // nanoMuse: there is no mountedSessionId field to reset any more; stop and drop every
        // per-session shell a previous test may have left behind instead.
        testSessionIds.forEach { ExecutionCoordinator.sessionDidTerminate(it) }
    }

    // nanoMuse: shared between the per-session reset above and the directory cleanup below.
    private val testSessionIds = listOf("session-mount-test", "session-A", "session-B", "session-X",
        "session-mounts", "session-dirs-test", "session-verify-paths",
        "session-output", "session-fail", "session-ok", "session-timeout",
        "session-callback", "session-serial", "session-terminate",
        "session-keep", "session-iso-A", "session-iso-B",
        "session-global-A", "session-global-B")

    private fun cleanupSessionDirs() {
        testSessionIds.forEach { id ->
            File(context.filesDir, "minis-sessions/$id").deleteRecursively()
        }
        File(context.filesDir, "minis-global").deleteRecursively()
    }
}
