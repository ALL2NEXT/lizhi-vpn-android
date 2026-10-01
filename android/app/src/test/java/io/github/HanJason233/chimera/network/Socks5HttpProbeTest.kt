package io.github.HanJason233.chimera.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.SocksCredentials
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class Socks5HttpProbeTest {
    @Test
    fun `authenticated probe performs socks connect and checks HTTP status`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "fake-socks5") {
                runCatching { serveOnce(server, "GET") }.onFailure(failure::set)
            }
            val endpoint = LocalSocksEndpoint(
                "127.0.0.1",
                server.localPort,
                SocksCredentials("vpn", "secret")
            )

            assertTrue(Socks5HttpProbe.hasStatus(endpoint, "http://example.com/generate_204", 204, 2_000))
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Fake SOCKS5 server failed", it) }
        }
    }

    @Test
    fun `authenticated fetch reads response body for unlock detection`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "fake-socks5-body") {
                runCatching { serveBodyResponse(server) }.onFailure(failure::set)
            }
            val endpoint = LocalSocksEndpoint(
                "127.0.0.1",
                server.localPort,
                SocksCredentials("vpn", "secret")
            )

            val response = Socks5HttpProbe.fetch(
                endpoint,
                "http://example.com/unlock",
                timeoutMs = 2_000,
                headers = mapOf("User-Agent" to "unlock-test")
            )

            assertEquals(200, response.statusCode)
            assertEquals("Gemini supported", response.body)
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Fake SOCKS5 server failed", it) }
        }
    }

    @Test
    fun `latency probe reuses one socks tunnel for two get requests`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "fake-socks5-warm-latency") {
                runCatching { serveWarmProbe(server, warmupDelayMs = 700) }.onFailure(failure::set)
            }
            val endpoint = LocalSocksEndpoint.unauthenticated(server.localPort)

            val result = Socks5HttpProbe.measureLatency(
                endpoint,
                "http://warm.test/generate_204",
                warmupTimeoutMs = 2_000,
                measurementTimeoutMs = 500
            )

            assertEquals(204, result.statusCode)
            assertTrue(result.delayMs > 0)
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Fake warm SOCKS5 server failed", it) }
        }
    }

    private fun serveOnce(server: ServerSocket, method: String) {
        server.accept().use { socket ->
            socket.soTimeout = 2_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            assertEquals(listOf(5, 1, 2), input.readExactly(3))
            output.write(byteArrayOf(5, 2))
            output.flush()

            assertEquals(1, input.read())
            val username = input.readExactly(input.read())
            val password = input.readExactly(input.read())
            assertEquals("vpn", username.toAscii())
            assertEquals("secret", password.toAscii())
            output.write(byteArrayOf(1, 0))
            output.flush()

            assertEquals(listOf(5, 1, 0, 3), input.readExactly(4))
            val host = input.readExactly(input.read()).toAscii()
            val port = (input.read() shl 8) or input.read()
            assertEquals("example.com", host)
            assertEquals(80, port)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()

            val request = buildString {
                while (true) {
                    val value = input.read()
                    check(value >= 0)
                    append(value.toChar())
                    if (endsWith("\r\n\r\n")) break
                }
            }
            assertTrue(request.startsWith("$method /generate_204 HTTP/1.1\r\n"))
            output.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private fun serveWarmProbe(server: ServerSocket, warmupDelayMs: Long = 0) {
        server.accept().use { socket ->
            socket.soTimeout = 2_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            assertEquals(5, input.read())
            val methods = input.readExactly(input.read())
            assertTrue(methods.contains(0))
            output.write(byteArrayOf(5, 0))
            output.flush()

            assertEquals(listOf(5, 1, 0, 3), input.readExactly(4))
            val host = input.readExactly(input.read()).toAscii()
            val port = (input.read() shl 8) or input.read()
            assertEquals("warm.test", host)
            assertEquals(80, port)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()

            repeat(2) { index ->
                val request = readHttpHeaders(input)
                assertTrue(request.startsWith("GET /generate_204 HTTP/1.1\r\n"))
                if (index == 0 && warmupDelayMs > 0) Thread.sleep(warmupDelayMs)
                val connection = if (index == 0) "keep-alive" else "close"
                output.write(
                    "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: $connection\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
            }
        }
    }

    private fun serveBodyResponse(server: ServerSocket) {
        server.accept().use { socket ->
            socket.soTimeout = 2_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            assertEquals(listOf(5, 1, 2), input.readExactly(3))
            output.write(byteArrayOf(5, 2))
            output.flush()

            assertEquals(1, input.read())
            assertEquals("vpn", input.readExactly(input.read()).toAscii())
            assertEquals("secret", input.readExactly(input.read()).toAscii())
            output.write(byteArrayOf(1, 0))
            output.flush()

            assertEquals(listOf(5, 1, 0, 3), input.readExactly(4))
            assertEquals("example.com", input.readExactly(input.read()).toAscii())
            assertEquals(80, (input.read() shl 8) or input.read())
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()

            val request = readHttpHeaders(input)
            assertTrue(request.startsWith("GET /unlock HTTP/1.1\r\n"))
            assertTrue(request.contains("User-Agent: unlock-test\r\n"))
            val body = "Gemini supported"
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n".toByteArray()
            )
            output.write("Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body.toByteArray())
            output.flush()
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): String = buildString {
        while (true) {
            val value = input.read()
            check(value >= 0)
            append(value.toChar())
            if (endsWith("\r\n\r\n")) return@buildString
        }
    }

    private fun BufferedInputStream.readExactly(size: Int): List<Int> =
        List(size) { read().also { check(it >= 0) } }

    private fun List<Int>.toAscii(): String = map(Int::toByte).toByteArray().toString(Charsets.US_ASCII)
}
