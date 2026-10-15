package io.github.nanomuse.speech

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionEngine
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import java.lang.reflect.Field
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

class SystemSpeechRecognizerFallbackTest {
    @Test fun `unavailable language on an already regular recognizer is reported without another fallback`() {
        assertCreationFallbackReportsError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            "Language pack unavailable — download it in the system speech settings")
    }

    @Test fun `unsupported language on an already regular recognizer is reported without another fallback`() {
        assertCreationFallbackReportsError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            "Language not supported by the recognizer")
    }

    private fun assertCreationFallbackReportsError(code: Int, expectedMessage: String) {
        Fixture(onDeviceThrows = true).use { fixture ->
            fixture.start(offline = true)
            assertEquals(1, fixture.onDeviceCreations)
            assertEquals(1, fixture.regularCreations)
            assertTrue(fixture.errors.isEmpty())
            fixture.callback!!.onError(code)
            assertEquals("Already using regular after the on-device factory threw", 1, fixture.regularCreations)
            assertEquals(listOf(RecognitionError.LANGUAGE_UNSUPPORTED), fixture.errors)
            assertEquals(listOf(expectedMessage), fixture.messages)
        }
    }

    @Test fun `a real on-device recognizer still falls back once for a missing language`() {
        Fixture(onDeviceThrows = false).use { fixture ->
            fixture.start(offline = true)
            assertEquals(1, fixture.onDeviceCreations)
            assertEquals(0, fixture.regularCreations)
            fixture.callback!!.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            assertEquals(1, fixture.regularCreations)
            assertTrue(fixture.errors.isEmpty())
            fixture.callback!!.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            assertEquals(1, fixture.onDeviceCreations)
            assertEquals(1, fixture.regularCreations)
            assertEquals(listOf(RecognitionError.LANGUAGE_UNSUPPORTED), fixture.errors)
        }
    }

    @Test fun `online selection reports the language error without creating an on-device recognizer`() {
        Fixture(onDeviceThrows = false).use { fixture ->
            fixture.start(offline = false)
            fixture.callback!!.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            assertEquals(0, fixture.onDeviceCreations)
            assertEquals(1, fixture.regularCreations)
            assertEquals(listOf(RecognitionError.LANGUAGE_UNSUPPORTED), fixture.errors)
        }
    }

    @Test fun `a real on-device recognizer still falls back once for an unsupported language`() {
        Fixture(onDeviceThrows = false).use { fixture ->
            fixture.start(offline = true)
            fixture.callback!!.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            assertEquals(1, fixture.onDeviceCreations)
            assertEquals(1, fixture.regularCreations)
            assertTrue(fixture.errors.isEmpty())
            fixture.callback!!.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            assertEquals(1, fixture.regularCreations)
            assertEquals(listOf(RecognitionError.LANGUAGE_UNSUPPORTED), fixture.errors)
        }
    }

    @Test fun `a disconnected recognition service is not retried as a missing language pack`() {
        Fixture(onDeviceThrows = false).use { fixture ->
            fixture.start(offline = true)
            fixture.callback!!.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            assertEquals(1, fixture.onDeviceCreations)
            assertEquals(0, fixture.regularCreations)
            assertEquals(listOf(RecognitionError.UNKNOWN), fixture.errors)
        }
    }

    /** Only framework factories/dispatch are replaced; the original engine and callbacks execute. */
    private class Fixture(private val onDeviceThrows: Boolean) : AutoCloseable {
        private val sdk = Build.VERSION::class.java.getDeclaredField("SDK_INT").apply { isAccessible = true }
        private val savedSdk = sdk.getInt(null)
        // The mockable android.jar retains this static final field. Change only this JVM's
        // stub SDK during the fixture and restore it before another test can observe it.
        private val unsafeType = Class.forName("sun.misc.Unsafe")
        private val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        private val sdkBase = unsafeType.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, sdk)
        private val sdkOffset = unsafeType.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, sdk) as Long
        private val writeSdk = unsafeType.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val loopers = Mockito.mockStatic(Looper::class.java)
        private val handlers = Mockito.mockConstruction(Handler::class.java) { handler, _ ->
            Mockito.`when`(handler.post(any(Runnable::class.java))).thenAnswer { invocation ->
                invocation.getArgument<Runnable>(0).run()
                true
            }
        }
        private val factories = Mockito.mockStatic(SpeechRecognizer::class.java)
        private val context = Mockito.mock(Context::class.java)
        val errors = mutableListOf<RecognitionError>()
        val messages = mutableListOf<String?>()
        var onDeviceCreations = 0
            private set
        var regularCreations = 0
            private set
        var callback: RecognitionListener? = null
            private set
        private var engine: SystemSpeechRecognitionEngine? = null

        init {
            try {
                writeSdk.invoke(unsafe, sdkBase, sdkOffset, Build.VERSION_CODES.S)
                loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
                factories.`when`<SpeechRecognizer> { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.thenAnswer {
                    onDeviceCreations++
                    if (onDeviceThrows) throw UnsupportedOperationException("fixture: on-device service unavailable")
                    recognizer()
                }
                factories.`when`<SpeechRecognizer> { SpeechRecognizer.createSpeechRecognizer(context) }.thenAnswer {
                    regularCreations++
                    recognizer()
                }
                engine = SystemSpeechRecognitionEngine(context)
                val observer = object : SpeechRecognitionEngine.Listener {
                    override fun onPartial(text: String) {}
                    override fun onFinal(text: String) {}
                    override fun onError(error: RecognitionError, message: String?) {
                        errors += error
                        messages += message
                    }
                }
                SystemSpeechRecognitionEngine::class.java.getDeclaredField("listener")
                    .apply { isAccessible = true }.set(engine, observer)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun recognizer(): SpeechRecognizer = Mockito.mock(SpeechRecognizer::class.java).also { recognizer ->
            Mockito.doAnswer { invocation ->
                callback = invocation.getArgument(0)
                null
            }.`when`(recognizer).setRecognitionListener(any(RecognitionListener::class.java))
        }

        fun start(offline: Boolean) {
            val engine = checkNotNull(engine)
            engine.preferOffline = offline
            // Enter after permission/capture setup: no microphone or platform service is opened.
            SystemSpeechRecognitionEngine::class.java.getDeclaredMethod("startInternal", Locale::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(engine, Locale.forLanguageTag("en-US"), true)
            Mockito.verify(handlers.constructed().single())
                .post(any(Runnable::class.java))
        }

        override fun close() {
            try { engine?.cancel() } finally {
                factories.close()
                handlers.close()
                loopers.close()
                writeSdk.invoke(unsafe, sdkBase, sdkOffset, savedSdk)
            }
        }
    }
}
