package io.github.nanomuse.speech

import android.content.ContextWrapper
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import com.openminis.app.speech.SegmentEndReason
import com.openminis.app.speech.VoiceActivityDetector
import com.openminis.app.speech.VoiceActivityListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancelling provider voice input must stop the detector it owns: the detector's capture loop
 * runs on flags of its own, so flipping the engine's flags alone leaves the microphone open
 * (#356, reported with the diagnosis by @Mola-maker). No AudioRecord and no native VAD here:
 * a detector with a controlled capture job stands in for a live one.
 */
class ProviderSpeechCancelTest {
    private val context = ContextWrapper(null)

    @Test fun `cancel cancels the owned detector and drops it`() {
        val engine = ProviderSpeechRecognitionEngine(context)
        val detector = detector()
        val capture = Job()
        flag(detector, "running").set(true)
        set(detector, "captureJob", capture)
        set(engine, "detector", detector)
        flag(engine, "recording").set(true)
        try {
            engine.cancel()
            assertTrue("the capture job must be cancelled", capture.isCancelled)
            assertFalse(detector.isRunning)
            assertTrue("cancel, not stop: no late segment may be delivered", flag(detector, "cancelled").get())
            assertNull(get(engine, "detector"))
            assertTrue(flag(engine, "cancelled").get())
            assertFalse(flag(engine, "recording").get())
        } finally {
            detector.cancel()
        }
    }

    @Test fun `stop keeps the detector's plain stop`() {
        val engine = ProviderSpeechRecognitionEngine(context)
        val detector = detector()
        val capture = Job()
        flag(detector, "running").set(true)
        set(detector, "captureJob", capture)
        set(engine, "detector", detector)
        flag(engine, "recording").set(true)
        try {
            engine.stop()
            assertTrue(capture.isCancelled)
            assertFalse(detector.isRunning)
            assertFalse("stop lets a segment already closed stand", flag(detector, "cancelled").get())
            assertNull(get(engine, "detector"))
            assertFalse(flag(engine, "cancelled").get())
        } finally {
            detector.cancel()
        }
    }

    @Test fun `cancel without a detector still drops held audio and pending work`() {
        val engine = ProviderSpeechRecognitionEngine(context)
        val transcription = Job()
        val holdFlush = Job()
        set(engine, "transcribeJob", transcription)
        set(engine, "holdFlushJob", holdFlush)
        @Suppress("UNCHECKED_CAST")
        val pending = get(engine, "pendingSegments") as MutableList<ByteArray>
        pending.add(byteArrayOf(1, 2, 3))
        engine.cancel()
        assertTrue(transcription.isCancelled)
        assertTrue(holdFlush.isCancelled)
        assertNull(get(engine, "holdFlushJob"))
        assertTrue(pending.isEmpty())
    }

    private fun detector() = VoiceActivityDetector(context, object : VoiceActivityListener {
        override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) = Unit
    })

    private fun get(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun set(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun flag(owner: Any, name: String) = get(owner, name) as AtomicBoolean
}
