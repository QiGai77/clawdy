package io.github.nanomuse.cloud

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.nanomuse.net.HoldingServer
import io.github.nanomuse.net.BodyHoldingServer
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay client's requests follow their coroutine: leaving the sign-in page while the code
 * is being asked for hangs up instead of parking an IO thread until the read timeout (#336).
 */
class NanoMuseCloudCancelTest {

    /** A context whose preferences live in a map: enough for [NanoMuseCloud.baseUrl]. */
    private class PrefsContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MapPrefs>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) { MapPrefs() }
        override fun getApplicationContext(): Context = this
    }

    private class MapPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }

    @Test fun `cancelling the code request hangs up on the relay at once`() = runBlocking {
        HoldingServer().use { server ->
            val context = PrefsContext()
            NanoMuseCloud.setBaseUrl(context, "http://127.0.0.1:${server.port}")
            // on another thread: the latch waits below block this one
            val job = launch(Dispatchers.Default) { NanoMuseCloud.requestCode(context, "someone") }
            assertTrue("the request never reached the relay", server.accepted.await(5, TimeUnit.SECONDS))
            job.cancel()
            // Before #336 the socket stayed open until the client's 30 s read timeout.
            assertTrue("the relay connection stayed open after the cancel", server.closedByClient.await(1, TimeUnit.SECONDS))
            job.join()
        }
    }

    @Test fun `cancelling while reading a successful relay body closes the call`() = runBlocking {
        cancelDuringBodyRead(200)
    }

    @Test fun `cancelling while reading a relay error body closes the call`() = runBlocking {
        cancelDuringBodyRead(403)
    }

    private suspend fun cancelDuringBodyRead(status: Int) = kotlinx.coroutines.coroutineScope {
        BodyHoldingServer(status).use { server ->
            val context = PrefsContext()
            NanoMuseCloud.setBaseUrl(context, "http://127.0.0.1:${server.port}")
            val job = launch(Dispatchers.Default) {
                try {
                    NanoMuseCloud.requestCode(context, "someone")
                } catch (error: IOException) {
                    // A failing regression closes the fixture in finally. Do not let its EOF
                    // replace the assertion while the canceled baseline is still reading.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
            try {
                assertTrue("the relay body reader was not entered", server.awaitBodyReader("NanoMuseCloud.kt", "string"))
                job.cancel()
                assertTrue("the relay body stayed open after cancel", server.closedByClient.await(1, TimeUnit.SECONDS))
                withTimeout(1_000) { job.join() }
            } finally {
                job.cancel()
                server.close()
                withTimeout(5_000) { job.join() }
            }
        }
    }

    @Test fun `relay HTTP errors preserve their code and metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"limited","message":"fixture limit","retry_after":3,"paused":true}}""",
            ))
            val context = PrefsContext()
            NanoMuseCloud.setBaseUrl(context, server.url("/").toString().trimEnd('/'))
            try {
                NanoMuseCloud.requestCode(context, "someone")
                fail("the relay error must be delivered")
            } catch (error: NanoMuseCloud.CloudException) {
                assertEquals("limited", error.code)
                assertEquals(429, error.status)
                assertEquals(3, error.retryAfterS)
                assertTrue(error.paused)
            }
        }
    }
}
