package io.github.HanJason233.chimera.security.handling

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecurityNetworkPolicyTest {
    @Test
    fun systemProxyStaysDisabledBeforeScanAndAfterNegativeFinding() {
        SecurityProxySelector.setCaptureProxyBlocked(false)
        assertEquals(
            listOf(Proxy.NO_PROXY),
            SecurityProxySelector.select(URI("https://example.com/account"))
        )
    }

    @Test
    fun remoteCleartextIsRejectedBeforeSendingCredentials() {
        val findings = mutableListOf<String>()
        var runtimeChecks = 0
        val client = policy({ runtimeChecks++ }, findings).configure(OkHttpClient.Builder()).build()
        val error = expectIoFailure {
            client.newCall(Request.Builder().url("http://example.invalid/account")
                .post("password=secret".toRequestBody()).build()).execute().close()
        }
        assertTrue(error.message.orEmpty().contains("未加密"))
        assertEquals(listOf("cleartext_traffic_blocked"), findings)
        assertEquals(0, runtimeChecks)
        close(client)
    }

    @Test
    fun unavailableNativeSecurityPreventsBusinessRequests() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = policy({ throw IOException("security unavailable") }, mutableListOf())
                .configure(OkHttpClient.Builder()).build()
            expectIoFailure {
                client.newCall(Request.Builder().url(server.url("/account")).build()).execute().close()
            }
            assertEquals(0, server.requestCount)
            close(client)
        }
    }

    @Test
    fun untrustedInterceptionCertificateNeverReceivesCredentials() {
        val certificate = certificate("127.0.0.1")
        MockWebServer().use { server ->
            server.useHttps(serverIdentity(certificate).sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val findings = mutableListOf<String>()
            val client = policy({}, findings).configure(OkHttpClient.Builder())
                .callTimeout(3, TimeUnit.SECONDS).build()
            val error = expectIoFailure {
                client.newCall(Request.Builder().url(server.url("/login"))
                    .post("password=secret".toRequestBody()).build()).execute().close()
            }
            assertTrue(isTlsValidationFailure(error))
            assertEquals(listOf("tls_validation_failed"), findings)
            assertEquals(0, server.requestCount)
            close(client)
        }
    }

    @Test
    fun trustedCertificateForAnotherHostnameIsRejected() {
        val certificate = certificate("wrong.example")
        MockWebServer().use { server ->
            server.useHttps(serverIdentity(certificate).sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val findings = mutableListOf<String>()
            val client = policy({}, findings).configure(trustingBuilder(certificate)).build()
            val error = expectIoFailure {
                client.newCall(Request.Builder().url(server.url("/account")).build()).execute().close()
            }
            assertTrue(isTlsValidationFailure(error))
            assertEquals(listOf("tls_validation_failed"), findings)
            assertEquals(0, server.requestCount)
            close(client)
        }
    }

    @Test
    fun validHttpsStillWorksWithSecurityGate() {
        val certificate = certificate("127.0.0.1")
        MockWebServer().use { server ->
            server.useHttps(serverIdentity(certificate).sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val findings = mutableListOf<String>()
            var checks = 0
            val client = policy({ checks++ }, findings).configure(trustingBuilder(certificate)).build()
            client.newCall(Request.Builder().url(server.url("/account")).build()).execute().use {
                assertEquals("ok", it.body!!.string())
            }
            assertEquals(1, checks)
            assertTrue(findings.isEmpty())
            assertEquals(1, server.requestCount)
            close(client)
        }
    }

    @Test
    fun httpsRedirectCannotDowngradeEvenToLoopback() {
        val certificate = certificate("127.0.0.1")
        MockWebServer().use { cleartext ->
            cleartext.start(InetAddress.getByName("127.0.0.1"), 0)
            MockWebServer().use { secure ->
                secure.useHttps(serverIdentity(certificate).sslSocketFactory(), false)
                secure.enqueue(MockResponse().setResponseCode(302)
                    .addHeader("Location", cleartext.url("/stolen")))
                secure.start(InetAddress.getByName("127.0.0.1"), 0)
                val client = policy({}, mutableListOf()).configure(trustingBuilder(certificate)).build()
                client.newCall(Request.Builder().url(secure.url("/account")).build()).execute().use {
                    assertEquals(302, it.code)
                }
                assertFalse(client.followSslRedirects)
                assertEquals(0, cleartext.requestCount)
                close(client)
            }
        }
    }

    private fun policy(check: () -> Unit, findings: MutableList<String>) =
        RequestSecurityPolicy(checkRuntime = check, reportFinding = { findings.add(it) })

    private fun certificate(host: String): HeldCertificate =
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).build()

    private fun serverIdentity(certificate: HeldCertificate): HandshakeCertificates =
        HandshakeCertificates.Builder().heldCertificate(certificate).build()

    private fun trustingBuilder(certificate: HeldCertificate): OkHttpClient.Builder {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).callTimeout(3, TimeUnit.SECONDS)
    }

    private fun expectIoFailure(action: () -> Unit): IOException {
        try {
            action()
        } catch (error: IOException) {
            return error
        }
        fail("Expected a rejected network request")
        error("unreachable")
    }

    private fun close(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
