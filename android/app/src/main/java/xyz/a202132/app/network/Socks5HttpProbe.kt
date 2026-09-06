package xyz.a202132.app.network

import xyz.a202132.app.core.LocalSocksEndpoint
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Small authenticated SOCKS5 client used only for VPN-path health probes. */
internal object Socks5HttpProbe {
    fun hasStatus(
        endpoint: LocalSocksEndpoint,
        targetUrl: String,
        expectedStatus: Int,
        timeoutMs: Long
    ): Boolean {
        val target = URI(targetUrl)
        val host = requireNotNull(target.host) { "Probe URL has no host" }
        val secure = when (target.scheme?.lowercase()) {
            "http" -> false
            "https" -> true
            else -> error("Unsupported probe URL scheme: ${target.scheme}")
        }
        val port = if (target.port >= 0) target.port else if (secure) 443 else 80
        val timeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

        val socket = Socket()
        try {
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), timeout)
            negotiate(socket, endpoint, host, port)

            val transport = if (secure) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true) as SSLSocket).apply {
                    soTimeout = timeout
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    startHandshake()
                }
            } else {
                socket
            }
            transport.use {
                writeRequest(it, target, host, port, secure)
                return readStatus(it) == expectedStatus
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun negotiate(socket: Socket, endpoint: LocalSocksEndpoint, host: String, port: Int) {
        // Do not buffer here: bytes read ahead of the SOCKS response would be lost
        // when the socket is handed to TLS/HTTP below.
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

    private fun writeRequest(socket: Socket, target: URI, host: String, port: Int, secure: Boolean) {
        val path = buildString {
            append(target.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            target.rawQuery?.let { append('?').append(it) }
        }
        val defaultPort = if (secure) 443 else 80
        val hostHeader = if (port == defaultPort) host else "$host:$port"
        socket.getOutputStream().write(
            "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\nUser-Agent: liuying-vpn-probe\r\n\r\n"
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

    private const val MAX_STATUS_LINE = 8_192
    private const val VERSION: Byte = 5
    private const val AUTH_VERSION: Byte = 1
    private const val NO_AUTH: Byte = 0
    private const val USER_PASSWORD: Byte = 2
    private const val CONNECT: Byte = 1
    private const val IPV4: Byte = 1
    private const val DOMAIN: Byte = 3
    private const val IPV6: Byte = 4
}
