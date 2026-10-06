package io.github.HanJason233.chimera.network.nodetest.linktype

import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe

import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinkTypeTestTest {
    @Test
    fun `matching any resolved entrance is direct and does not inspect CDN`() {
        val result = LinkTypeClassifier.classify(
            "node.example", listOf("104.16.1.2", "104.16.1.3"), "104.16.1.3"
        ) { error("Direct connections do not need CDN classification") }
        assertEquals(LinkTypeTestState.DIRECT, result.state)
        assertEquals("104.16.1.3", result.exitIp)
        assertTrue(result.entryCdnProviders.isEmpty())
    }

    @Test
    fun `different addresses with non CDN entrance are ordinary relay`() {
        // A CDN exit must not make an ordinary entrance a CDN relay.
        val result = LinkTypeClassifier.classify("203.0.113.8", listOf("203.0.113.8"), "104.16.1.2")
        assertEquals(LinkTypeTestState.RELAY, result.state)
        assertEquals(listOf("203.0.113.8"), result.entryIps)
        assertEquals("104.16.1.2", result.exitIp)
    }

    @Test
    fun `CDN relay records the provider for each entrance`() {
        val result = LinkTypeClassifier.classify(
            "node.example", listOf("104.16.1.2", "151.101.1.2"), "203.0.113.8"
        )
        assertEquals(LinkTypeTestState.CDN_RELAY, result.state)
        assertEquals(mapOf("104.16.1.2" to "Cloudflare", "151.101.1.2" to "Fastly"), result.entryCdnProviders)
    }

    @Test
    fun `IPv6 compression brackets and mapped IPv4 do not create false relays`() {
        val ipv6 = LinkTypeClassifier.classify(
            "[2001:db8::1]", listOf("[2001:db8::1]"), "2001:0db8:0000:0000:0000:0000:0000:0001"
        )
        assertEquals(LinkTypeTestState.DIRECT, ipv6.state)
        val mapped = LinkTypeClassifier.classify("192.0.2.1", listOf("192.0.2.1"), "::ffff:192.0.2.1")
        assertEquals(LinkTypeTestState.DIRECT, mapped.state)
    }

    @Test
    fun `missing DNS or invalid exit address is an error`() {
        assertEquals(LinkTypeTestState.ERROR, LinkTypeClassifier.classify("bad.example", emptyList(), "192.0.2.1").state)
        for (invalid in listOf("", "example.com", "127.1", "256.1.1.1", "<html>blocked</html>", "fe80::1%wlan0")) {
            assertNull(LinkIpAddress.parse(invalid))
            assertEquals(LinkTypeTestState.ERROR,
                LinkTypeClassifier.classify("192.0.2.1", listOf("192.0.2.1"), invalid).state)
        }
    }

    @Test
    fun `bundled ranges support IPv6 and respect prefix boundaries`() {
        assertEquals("Cloudflare", CdnIpCatalog.providerFor("2606:4700::1111"))
        assertEquals("Cloudflare", CdnIpCatalog.providerFor("104.16.0.0"))
        assertEquals("Cloudflare", CdnIpCatalog.providerFor("104.27.255.255"))
        assertNotEquals("Cloudflare", CdnIpCatalog.providerFor("104.15.255.255"))
        assertNotEquals("Cloudflare", CdnIpCatalog.providerFor("104.28.0.0"))
        assertEquals("Fastly", CdnIpCatalog.providerFor("2a04:4e42::1"))
        assertNull(CdnIpCatalog.providerFor("203.0.113.1"))
        // A provider's generic cloud or DNS range must not be labeled as CDN.
        assertNull(CdnIpCatalog.providerFor("8.8.8.8"))
    }

    @Test
    fun `runner tries fallback for malformed response and retains entrance`() = runBlocking {
        val targets = mutableListOf<String>()
        val result = LinkTypeTestRunner(LocalSocksEndpoint.unauthenticated(1234),
            resolveServer = { listOf("203.0.113.8") }, fetch = { url ->
                targets += url
                Socks5HttpProbe.HttpResponse(200, if (targets.size == 1) "bad.example" else "203.0.113.8\n", null, 1)
            }).run("node.example")
        assertEquals(listOf("https://api.ipify.org", "https://api64.ipify.org"), targets)
        assertEquals(LinkTypeTestState.DIRECT, result.state)
    }

    @Test
    fun `runner prefers IPv6 lookup for IPv6 entrance`() = runBlocking {
        val targets = mutableListOf<String>()
        val result = LinkTypeTestRunner(LocalSocksEndpoint.unauthenticated(1234),
            resolveServer = { listOf("2001:db8::1") }, fetch = { url ->
                targets += url
                Socks5HttpProbe.HttpResponse(200, "2001:db8::1", null, 1)
            }).run("node.example")
        assertEquals(listOf("https://api6.ipify.org"), targets)
        assertEquals(LinkTypeTestState.DIRECT, result.state)
    }

    @Test
    fun `failed lookup never returns a relay or performs direct network fallback`() = runBlocking {
        val failedDns = LinkTypeTestRunner(LocalSocksEndpoint.unauthenticated(1234),
            resolveServer = { throw java.net.UnknownHostException() },
            fetch = { error("No exit request should run without an entrance") }).run("bad.example")
        assertEquals(LinkTypeTestState.ERROR, failedDns.state)
        val failedExit = LinkTypeTestRunner(LocalSocksEndpoint.unauthenticated(1234),
            resolveServer = { listOf("203.0.113.8") },
            fetch = { throw java.net.SocketTimeoutException() }).run("node.example")
        assertEquals(LinkTypeTestState.ERROR, failedExit.state)
        assertEquals(listOf("203.0.113.8"), failedExit.entryIps)
    }

    @Test(expected = CancellationException::class)
    fun `runner preserves cancellation`() = runBlocking<Unit> {
        LinkTypeTestRunner(LocalSocksEndpoint.unauthenticated(1234),
            resolveServer = { throw CancellationException() }).run("node.example")
    }
}
