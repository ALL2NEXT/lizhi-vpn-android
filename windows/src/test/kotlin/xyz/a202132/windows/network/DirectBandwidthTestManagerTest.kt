package xyz.a202132.windows.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

class DirectBandwidthTestManagerTest {
    @Test
    fun `tests direct download and upload against configured endpoints`() {
        val payload = ByteArray(256 * 1024) { 7 }
        val uploadedBytes = AtomicLong()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/__down") { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            createContext("/__up") { exchange ->
                val buffer = ByteArray(payload.size)
                var received = 0
                exchange.requestBody.use { input ->
                    while (received < buffer.size) {
                        val count = input.read(buffer, received, buffer.size - received)
                        if (count < 0) break
                        received += count
                    }
                }
                uploadedBytes.set(received.toLong())
                exchange.sendResponseHeaders(if (received == payload.size) 200 else 400, 0)
                exchange.responseBody.close()
            }
            start()
        }

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val result = DirectBandwidthTestManager(
                downloadUrl = "$baseUrl/__down",
                uploadUrl = "$baseUrl/__up"
            ).test(durationMs = 100)

            assertTrue(result.downloadMbps > 0f)
            assertTrue(result.uploadMbps > 0f)
            assertTrue(uploadedBytes.get() > 0L)
        } finally {
            server.stop(0)
        }
    }
}
