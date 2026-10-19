package io.github.HanJason233.chimera.network.nodetest.common

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.FailoverPingTarget
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 用于健康检查和延迟测试的轻量 SOCKS5 客户端，支持用户名密码认证。 */
internal object Socks5HttpProbe {
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    data class Result(val statusCode: Int, val delayMs: Int)

    data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val location: String?,
        val delayMs: Int
    )

    /** 关闭进行中的 IP/服务探测和 URL Test 请求。 */
    fun cancelActiveRequests() {
        activeCalls.toList().forEach { call -> call.cancel() }
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
    }

    /**
     * 通过支持用户名/密码认证的 SOCKS5 隧道读取受限大小的 HTTP 响应。
     * 域名由代理端解析，避免检测请求绕过当前节点的 DNS 路径。
     */
    fun fetch(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES
    ): HttpResponse {
        require(maxBodyBytes in 1..MAX_ALLOWED_BODY_BYTES)
        var currentUrl = targetUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = fetchOnce(endpoint, currentUrl, timeoutMs, headers, maxBodyBytes)
            val location = response.location
            if (!followRedirects || response.statusCode !in REDIRECT_CODES || location.isNullOrBlank()) {
                return response
            }
            check(redirectCount < MAX_REDIRECTS) { "HTTP 重定向次数过多" }
            currentUrl = URI(currentUrl).resolve(location).toString()
        }
        error("HTTP 重定向次数过多")
    }

    fun hasStatus(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        expectedStatus: Int,
        timeoutMs: Long
    ): Boolean = execute(endpoint, targetUrl, timeoutMs, "GET").statusCode == expectedStatus

    /** One bounded HTTP request for runtime health checks; response bodies are not downloaded. */
    fun probeHttpStatusCode(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        timeoutMs: Long
    ): Int = execute(endpoint, targetUrl, timeoutMs, "GET").statusCode

    /** 通过当前 SOCKS5 出站连接目标端口，以 CONNECT 建立耗时作为代理 TCP Ping 延迟。 */
    fun probeTcpConnect(
        endpoint: LocalSocksEndpoint,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Long
    ): Int {
        require(targetPort in 1..65_535) { "Invalid ping target port" }
        val host = requireNotNull(FailoverPingTarget.normalizeHost(targetHost)) {
            "Invalid ping target host"
        }
        val timeoutNanos = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()) * NANOS_PER_MILLISECOND
        val startedAt = System.nanoTime()
        val deadline = startedAt + timeoutNanos
        val socket = Socket()
        activeSockets.add(socket)
        try {
            socket.soTimeout = remainingMillis(deadline)
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), remainingMillis(deadline))
            socket.soTimeout = remainingMillis(deadline)
            negotiate(socket, endpoint, host, targetPort, deadline)
            val completedAt = System.nanoTime()
            if (completedAt > deadline) throw SocketTimeoutException("代理 TCP Ping 超时")
            return ((completedAt - startedAt) / NANOS_PER_MILLISECOND)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

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
            activeCalls.add(warmupCall)
            warmupCall.timeout().timeout(effectiveWarmupTimeoutMs, TimeUnit.MILLISECONDS)
            try {
                warmupCall.execute().use { response ->
                    // 读取完预热响应，确保 OkHttp 能复用 TCP/TLS 连接。
                    val input = response.body?.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (input != null && input.read(buffer) != -1) Unit
                }
            } finally {
                activeCalls.remove(warmupCall)
            }

            val measurementCall = client.newCall(request)
            activeCalls.add(measurementCall)
            measurementCall.timeout().timeout(effectiveMeasurementTimeoutMs, TimeUnit.MILLISECONDS)
            val startedAt = System.nanoTime()
            try {
                return measurementCall.execute().use { response ->
                    val headersAt = System.nanoTime()
                    Result(
                        response.code,
                        ((headersAt - startedAt) / NANOS_PER_MILLISECOND)
                            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    )
                }
            } finally {
                activeCalls.remove(measurementCall)
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

    private fun fetchOnce(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        timeoutMs: Long,
        headers: Map<String, String>,
        maxBodyBytes: Int
    ): HttpResponse {
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
        activeSockets.add(socket)
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
                writeRequest(it, target, host, port, secure, "GET", headers)
                val input = BufferedInputStream(it.getInputStream())
                val statusLine = readHttpLine(input)
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: error("Invalid HTTP status line")
                val responseHeaders = readHeaders(input)
                val headersAt = System.nanoTime()
                val bodyBytes = readResponseBody(input, statusCode, responseHeaders, maxBodyBytes)
                if (System.nanoTime() > deadline) throw SocketTimeoutException("HTTP 探测超时")
                return HttpResponse(
                    statusCode = statusCode,
                    body = decodeBody(bodyBytes, responseHeaders, maxBodyBytes),
                    location = responseHeaders["location"],
                    delayMs = ((headersAt - startedAt) / NANOS_PER_MILLISECOND)
                        .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                )
            }
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(MAX_HEADER_COUNT) {
            val line = readHttpLine(input)
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
        }
        error("HTTP response has too many headers")
    }

    private fun readResponseBody(
        input: BufferedInputStream,
        statusCode: Int,
        headers: Map<String, String>,
        limit: Int
    ): ByteArray {
        if (statusCode in 100..199 || statusCode == 204 || statusCode == 304) return ByteArray(0)
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunkedBody(input, limit)
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        return readLimited(input, limit, contentLength)
    }

    private fun readChunkedBody(input: BufferedInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        while (output.size() < limit) {
            val chunkSize = readHttpLine(input).substringBefore(';').trim().toLongOrNull(16)
                ?: error("Invalid HTTP chunk size")
            if (chunkSize == 0L) {
                while (readHttpLine(input).isNotEmpty()) Unit
                break
            }
            var remaining = chunkSize
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0 && output.size() < limit) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), remaining, (limit - output.size()).toLong()).toInt()
                )
                if (count < 0) throw EOFException("HTTP chunk ended early")
                output.write(buffer, 0, count)
                remaining -= count
            }
            if (output.size() >= limit) break
            require(input.read() == '\r'.code && input.read() == '\n'.code) { "Invalid HTTP chunk ending" }
        }
        return output.toByteArray()
    }

    private fun readLimited(input: InputStream, limit: Int, expectedLength: Long? = null): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = minOf(expectedLength ?: Long.MAX_VALUE, limit.toLong())
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun decodeBody(
        body: ByteArray,
        headers: Map<String, String>,
        limit: Int
    ): String {
        val decoded = runCatching {
            val source = ByteArrayInputStream(body)
            val stream: InputStream = when (headers["content-encoding"]?.lowercase()) {
                "gzip", "x-gzip" -> GZIPInputStream(source)
                "deflate" -> InflaterInputStream(source)
                else -> source
            }
            stream.use { readLimited(it, limit) }
        }.getOrDefault(body)
        val charsetName = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(headers["content-type"].orEmpty())?.groupValues?.getOrNull(1)?.trim('"', '\'')
        val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return decoded.toString(charset)
    }

    private fun readHttpLine(input: InputStream): String {
        val line = StringBuilder()
        while (line.length < MAX_STATUS_LINE) {
            val value = input.read()
            if (value < 0) throw EOFException("HTTP response ended early")
            if (value == '\n'.code) return line.toString()
            if (value != '\r'.code) line.append(value.toChar())
        }
        error("HTTP line is too long")
    }

    private fun negotiate(
        socket: Socket,
        endpoint: LocalSocksEndpoint,
        host: String,
        port: Int,
        deadlineNanos: Long? = null
    ) {
        // 此处不能使用缓冲流，否则预读的 SOCKS 响应字节在套接字交给 TLS/HTTP 后会丢失。
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val credentials = endpoint.credentials
        val method = if (credentials == null) NO_AUTH else USER_PASSWORD
        fun readExactly(size: Int): ByteArray {
            deadlineNanos?.let { socket.soTimeout = remainingMillis(it) }
            return input.readExactly(size)
        }
        output.write(byteArrayOf(VERSION, 1, method))
        output.flush()
        val greeting = readExactly(2)
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
            val authentication = readExactly(2)
            require(authentication[0] == AUTH_VERSION && authentication[1].toInt() == 0) {
                "SOCKS5 authentication failed"
            }
        }

        val (addressType, encodedAddress) = encodeSocks5Address(host)
        output.write(byteArrayOf(VERSION, CONNECT, 0, addressType))
        if (addressType == DOMAIN) output.write(encodedAddress.size)
        output.write(encodedAddress)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()

        val response = readExactly(4)
        require(response[0] == VERSION && response[1].toInt() == 0) {
            "SOCKS5 connect failed: ${response[1].toInt() and 0xff}"
        }
        when (response[3]) {
            IPV4 -> readExactly(4)
            IPV6 -> readExactly(16)
            DOMAIN -> readExactly(readExactly(1)[0].toInt() and 0xff)
            else -> error("Invalid SOCKS5 address type")
        }
        readExactly(2)
    }

    /** SOCKS5 域名以域名类型发送，避免客户端本地解析绕过代理的 DNS。 */
    private fun encodeSocks5Address(host: String): Pair<Byte, ByteArray> {
        val unwrappedHost = if (host.startsWith('[') && host.endsWith(']')) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
        if (':' in unwrappedHost) {
            require('%' !in unwrappedHost) { "Scoped IPv6 addresses are not supported" }
            val address = InetAddress.getByName(unwrappedHost).address
            require(address.size == 16) { "Invalid IPv6 target host" }
            return IPV6 to address
        }

        if (unwrappedHost.all { it.isDigit() || it == '.' }) {
            val octets = unwrappedHost.split('.')
            require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 }) {
                "Invalid IPv4 target host"
            }
            val address = octets.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } }
            require(address.all { it != null }) { "Invalid IPv4 target host" }
            return IPV4 to address.map { it!!.toByte() }.toByteArray()
        }

        val encodedHost = IDN.toASCII(unwrappedHost, IDN.USE_STD3_ASCII_RULES)
            .toByteArray(Charsets.US_ASCII)
        require(encodedHost.size in 1..255) { "SOCKS5 target host is too long" }
        return DOMAIN to encodedHost
    }

    private fun writeRequest(
        socket: Socket,
        target: URI,
        host: String,
        port: Int,
        secure: Boolean,
        method: String,
        headers: Map<String, String> = emptyMap()
    ) {
        val path = buildString {
            append(target.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            target.rawQuery?.let { append('?').append(it) }
        }
        val defaultPort = if (secure) 443 else 80
        val hostHeader = if (port == defaultPort) host else "$host:$port"
        val effectiveHeaders = linkedMapOf(
            "Host" to hostHeader,
            "Connection" to "close",
            "Accept-Encoding" to "identity",
            "User-Agent" to "liuying-vpn-probe"
        )
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && '\r' !in name && '\n' !in name)
            require('\r' !in value && '\n' !in value)
            if (!name.equals("Host", ignoreCase = true) && !name.equals("Connection", ignoreCase = true)) {
                effectiveHeaders.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                    ?.let(effectiveHeaders::remove)
                effectiveHeaders[name] = value
            }
        }
        val encodedRequest = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            effectiveHeaders.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().write(
            encodedRequest
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
    private const val MAX_HEADER_COUNT = 100
    private const val MAX_REDIRECTS = 5
    private const val DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_ALLOWED_BODY_BYTES = 4 * 1024 * 1024
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private const val VERSION: Byte = 5
    private const val AUTH_VERSION: Byte = 1
    private const val NO_AUTH: Byte = 0
    private const val USER_PASSWORD: Byte = 2
    private const val CONNECT: Byte = 1
    private const val IPV4: Byte = 1
    private const val DOMAIN: Byte = 3
    private const val IPV6: Byte = 4
}
