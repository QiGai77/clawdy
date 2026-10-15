package io.github.nanomuse.avatar

import io.github.nanomuse.avatar.OwnClip.Probe
import io.github.nanomuse.avatar.OwnClip.Refusal
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The rule for a clip of one's own (#324) and what the store does with the files. */
class OwnClipTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ok(
        container: String? = "video/mp4",
        codec: String? = OwnClip.H264,
        durationMs: Long? = 8_000L,
        bytes: Long? = 4L * 1024 * 1024,
    ) = Probe(container, codec, durationMs, bytes)

    @Test fun `an H264 MP4 within the limits is taken`() {
        assertNull(OwnClip.check(ok()))
    }

    @Test fun `a MOV with H264 video is taken too`() {
        assertNull(OwnClip.check(ok(container = "video/quicktime")))
    }

    @Test fun `a clip that rounds down to 15 seconds is taken and 16 seconds is not`() {
        assertNull(OwnClip.check(ok(durationMs = 15_000L)))
        assertNull(OwnClip.check(ok(durationMs = 15_500L)))
        assertNull(OwnClip.check(ok(durationMs = 15_999L)))
        assertEquals(Refusal.TOO_LONG, OwnClip.check(ok(durationMs = 16_000L)))
        assertEquals(Refusal.TOO_LONG, OwnClip.check(ok(durationMs = 60_000L)))
    }

    @Test fun `a picture or a text file has no video track and is a format refusal`() {
        // what probe() returns for a JPEG: the retriever gives a container and no length, the extractor no video track
        assertEquals(Refusal.FORMAT, OwnClip.check(Probe("image/jpeg", null, null, 300_000L)))
        // a text file: nothing can be read of it but its size
        assertEquals(Refusal.FORMAT, OwnClip.check(Probe(null, null, null, 12L)))
        // an audio-only MP4: a container and a length, but no video track
        assertEquals(Refusal.FORMAT, OwnClip.check(Probe("video/mp4", null, 8_000L, 300_000L)))
    }

    @Test fun `a HEVC MOV is refused for its codec, not its container`() {
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(container = "video/quicktime", codec = "video/hevc")))
    }

    @Test fun `30 MB is the largest file taken`() {
        assertNull(OwnClip.check(ok(bytes = OwnClip.MAX_BYTES)))
        assertEquals(Refusal.TOO_LARGE, OwnClip.check(ok(bytes = OwnClip.MAX_BYTES + 1)))
        assertEquals(30L * 1024 * 1024, OwnClip.MAX_BYTES)
        assertEquals(15, OwnClip.MAX_SECONDS)
    }

    @Test fun `another codec or container is a format refusal`() {
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(codec = "video/hevc")))
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(codec = "video/x-vnd.on2.vp9")))
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(container = "video/webm")))
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(container = "video/x-matroska")))
    }

    @Test fun `a file whose tracks or length cannot be read is a format refusal, whatever its size`() {
        assertEquals(Refusal.FORMAT, OwnClip.check(Probe(null, null, null, 100L)))
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(codec = null)))
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(durationMs = null)))
        assertEquals(Refusal.FORMAT, OwnClip.check(Probe(null, null, null, OwnClip.MAX_BYTES * 2)))
    }

    @Test fun `an unknown container or size does not stand in the way of a readable H264 clip`() {
        assertNull(OwnClip.check(ok(container = null)))
        assertNull(OwnClip.check(ok(bytes = null)))
    }

    @Test fun `the format is checked before the length and the length before the size`() {
        assertEquals(Refusal.FORMAT, OwnClip.check(ok(codec = "video/hevc", durationMs = 60_000L, bytes = OwnClip.MAX_BYTES * 2)))
        assertEquals(Refusal.TOO_LONG, OwnClip.check(ok(durationMs = 60_000L, bytes = OwnClip.MAX_BYTES * 2)))
    }

    // -- the copy -----------------------------------------------------------------------------

    @Test fun `a file within the limit is copied whole and its size reported`() {
        val dst = File(tmp.newFolder(), "pending.mp4")
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(200_000L, OwnClip.copyBounded(bytes.inputStream(), dst, limit = 1_000_000L))
        assertTrue(dst.readBytes().contentEquals(bytes))
    }

    @Test fun `a file exactly at the limit is taken`() {
        val dst = File(tmp.newFolder(), "pending.mp4")
        val bytes = ByteArray(70_000)
        assertEquals(70_000L, OwnClip.copyBounded(bytes.inputStream(), dst, limit = 70_000L))
        assertEquals(70_000L, dst.length())
    }

    @Test fun `the copy stops at the limit, deletes the partial file and reads no further`() {
        val dst = File(tmp.newFolder(), "pending.mp4")
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served++; return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertNull(OwnClip.copyBounded(endless, dst, limit = 1_000_000L))
        assertFalse("the partial copy is gone", dst.exists())
        assertTrue("stopped soon after the limit, not at the end of the stream (read $served bytes)", served <= 1_000_000L + 2 * 64 * 1024)
    }

    @Test fun `the default limit is the 30 MB rule`() {
        val dst = File(tmp.newFolder(), "pending.mp4")
        val overLimit = object : java.io.InputStream() {
            var left = OwnClip.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt(); left -= n; return n
            }
        }
        assertNull(OwnClip.copyBounded(overLimit, dst))
        assertFalse(dst.exists())
    }

    // -- the files ----------------------------------------------------------------------------

    private fun pending(root: File, content: String = "clip"): OwnClip.Pending {
        val f = File(root, "own-clip.pending.mp4").apply { writeText(content) }
        return OwnClip.Pending(f, poster = null, durationMs = 8_000L, bytes = f.length())
    }

    @Test fun `use moves the pending copy into place and the store sees it`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        OwnClip.init()
        assertNull(OwnClip.current.value)
        val p = pending(root)
        OwnClip.adopt(p)
        val clip = OwnClip.current.value
        assertEquals(File(root, "own-clip.mp4"), clip?.file)
        assertEquals("clip", clip?.file?.readText())
        assertFalse("the pending copy is gone", p.file.exists())
        assertNull("no first frame could be decoded here, so no poster", OwnClip.poster.value)
        assertTrue(clip!!.key.startsWith(clip.file.path))
    }

    @Test fun `cancel in the preview deletes the copy and changes nothing`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        OwnClip.init()
        val p = pending(root)
        OwnClip.discard(p)
        assertFalse(p.file.exists())
        assertNull(OwnClip.current.value)
        assertFalse(File(root, "own-clip.mp4").exists())
    }

    @Test fun `default face removes the clip and its still`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        OwnClip.init()
        OwnClip.adopt(pending(root))
        File(root, "own-clip.png").writeText("png")
        OwnClip.rescan()
        OwnClip.clear()
        assertNull(OwnClip.current.value)
        assertNull(OwnClip.poster.value)
        assertFalse(File(root, "own-clip.mp4").exists())
        assertFalse(File(root, "own-clip.png").exists())
    }

    @Test fun `a replaced clip gets a new key so the player starts over`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        OwnClip.init()
        OwnClip.adopt(pending(root, "one"))
        val first = OwnClip.current.value!!
        val second = pending(root, "two")
        second.file.setLastModified(first.modified + 5_000L)
        OwnClip.adopt(second)
        val now = OwnClip.current.value!!
        assertEquals("two", now.file.readText())
        assertEquals(first.file, now.file)
        assertNotEquals(first.key, now.key)
    }

    @Test fun `a pending copy left behind by a crash is dropped on start`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        val stale = File(root, "own-clip.pending.mp4").apply { writeText("stale") }
        OwnClip.init()
        assertFalse(stale.exists())
        assertNull(OwnClip.current.value)
    }

    @Test fun `an empty file is no clip`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        File(root, "own-clip.mp4").writeBytes(ByteArray(0))
        OwnClip.rescan()
        assertNull(OwnClip.current.value)
    }

    @Test fun `the clip lives beside the studio pictures and survives a reset of the studio face`() {
        val root = tmp.newFolder()
        AvatarStore.useDirForTests(root)
        OwnClip.init()
        OwnClip.adopt(pending(root))
        // the studio's own files, and a clip the video model made
        AvatarStore.baseFile().writeText("base")
        File(AvatarStore.root(), "motion").mkdirs()
        AvatarMotion.clipFile(io.github.nanomuse.ui.avatar.AgentMood.IDLE).writeText("idle")
        AvatarMotion.rescan()
        assertEquals(1, AvatarMotion.clips.value.size)
        AvatarStore.reset()
        assertFalse(AvatarStore.baseFile().exists())
        assertTrue("the studio clips belong to the studio face", AvatarMotion.clips.value.isEmpty())
        assertTrue("the person's own clip stays", OwnClip.clipFile().exists())
        OwnClip.rescan()
        assertEquals(File(root, "own-clip.mp4"), OwnClip.current.value?.file)
    }
}
