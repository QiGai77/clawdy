package io.github.nanomuse.avatar

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.ui.avatar.AgentMood
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A clip of the person's own as the face (#324). One short video, picked from the phone's
 * gallery, plays muted in a loop wherever the face moves: for the mood at rest, and for every
 * other mood too, since there is only the one clip. The stills keep their place where a still is
 * shown: a studio face's mood pictures sit under the clip until its first frame is on screen;
 * with no studio face the clip's own first frame is the still.
 *
 * The clip lives next to the pictures as `avatar/own-clip.mp4` with its first frame in
 * `avatar/own-clip.png`, and nowhere else: it is never sent to the relay ([ProfileSync] reads
 * [AvatarStore] only) and does not follow the account to another device. It survives a new
 * studio face and a reset to the built-in one; only *Default face* removes it.
 */
object OwnClip {
    private const val TAG = "OwnClip"

    /** The longest clip taken, in whole seconds; a clip that rounds down to this is fine. */
    const val MAX_SECONDS = 15

    /** The largest file taken. */
    const val MAX_BYTES = 30L * 1024 * 1024

    /** The video codec the phone's player is counted on to decode. */
    const val H264 = "video/avc"

    /** The containers taken: MP4 and QuickTime (MOV). */
    val containers: Set<String> = setOf("video/mp4", "video/quicktime")

    enum class Refusal { FORMAT, TOO_LONG, TOO_LARGE }

    /** What a look at a picked file found; a null field is one that could not be read. */
    data class Probe(
        val container: String?,
        val videoCodec: String?,
        val durationMs: Long?,
        val bytes: Long?,
    )

    /** A clip that passed the checks and was copied in, waiting for *Use* or *Cancel*. */
    data class Pending(val file: File, val poster: Bitmap?, val durationMs: Long, val bytes: Long)

    sealed class Outcome {
        data class Ready(val pending: Pending) : Outcome()
        data class Refused(val why: Refusal, val probe: Probe) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * The rule, apart from the phone: the format first (an unreadable file is a format problem,
     * whatever its size), then the length, then the size. Null means the clip is taken.
     */
    fun check(p: Probe): Refusal? {
        val codec = p.videoCodec ?: return Refusal.FORMAT
        val duration = p.durationMs ?: return Refusal.FORMAT
        if (codec != H264) return Refusal.FORMAT
        if (p.container != null && p.container !in containers) return Refusal.FORMAT
        if (duration >= (MAX_SECONDS + 1) * 1000L) return Refusal.TOO_LONG
        if (p.bytes != null && p.bytes > MAX_BYTES) return Refusal.TOO_LARGE
        return null
    }

    /** The clip on disk; [modified] tells a replaced clip from the one before at the same path. */
    data class Clip(val file: File, val modified: Long) {
        val key: String get() = "${file.path}:$modified"
    }

    private val _current = MutableStateFlow<Clip?>(null)

    /** The clip on disk, or null for none. */
    val current: StateFlow<Clip?> = _current
    private val _poster = MutableStateFlow<ImageBitmap?>(null)

    /** The clip's first frame, the still shown under it and in its place when there is no studio face. */
    val poster: StateFlow<ImageBitmap?> = _poster
    private val _moods = MutableStateFlow<Map<AgentMood, Clip>>(emptyMap())
    val moods: StateFlow<Map<AgentMood, Clip>> = _moods
    private val _moodPosters = MutableStateFlow<Map<AgentMood, ImageBitmap>>(emptyMap())
    val moodPosters: StateFlow<Map<AgentMood, ImageBitmap>> = _moodPosters

    fun clipFile(): File = File(AvatarStore.root(), "own-clip.mp4")
    fun posterFile(): File = File(AvatarStore.root(), "own-clip.png")
    private fun pendingFile(): File = File(AvatarStore.root(), "own-clip.pending.mp4")
    private fun moodClipFile(mood: AgentMood) = File(AvatarStore.root(), "own-clip-${mood.name.lowercase()}.mp4")
    private fun moodPosterFile(mood: AgentMood) = File(AvatarStore.root(), "own-clip-${mood.name.lowercase()}.png")

    fun init() {
        // a copy that was never used or cancelled (the app died in between) is of no use now
        pendingFile().delete()
        rescan()
    }

    fun rescan() {
        val f = clipFile()
        _current.value = f.takeIf { it.exists() && it.length() > 0 }?.let { Clip(it, it.lastModified()) }
        _poster.value = if (_current.value != null) AvatarStore.decodeBitmap(posterFile())?.asImageBitmap() else null
        _moods.value = AgentMood.values().mapNotNull { mood ->
            moodClipFile(mood).takeIf { it.exists() && it.length() > 0 }?.let { mood to Clip(it, it.lastModified()) }
        }.toMap()
        _moodPosters.value = _moods.value.keys.mapNotNull { mood ->
            AvatarStore.decodeBitmap(moodPosterFile(mood))?.asImageBitmap()?.let { mood to it }
        }.toMap()
    }

    /** *Use*: the pending copy becomes the clip, its first frame the still. */
    fun adopt(pending: Pending, mood: AgentMood? = null) {
        val f = mood?.let(::moodClipFile) ?: clipFile()
        if (!pending.file.renameTo(f)) {
            f.delete()
            if (!pending.file.renameTo(f)) { pending.file.copyTo(f, overwrite = true); pending.file.delete() }
        }
        val poster = pending.poster
        val still = mood?.let(::moodPosterFile) ?: posterFile()
        if (poster != null) AvatarStore.write(still, poster) else still.delete()
        rescan()
    }

    /** *Cancel* in the preview: the copy goes. */
    fun discard(pending: Pending) {
        pending.file.delete()
    }

    /** *Default face*: back to the studio face, or the built-in one. */
    fun clear(mood: AgentMood? = null) {
        if (mood == null) {
            clipFile().delete()
            posterFile().delete()
            AgentMood.values().forEach { moodClipFile(it).delete(); moodPosterFile(it).delete() }
        } else {
            moodClipFile(mood).delete()
            moodPosterFile(mood).delete()
        }
        pendingFile().delete()
        rescan()
    }

    /**
     * Looks at the picked file without copying it: the size from the provider, the container
     * and the length from the metadata, the codec from the first video track.
     */
    fun probe(context: Context, uri: Uri): Probe {
        val resolver = context.contentResolver
        var bytes: Long? = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 }
        if (bytes == null) {
            bytes = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }.getOrNull()
        }
        var container: String? = null
        var durationMs: Long? = null
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                container = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                r.release()
            }
        }.onFailure { AppLogger.info(TAG, "metadata: ${it.message}") }
        var codec: String? = null
        runCatching {
            val x = MediaExtractor()
            try {
                x.setDataSource(context, uri, null)
                for (i in 0 until x.trackCount) {
                    val mime = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) { codec = mime; break }
                }
            } finally {
                x.release()
            }
        }.onFailure { AppLogger.info(TAG, "tracks: ${it.message}") }
        return Probe(container, codec, durationMs, bytes)
    }

    /**
     * Copies [input] into [dst], but never more than [limit] bytes: the copy stops as soon as
     * the limit is passed, the partial file is deleted and null comes back, so a provider that
     * gave no size up front cannot fill the disk with a long clip. Otherwise the byte count.
     */
    internal fun copyBounded(input: InputStream, dst: File, limit: Long = MAX_BYTES): Long? {
        var copied = 0L
        var over = false
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(dst).use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (copied + n > limit) { over = true; break }
                out.write(buffer, 0, n)
                copied += n
            }
        }
        if (over) { dst.delete(); return null }
        return copied
    }

    /**
     * The whole import: a look first, and a clip the rule refuses never touches the disk; one it
     * takes is copied into the avatar directory as a pending file, its first frame decoded, and
     * handed back for the preview.
     */
    fun import(context: Context, uri: Uri): Outcome {
        val probe = probe(context, uri)
        check(probe)?.let { return Outcome.Refused(it, probe) }
        val dst = pendingFile()
        try {
            dst.delete()
            val input = context.contentResolver.openInputStream(uri) ?: return Outcome.Failed("cannot open")
            // The provider gave no size up front (or a wrong one) and the file turned out larger:
            // the copy stopped at the limit, so the real size is unknown and stays null.
            val copied = input.use { copyBounded(it, dst) }
                ?: return Outcome.Refused(Refusal.TOO_LARGE, probe.copy(bytes = null))
            val poster = runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(dst.path)
                    r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    r.release()
                }
            }.onFailure { AppLogger.info(TAG, "first frame: ${it.message}") }.getOrNull()
            return Outcome.Ready(Pending(dst, poster, probe.durationMs ?: 0L, copied))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "import: ${e.message}")
            dst.delete()
            return Outcome.Failed(e.message ?: "cannot read")
        }
    }
}
