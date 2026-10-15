package io.github.nanomuse.speech

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import com.openminis.app.speech.VoiceActivityDetector
import java.io.IOException
import java.lang.reflect.Field
import java.util.Locale
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito

class SystemSpeechPipeOwnershipTest {
    @Test fun `a retired pipe write failure does not disable the next recognizer pipe`() {
        Fixture().use { fixture ->
            val old = fixture.streams.constructed().single()
            Mockito.doAnswer {
                // The capture write was in flight when the main thread replaced its pipe.
                fixture.restart()
                throw IOException("retired reader closed")
            }.`when`(old).write(any(ByteArray::class.java), anyInt(), anyInt())

            fixture.feed()
            val replacement = fixture.streams.constructed().last()
            fixture.feed()
            Mockito.verify(replacement).write(fixture.frame, 0, fixture.frame.size)
            Mockito.verify(old).close()
        }
    }

    @Test fun `failure of the current pipe stops further writes to that pipe`() {
        Fixture().use { fixture ->
            val current = fixture.streams.constructed().single()
            Mockito.doThrow(IOException("reader closed")).`when`(current)
                .write(any(ByteArray::class.java), anyInt(), anyInt())
            fixture.feed()
            fixture.feed()
            Mockito.verify(current).write(fixture.frame, 0, fixture.frame.size)
        }
    }

    @Test fun `successful frames keep flowing to the current pipe`() {
        Fixture().use { fixture ->
            fixture.feed()
            fixture.feed()
            Mockito.verify(fixture.streams.constructed().single(), Mockito.times(2))
                .write(fixture.frame, 0, fixture.frame.size)
        }
    }

    /** Real startup, sink and restart code; mocked framework pipes never touch native audio. */
    private class Fixture : AutoCloseable {
        private val sdk = Build.VERSION::class.java.getDeclaredField("SDK_INT").apply { isAccessible = true }
        private val savedSdk = sdk.getInt(null)
        private val unsafeType = Class.forName("sun.misc.Unsafe")
        private val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        private val sdkBase = unsafeType.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, sdk)
        private val sdkOffset = unsafeType.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, sdk) as Long
        private val writeSdk = unsafeType.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val loopers = Mockito.mockStatic(Looper::class.java)
        // Keep recognizer service work queued; this test exercises pipe publication and writes.
        private val handlers = Mockito.mockConstruction(Handler::class.java)
        private val pipes = Mockito.mockStatic(ParcelFileDescriptor::class.java)
        val streams = Mockito.mockConstruction(ParcelFileDescriptor.AutoCloseOutputStream::class.java)
        private var sink: ((ByteArray, Int) -> Unit)? = null
        private val detectors = Mockito.mockConstruction(VoiceActivityDetector::class.java) { detector, _ ->
            Mockito.doAnswer { invocation ->
                sink = invocation.getArgument(0)
                null
            }.`when`(detector).rawAudioSink = any()
        }
        val frame = byteArrayOf(1, 2, 3, 4)
        private val engine = SystemSpeechRecognitionEngine(Mockito.mock(Context::class.java))

        init {
            try {
                writeSdk.invoke(unsafe, sdkBase, sdkOffset, Build.VERSION_CODES.TIRAMISU)
                pipes.`when`<Array<ParcelFileDescriptor>> { ParcelFileDescriptor.createPipe() }.thenAnswer {
                    arrayOf(Mockito.mock(ParcelFileDescriptor::class.java), Mockito.mock(ParcelFileDescriptor::class.java))
                }
                // Enter after permission checking; the actual startup installs the audio sink.
                invoke("startEndpointingVad")
                SystemSpeechRecognitionEngine::class.java.getDeclaredField("continuousLocale")
                    .apply { isAccessible = true }.set(engine, Locale.US)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun invoke(name: String) {
            SystemSpeechRecognitionEngine::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(engine)
        }

        fun restart() = invoke("restartRecogniserForNextSegment")
        fun feed() = checkNotNull(sink)(frame, frame.size)

        override fun close() {
            try { engine.cancel() } finally {
                detectors.close()
                streams.close()
                pipes.close()
                handlers.close()
                loopers.close()
                writeSdk.invoke(unsafe, sdkBase, sdkOffset, savedSdk)
            }
        }
    }
}
