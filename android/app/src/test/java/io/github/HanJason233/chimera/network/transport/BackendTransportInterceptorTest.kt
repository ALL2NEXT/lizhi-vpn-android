package io.github.HanJason233.chimera.network.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackendTransportInterceptorTest {
    private class Crypto : TransportCrypto {
        val plaintexts = mutableListOf<JsonObject>()
        var created = 0
        var closed = 0
        var reject = false
        override fun create(publicKey: ByteArray): TransportSession {
            created++
            return object : TransportSession {
                override fun seal(plaintext: ByteArray): ByteArray {
                    plaintexts.add(JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject)
                    return "opaque-request".toByteArray()
                }
                override fun open(packet: ByteArray): ByteArray {
                    if (reject) throw IOException("invalid authentication tag")
                    return packet // HTTP behavior is tested independently of native/WebCrypto interop.
                }
                override fun close() { closed++ }
            }
        }
    }

    private fun client(backend: () -> String, crypto: Crypto) = OkHttpClient.Builder()
        .addInterceptor(BackendTransportInterceptor(backend, crypto, { 123456789L }))
        .addNetworkInterceptor(TransportDestinationGuard(backend)).build()

    private fun key() = MockResponse().setHeader("Content-Type", "application/pkix-key").setBody("test-public-key")

    private fun encrypted(body: String, status: Int = 200) = MockResponse()
        .setHeader("Content-Type", "application/vnd.chimera.encrypted")
        .setBody(JsonObject().apply {
            addProperty("status", status)
            add("headers", JsonArray().apply {
                add(JsonArray().apply { add("content-type"); add("text/plain; charset=utf-8") })
                add(JsonArray().apply { add("subscription-userinfo"); add("upload=1; download=2") })
            })
            addProperty("body", body.toByteArray().toByteString().base64())
        }.toString())

    @Test fun queryAndBodyAreWrappedAndAuthenticatedBusinessErrorsAreRestored() {
        MockWebServer().use { server ->
            server.enqueue(key())
            server.enqueue(encrypted("invalid_device_id", 400))
            val crypto = Crypto()
            val client = client({ server.url("/").toString() }, crypto)
            val request = Request.Builder().url(server.url("/api/account/register?deviceId=private-device"))
                .post("password=private-password".toRequestBody()).build()
            client.newCall(request).execute().use { response ->
                assertEquals(400, response.code)
                assertEquals(request.url, response.request.url)
                assertEquals("invalid_device_id", response.body!!.string())
                assertEquals("upload=1; download=2", response.header("subscription-userinfo"))
            }
            assertEquals("/api/transport/key", server.takeRequest().path)
            val transmitted = server.takeRequest()
            assertEquals("/api/transport", transmitted.path)
            assertEquals("POST", transmitted.method)
            assertEquals("opaque-request", transmitted.body.readUtf8())
            val plain = crypto.plaintexts.single()
            assertEquals("/api/account/register?deviceId=private-device", plain.get("path").asString)
            assertEquals("POST", plain.get("method").asString)
            assertEquals(1, crypto.closed)
        }
    }

    @Test fun publicKeyIsCachedAndGetRequestsAlsoCarryAnEncryptedPayload() {
        MockWebServer().use { server ->
            server.enqueue(key())
            repeat(2) { server.enqueue(encrypted("subscription")) }
            val crypto = Crypto()
            val client = client({ server.url("/").toString() }, crypto)
            repeat(2) {
                client.newCall(Request.Builder().url(server.url("/api/subscription/1?deviceId=secret")).build())
                    .execute().close()
            }
            assertEquals(3, server.requestCount)
            assertTrue(crypto.plaintexts.all { it.get("method").asString == "GET" && it.get("body").asString == "" })
            assertEquals(2, crypto.closed)
        }
    }

    @Test fun plaintextResponseAndInvalidTagFailWithoutSendingTheBusinessRequestAgain() {
        for (badTag in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(key())
            server.enqueue(if (badTag) encrypted("bad") else MockResponse().setBody("plaintext"))
            val crypto = Crypto().apply { reject = badTag }
            val client = client({ server.url("/").toString() }, crypto)
            expectFailure { client.newCall(Request.Builder().url(server.url("/api/client")).build()).execute().close() }
            assertEquals(2, server.requestCount)
            assertEquals(1, crypto.closed)
        }
    }

    @Test fun keyFailureNeverSendsCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val crypto = Crypto()
            val client = client({ server.url("/").toString() }, crypto)
            expectFailure {
                client.newCall(Request.Builder().url(server.url("/api/account/login"))
                    .post("password=secret".toRequestBody()).build()).execute().close()
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, crypto.created)
            assertEquals("/api/transport/key", server.takeRequest().path)
        }
    }

    @Test fun redirectsCannotForwardKeyOrEncryptedRequestToAnotherPath() {
        for (keyRedirect in listOf(true, false)) MockWebServer().use { server ->
            if (!keyRedirect) server.enqueue(key())
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/stolen")))
            val crypto = Crypto()
            val client = client({ server.url("/").toString() }, crypto)
            expectFailure { client.newCall(Request.Builder().url(server.url("/api/client")).build()).execute().close() }
            assertEquals(if (keyRedirect) 1 else 2, server.requestCount)
        }
    }

    @Test fun externalSubscriptionsAndOtherPortsKeepTheirExistingProtocol() {
        MockWebServer().use { backend -> MockWebServer().use { thirdParty ->
            backend.enqueue(MockResponse().setBody("same-origin-external-subscription"))
            thirdParty.enqueue(MockResponse().setBody("other-port-subscription"))
            val crypto = Crypto()
            val client = client({ backend.url("/").toString() }, crypto)
            for (url in listOf(backend.url("/external/sub"), thirdParty.url("/api/subscription/1"))) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertEquals("/external/sub", backend.takeRequest().path)
            assertEquals("/api/subscription/1", thirdParty.takeRequest().path)
            assertEquals(0, crypto.created)
        } }
    }

    @Test fun thirdPartyRedirectCannotEnterBackendWithAnUnencryptedRequest() {
        MockWebServer().use { backend -> MockWebServer().use { thirdParty ->
            thirdParty.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", backend.url("/api/client?deviceId=private")))
            val crypto = Crypto()
            val client = client({ backend.url("/").toString() }, crypto)
            expectFailure {
                client.newCall(Request.Builder().url(thirdParty.url("/subscription")).build()).execute().close()
            }
            assertEquals(0, backend.requestCount)
            assertEquals(0, crypto.created)
        } }
    }

    @Test fun switchingBackendDoesNotReuseThePreviousServersKey() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            for (server in listOf(first, second)) { server.enqueue(key()); server.enqueue(encrypted("ok")) }
            var backend = first.url("/").toString()
            val client = client({ backend }, Crypto())
            client.newCall(Request.Builder().url(first.url("/api/client")).build()).execute().close()
            backend = second.url("/").toString()
            client.newCall(Request.Builder().url(second.url("/api/client")).build()).execute().close()
            assertEquals(2, first.requestCount)
            assertEquals(2, second.requestCount)
        } }
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected encrypted transport failure") } catch (_: IOException) { }
    }
}
