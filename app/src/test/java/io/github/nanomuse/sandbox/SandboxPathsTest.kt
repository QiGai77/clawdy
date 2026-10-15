package io.github.nanomuse.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Paths the agent names stay inside the sandbox: no `..`, no symlink out of the rootfs. */
class SandboxPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `normalise collapses dots and repeated slashes and never climbs above root`() {
        assertEquals("/a/c/d", SandboxPaths.normalise("/a/./b/../c//d"))
        assertEquals("/", SandboxPaths.normalise("/.."))
        assertEquals("/", SandboxPaths.normalise("/../../.."))
        assertEquals("/etc/passwd", SandboxPaths.normalise("/root/../../etc/passwd"))
        assertEquals("/root/notes.md", SandboxPaths.normalise("/root/notes.md"))
        assertEquals("/root/notes.md", SandboxPaths.normalise("root/notes.md/"))
        assertEquals("/", SandboxPaths.normalise(""))
    }

    @Test fun `inside accepts the root and its descendants, not siblings with the same prefix`() {
        val root = tmp.newFolder("alpine-rootfs")
        val sibling = tmp.newFolder("alpine-rootfs-backup")
        val child = File(root, "root/notes.md")
        assertTrue(SandboxPaths.inside(root, listOf(root)))
        assertTrue(SandboxPaths.inside(child, listOf(root)))
        assertTrue(SandboxPaths.inside(File(root, "tmp/not/there/yet"), listOf(root)))
        assertFalse(SandboxPaths.inside(sibling, listOf(root)))
        assertFalse(SandboxPaths.inside(File(sibling, "x"), listOf(root)))
        assertFalse(SandboxPaths.inside(File(root, "../shared_prefs/x.xml"), listOf(root)))
        assertFalse(SandboxPaths.inside(child, emptyList()))
    }

    @Test fun `a symlink made inside the rootfs that points out is outside`() {
        val root = tmp.newFolder("alpine-rootfs")
        val secret = tmp.newFolder("shared_prefs")
        File(secret, "account.xml").writeText("<x/>")
        val link = File(root, "tmp/leak")
        link.parentFile!!.mkdirs()
        val made = runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }.isSuccess
        assumeTrue("symlinks not available here", made)
        assertFalse(SandboxPaths.inside(File(link, "account.xml"), listOf(root)))
        assertTrue(SandboxPaths.inside(File(link, "account.xml"), listOf(root, secret)))
    }
}
