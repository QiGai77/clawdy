package io.github.nanomuse.net

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Sends response headers, then holds the entire body until the client closes the socket. */
class BodyHoldingServer(status: Int = 200) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    @Volatile private var client: Socket? = null
    val port: Int get() = server.localPort
    val headersSent = CountDownLatch(1)
    val closedByClient = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "body-holding-server") {
            runCatching {
                server.accept().use { connection ->
                    client = connection
                    connection.soTimeout = 10_000
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(length) { input.read() }
                    val output = connection.getOutputStream()
                    output.write(
                        ("HTTP/1.1 $status Fixture\r\nContent-Length: 64\r\n" +
                            "Content-Type: application/json\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII),
                    )
                    output.flush()
                    headersSent.countDown()
                    while (input.read() >= 0) { /* wait for the peer, never send the body */ }
                    closedByClient.countDown()
                }
            }
        }
    }

    /** Ensure cancellation happens inside the production body reader, after await resumed. */
    fun awaitBodyReader(sourceFile: String, method: String): Boolean {
        if (!headersSent.await(5, TimeUnit.SECONDS)) return false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().values.any { stack ->
                    stack.any { it.fileName == sourceFile } &&
                        stack.any { it.methodName == method && it.className.startsWith("okhttp3.") }
                }) return true
            Thread.sleep(5)
        }
        return false
    }

    override fun close() {
        client?.close()
        server.close()
    }
}
