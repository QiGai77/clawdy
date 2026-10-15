package io.github.nanomuse.speech

import android.content.Context
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SegmentEndReason
import com.openminis.app.speech.SessionLimit
import com.openminis.app.speech.SpeechRecognitionEngine
import com.openminis.app.speech.VoiceActivityDetector
import com.openminis.app.speech.VoiceActivityListener
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSpeechTerminalTest {
    @Test fun `idle capture delivers a quiet terminal result`() {
        Fixture().use { fixture ->
            fixture.callback.onSessionLimit(SessionLimit.IDLE)
            assertEquals(listOf(""), fixture.finals)
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun `tiny held utterance settles after the hold expires`() {
        Fixture().use { fixture ->
            fixture.segment(0.1f)
            fixture.scope.advanceTimeBy(5_001)
            fixture.scope.runCurrent()
            assertEquals(listOf(""), fixture.finals)
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun `longer short utterance retains the no match result`() {
        Fixture().use { fixture ->
            fixture.segment(0.5f)
            fixture.scope.advanceTimeBy(5_001)
            fixture.scope.runCurrent()
            assertTrue(fixture.finals.isEmpty())
            assertEquals(listOf(RecognitionError.NO_MATCH), fixture.errors)
        }
    }

    @Test fun `session limit leaves a pending transcription to deliver its result`() {
        Fixture().use { fixture ->
            val transcription = Job()
            fixture.field("transcribeJob", transcription)
            fixture.callback.onSessionLimit(SessionLimit.MAX_DURATION)
            assertTrue(transcription.isActive)
            assertTrue(fixture.finals.isEmpty())
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun `cancelled capture does not deliver a later session limit`() {
        Fixture().use { fixture ->
            fixture.engine.cancel()
            fixture.callback.onSessionLimit(SessionLimit.IDLE)
            assertTrue(fixture.finals.isEmpty())
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun `session limit after a transcription result does not deliver another terminal`() {
        Fixture().use { fixture ->
            fixture.callback.onVoiceEnd(ByteArray(44 + 64_000), SegmentEndReason.MAX_LENGTH_REACHED, 2f)
            fixture.scope.runCurrent()
            // An empty candidate list deterministically finishes the real transcription path.
            assertEquals(listOf(RecognitionError.UNKNOWN), fixture.errors)
            fixture.callback.onSessionLimit(SessionLimit.MAX_DURATION)
            assertEquals(listOf(RecognitionError.UNKNOWN), fixture.errors)
            assertTrue(fixture.finals.isEmpty())
        }
    }

    @Test fun `session limit retires the short utterance timer`() {
        Fixture().use { fixture ->
            fixture.segment(0.5f)
            fixture.scope.runCurrent()
            fixture.callback.onSessionLimit(SessionLimit.BACKGROUNDED)
            fixture.scope.advanceTimeBy(5_001)
            fixture.scope.runCurrent()
            assertEquals(listOf(""), fixture.finals)
            assertTrue(fixture.errors.isEmpty())
        }
    }

    /** The actual provider engine creates and uses this listener; only audio capture is mocked. */
    private class Fixture : AutoCloseable {
        val scope = TestScope()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<RecognitionError>()
        lateinit var callback: VoiceActivityListener
        private val detectors = Mockito.mockConstruction(VoiceActivityDetector::class.java) { _, construction ->
            callback = construction.arguments()[1] as VoiceActivityListener
        }
        val engine = ProviderSpeechRecognitionEngine(Mockito.mock(Context::class.java))

        init {
            field("scope", scope)
            val observer = object : SpeechRecognitionEngine.Listener {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { finals += text }
                override fun onError(error: RecognitionError, message: String?) { errors += error }
            }
            // Enter after provider selection, without opening a microphone or issuing a request.
            ProviderSpeechRecognitionEngine::class.java.getDeclaredMethod(
                "startVadCapture", Locale::class.java, ProviderRepository::class.java,
                List::class.java, SpeechRecognitionEngine.Listener::class.java,
            ).apply { isAccessible = true }.invoke(
                engine, Locale.US, Mockito.mock(ProviderRepository::class.java), emptyList<Any>(), observer,
            )
        }

        fun field(name: String, value: Any?) {
            ProviderSpeechRecognitionEngine::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(engine, value)
        }

        fun segment(seconds: Float) {
            callback.onVoiceEnd(ByteArray(44 + (seconds * 16_000 * 2).toInt()), SegmentEndReason.SILENCE_DETECTED, seconds)
        }

        override fun close() {
            try { engine.cancel() } finally { detectors.close() }
        }
    }
}
