package io.github.nanomuse.net

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A loopback server for the cancellation tests: it accepts one connection, reads whatever the
 * client sends and never answers, so the only way the line ends is the client hanging up.
 * [accepted] opens once the client is connected; [closedByClient] once the client closed the
 * socket (the read returned end of stream).
 */
class HoldingServer : AutoCloseable {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val accepted = CountDownLatch(1)
    val closedByClient = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "holding-server") {
            runCatching {
                socket.accept().use { client ->
                    accepted.countDown()
                    client.soTimeout = 60_000
                    val input = client.getInputStream()
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) {
                        // the request arrives here; keep reading until the client hangs up
                    }
                    closedByClient.countDown()
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }
}
