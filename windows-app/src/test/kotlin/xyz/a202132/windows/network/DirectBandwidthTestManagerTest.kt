package xyz.a202132.windows.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectBandwidthTestManagerTest {
    @Test
    fun `tests direct download and upload against configured endpoints`() {
        val payload = ByteArray(256 * 1024) { 7 }
        val uploadedBytes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/__down") { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            createContext("/__up") { exchange ->
                val received = exchange.requestBody.use { it.readAllBytes().size }
                uploadedBytes.set(received)
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
            ).test(sizeBytes = payload.size, timeoutMs = 5_000)

            assertTrue(result.downloadMbps > 0f)
            assertTrue(result.uploadMbps > 0f)
            assertEquals(payload.size, uploadedBytes.get())
        } finally {
            server.stop(0)
        }
    }
}
