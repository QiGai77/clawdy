package io.github.nanomuse.speech

import android.content.ContextWrapper
import android.media.AudioRecord
import com.openminis.app.speech.SegmentEndReason
import com.openminis.app.speech.VoiceActivityDetector
import com.openminis.app.speech.VoiceActivityListener
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The detector and its capture loop are real; only the Android recorder is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceActivityRecorderReleaseTest {
    @Test fun `a recorder that cannot start is released`() {
        val errors = mutableListOf<String>()
        val detector = detector { errors += it }
        val recorder = Mockito.mock(AudioRecord::class.java)
        Mockito.doThrow(IllegalStateException("microphone busy")).`when`(recorder).startRecording()

        captureLoop(detector, recorder)

        assertEquals(listOf("Microphone unavailable: microphone busy"), errors)
        assertFalse(detector.isRunning)
        Mockito.verify(recorder).release()
    }

    @Test fun `an error listener cannot prevent recorder release`() {
        val detector = detector { throw IllegalStateException("listener failed") }
        val recorder = Mockito.mock(AudioRecord::class.java)
        Mockito.doThrow(IllegalStateException("microphone busy")).`when`(recorder).startRecording()

        val failure = runCatching { captureLoop(detector, recorder) }.exceptionOrNull()

        assertEquals("listener failed", failure?.message)
        Mockito.verify(recorder).release()
    }

    @Test fun `cancelling before capture is dispatched releases the allocated recorder`() = runTest {
        assertCancelledBeforeDispatch(rearm = false)
    }

    @Test fun `a cancelled worker cannot open its recorder after another capture is armed`() = runTest {
        assertCancelledBeforeDispatch(rearm = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertCancelledBeforeDispatch(rearm: Boolean) {
        val detector = detector { error("Cancelled capture must not report an error") }
        val recorder = Mockito.mock(AudioRecord::class.java)
        // Bound a broken implementation that incorrectly starts the retired recorder.
        Mockito.doAnswer { flag(detector, "running").set(false); null }.`when`(recorder).startRecording()
        // Enter after hardware allocation, at the same worker boundary as start().
        set(detector, "scope", CoroutineScope(StandardTestDispatcher(testScheduler)))
        set(detector, "recorder", recorder)
        val launch = VoiceActivityDetector::class.java.getDeclaredMethod("launchCapture", AudioRecord::class.java)
            .apply { isAccessible = true }
        val job = launch.invoke(detector, recorder) as Job
        set(detector, "captureJob", job)

        detector.cancel()
        if (rearm) {
            flag(detector, "running").set(true)
            flag(detector, "cancelled").set(false)
        }
        runCurrent()

        assertTrue(job.isCompleted)
        Mockito.verify(recorder).release()
        Mockito.verify(recorder, Mockito.never()).startRecording()
        assertEquals(rearm, detector.isRunning)
    }

    @Test fun `a normally stopped capture releases its recorder once`() {
        val detector = detector { error(it) }
        val recorder = Mockito.mock(AudioRecord::class.java)
        Mockito.doAnswer { detector.stop(); null }.`when`(recorder).startRecording()

        captureLoop(detector, recorder)

        Mockito.verify(recorder).startRecording()
        Mockito.verify(recorder).stop()
        Mockito.verify(recorder).release()
    }

    private fun detector(onError: (String) -> Unit): VoiceActivityDetector =
        VoiceActivityDetector(ContextWrapper(null), object : VoiceActivityListener {
            override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) = Unit
            override fun onCaptureError(message: String) = onError(message)
        }).also { detector ->
            flag(detector, "running").set(true)
        }

    private fun captureLoop(detector: VoiceActivityDetector, recorder: AudioRecord) {
        val method = VoiceActivityDetector::class.java.getDeclaredMethod("captureLoop", AudioRecord::class.java)
            .apply { isAccessible = true }
        try {
            method.invoke(detector, recorder)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun set(detector: VoiceActivityDetector, name: String, value: Any) {
        VoiceActivityDetector::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(detector, value)
    }

    private fun flag(detector: VoiceActivityDetector, name: String): AtomicBoolean =
        VoiceActivityDetector::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(detector) as AtomicBoolean
}
