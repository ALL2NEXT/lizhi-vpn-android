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
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                exchange.requestBody.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count.toLong()
                    }
                }
                uploadedBytes.set(received)
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
            start()
        }

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val result = DirectBandwidthTestManager(
                downloadUrl = "$baseUrl/__down",
                uploadUrl = "$baseUrl/__up"
            ).test(durationMs = 1_000)

            assertTrue(result.downloadMbps > 0f)
            assertTrue(result.uploadMbps > 0f)
            assertTrue(uploadedBytes.get() > 0L)
        } finally {
            server.stop(0)
        }
    }
}
