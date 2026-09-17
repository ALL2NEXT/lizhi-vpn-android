package xyz.a202132.app.network

import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.core.LocalSocksEndpoint
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 用于健康检查和延迟测试的轻量 SOCKS5 客户端，支持用户名密码认证。 */
internal object Socks5HttpProbe {
    data class Result(val statusCode: Int, val delayMs: Int)

    fun hasStatus(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        expectedStatus: Int,
        timeoutMs: Long
    ): Boolean = execute(endpoint, targetUrl, timeoutMs, "GET").statusCode == expectedStatus

    /** 首次建连和预热请求、后续暖连接测量请求各自使用独立超时。 */
    fun measureLatency(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        warmupTimeoutMs: Long,
        measurementTimeoutMs: Long
    ): Result {
        // 同一客户端连续请求两次：第一次建立并预热隧道，第二次只统计暖连接首包。
        require(endpoint.credentials == null) { "暖连接测速仅支持应用内部免认证 SOCKS 入站" }
        val effectiveWarmupTimeoutMs = warmupTimeoutMs.coerceIn(1L, 60_000L)
        val effectiveMeasurementTimeoutMs = measurementTimeoutMs.coerceIn(1L, 60_000L)
        val clientTimeoutMs = maxOf(effectiveWarmupTimeoutMs, effectiveMeasurementTimeoutMs)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(endpoint.address, endpoint.port)))
            .connectTimeout(clientTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(clientTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "liuying-vpn-probe")
            .get()
            .build()
        try {
            val warmupCall = client.newCall(request)
            warmupCall.timeout().timeout(effectiveWarmupTimeoutMs, TimeUnit.MILLISECONDS)
            warmupCall.execute().use { response ->
                // 读取完预热响应，确保 OkHttp 能复用 TCP/TLS 连接。
                val input = response.body?.byteStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input != null && input.read(buffer) != -1) Unit
            }

            val measurementCall = client.newCall(request)
            measurementCall.timeout().timeout(effectiveMeasurementTimeoutMs, TimeUnit.MILLISECONDS)
            val startedAt = System.nanoTime()
            return measurementCall.execute().use { response ->
                val headersAt = System.nanoTime()
                Result(
                    response.code,
                    ((headersAt - startedAt) / NANOS_PER_MILLISECOND)
                        .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                )
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun execute(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        timeoutMs: Long,
        method: String
    ): Result {
        val target = URI(targetUrl)
        val host = requireNotNull(target.host) { "Probe URL has no host" }
        val secure = when (target.scheme?.lowercase()) {
            "http" -> false
            "https" -> true
            else -> error("Unsupported probe URL scheme: ${target.scheme}")
        }
        val port = if (target.port >= 0) target.port else if (secure) 443 else 80
        val timeoutNanos = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()) * NANOS_PER_MILLISECOND
        val startedAt = System.nanoTime()
        val deadline = startedAt + timeoutNanos

        val socket = Socket()
        try {
            socket.soTimeout = remainingMillis(deadline)
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), remainingMillis(deadline))
            negotiate(socket, endpoint, host, port)

            val transport = if (secure) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true) as SSLSocket).apply {
                    soTimeout = remainingMillis(deadline)
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    startHandshake()
                }
            } else {
                socket
            }
            transport.use {
                it.soTimeout = remainingMillis(deadline)
                writeRequest(it, target, host, port, secure, method)
                val statusCode = readStatus(it)
                val completedAt = System.nanoTime()
                if (completedAt > deadline) throw SocketTimeoutException("HTTP 探测超时")
                val delayMs = ((completedAt - startedAt) / NANOS_PER_MILLISECOND)
                    .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                return Result(statusCode, delayMs)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun negotiate(socket: Socket, endpoint: LocalSocksEndpoint, host: String, port: Int) {
        // 此处不能使用缓冲流，否则预读的 SOCKS 响应字节在套接字交给 TLS/HTTP 后会丢失。
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val credentials = endpoint.credentials
        val method = if (credentials == null) NO_AUTH else USER_PASSWORD
        output.write(byteArrayOf(VERSION, 1, method))
        output.flush()
        val greeting = input.readExactly(2)
        require(greeting[0] == VERSION && greeting[1] == method) {
            "SOCKS5 authentication method was rejected"
        }

        if (credentials != null) {
            val username = credentials.username.toByteArray(Charsets.UTF_8)
            val password = credentials.password.toByteArray(Charsets.UTF_8)
            require(username.size in 1..255 && password.size in 1..255)
            output.write(byteArrayOf(AUTH_VERSION, username.size.toByte()))
            output.write(username)
            output.write(password.size)
            output.write(password)
            output.flush()
            val authentication = input.readExactly(2)
            require(authentication[0] == AUTH_VERSION && authentication[1].toInt() == 0) {
                "SOCKS5 authentication failed"
            }
        }

        val encodedHost = IDN.toASCII(host).toByteArray(Charsets.US_ASCII)
        require(encodedHost.size in 1..255) { "SOCKS5 target host is too long" }
        output.write(byteArrayOf(VERSION, CONNECT, 0, DOMAIN, encodedHost.size.toByte()))
        output.write(encodedHost)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()

        val response = input.readExactly(4)
        require(response[0] == VERSION && response[1].toInt() == 0) {
            "SOCKS5 connect failed: ${response[1].toInt() and 0xff}"
        }
        when (response[3]) {
            IPV4 -> input.readExactly(4)
            IPV6 -> input.readExactly(16)
            DOMAIN -> input.readExactly(input.readExactly(1)[0].toInt() and 0xff)
            else -> error("Invalid SOCKS5 address type")
        }
        input.readExactly(2)
    }

    private fun writeRequest(
        socket: Socket,
        target: URI,
        host: String,
        port: Int,
        secure: Boolean,
        method: String
    ) {
        val path = buildString {
            append(target.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            target.rawQuery?.let { append('?').append(it) }
        }
        val defaultPort = if (secure) 443 else 80
        val hostHeader = if (port == defaultPort) host else "$host:$port"
        socket.getOutputStream().write(
            "$method $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\nUser-Agent: liuying-vpn-probe\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
        )
        socket.getOutputStream().flush()
    }

    private fun readStatus(socket: Socket): Int {
        val input = BufferedInputStream(socket.getInputStream())
        val line = StringBuilder()
        while (line.length < MAX_STATUS_LINE) {
            val value = input.read()
            if (value < 0) throw EOFException("HTTP status line ended early")
            if (value == '\n'.code) break
            if (value != '\r'.code) line.append(value.toChar())
        }
        require(line.length < MAX_STATUS_LINE) { "HTTP status line is too long" }
        return line.toString().split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("Invalid HTTP status line")
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw EOFException("SOCKS5 response ended early")
            offset += count
        }
        return result
    }

    private fun remainingMillis(deadline: Long): Int {
        val remainingNanos = deadline - System.nanoTime()
        check(remainingNanos > 0) { "HTTP 探测超时" }
        return ((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private const val MAX_STATUS_LINE = 8_192
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private const val VERSION: Byte = 5
    private const val AUTH_VERSION: Byte = 1
    private const val NO_AUTH: Byte = 0
    private const val USER_PASSWORD: Byte = 2
    private const val CONNECT: Byte = 1
    private const val IPV4: Byte = 1
    private const val DOMAIN: Byte = 3
    private const val IPV6: Byte = 4
}
