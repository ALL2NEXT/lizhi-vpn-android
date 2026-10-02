package io.github.HanJason233.chimera.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashYamlParserTest {
    @Test
    fun parsesCommonClashMetaProxyTypes() {
        val result = ClashYamlParser.parseToUris(
            """
            global-client-fingerprint: chrome
            proxies:
              - name: Reality VLESS
                type: vless
                server: edge.example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                network: ws
                servername: www.example.com
                reality-opts:
                  public-key: public-key-value
                  short-id: abcd
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example.com
              - name: VMess gRPC
                type: vmess
                server: vmess.example.com
                port: 443
                uuid: 22222222-2222-2222-2222-222222222222
                alterId: 0
                cipher: auto
                tls: true
                network: grpc
                grpc-opts:
                  grpc-service-name: tunnel
              - name: Shadowsocks
                type: ss
                server: ss.example.com
                port: 8388
                cipher: aes-256-gcm
                password: secret
              - name: Hysteria 2
                type: hysteria2
                server: hy.example.com
                port: 443
                password: hy-secret
                sni: hy.example.com
                obfs: salamander
                obfs-password: obfs-secret
              - name: TUIC
                type: tuic
                server: tuic.example.com
                port: 443
                uuid: 33333333-3333-3333-3333-333333333333
                password: tuic-secret
                congestion-controller: bbr
              - name: Unsupported
                type: snell
                server: snell.example.com
                port: 443
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(5, result!!.size)
        assertTrue(result[0].startsWith("vless://"))
        assertTrue(result[0].contains("security=reality"))
        assertTrue(result[0].contains("pbk=public-key-value"))
        assertTrue(result[0].contains("fp=chrome"))
        assertTrue(result[0].contains("type=ws"))
        assertTrue(result[1].startsWith("vmess://"))
        assertTrue(result[2].startsWith("ss://"))
        assertTrue(result[3].startsWith("hysteria2://"))
        assertTrue(result[4].startsWith("tuic://"))
        assertFalse(result.any { it.contains("snell.example.com") })
    }

    @Test
    fun returnsNullForNonClashContentAndEmptyListForInvalidClashYaml() {
        assertEquals(null, ClashYamlParser.parseToUris("vless://id@example.com:443"))
        assertEquals(emptyList<String>(), ClashYamlParser.parseToUris("proxies:\n  - ["))
    }

    @Test
    fun parsesRemainingSupportedClashTypesAndEscapesNames() {
        val result = ClashYamlParser.parseToUris(
            """
            proxies:
              - { name: "AnyTLS 80%", type: anytls, server: any.example.com, port: 443, password: secret }
              - { name: Naive, type: naive, server: naive.example.com, port: 443, username: user, password: pass }
              - { name: WireGuard, type: wireguard, server: wg.example.com, port: 51820, private-key: private, public-key: public, ip: 10.0.0.2/32 }
              - { name: HTTPS, type: http, server: http.example.com, port: 443, tls: true, username: user, password: pass }
              - { name: SOCKS, type: socks5, server: socks.example.com, port: 1080 }
            """.trimIndent()
        )!!

        assertEquals(5, result.size)
        assertTrue(result[0].startsWith("anytls://"))
        assertTrue(result[0].endsWith("#AnyTLS%2080%25"))
        assertTrue(result[1].startsWith("naive+https://"))
        assertTrue(result[2].startsWith("wireguard://"))
        assertTrue(result[3].startsWith("https://"))
        assertTrue(result[4].startsWith("socks5://"))
    }
}
