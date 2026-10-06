package io.github.HanJason233.chimera.network

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class UniversalSubscriptionImportTest {
    private val localUri = "vless://11111111-1111-1111-1111-111111111111@local.example:443"
    private val yaml = """
        proxies:
          - name: yaml-node
            type: trojan
            server: yaml.example
            port: 443
            password: secret
    """.trimIndent()
    private val json = """{"type":"vless","server":"json.example","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}"""

    private fun encode(content: String): String =
        Base64.getEncoder().encodeToString(content.toByteArray())

    @Test fun decodesNestedBase64BeforeExtractingRemoteSubscriptions() {
        val content = "https://one.example/sub\nhttps://two.example/sub\n" + localUri
        val input = SubscriptionInputDetector.detect(encode(encode(encode(content))))
            as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://one.example/sub", "https://two.example/sub"), input.urls)
        assertEquals(listOf(localUri), SubscriptionConverter.toStandardUris(input.content))
    }

    @Test fun combinesPlainTextAndIndependentlyEncodedSources() {
        val input = SubscriptionInputDetector.detect(
            localUri + "\n" + encode(encode("https://one.example/sub\n" + yaml)) +
                "\n" + json + "\n" + encode("https://two.example/sub")
        ) as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://one.example/sub", "https://two.example/sub"), input.urls)
        val uris = SubscriptionConverter.toStandardUris(input.content)
        assertEquals(3, uris.size)
        assertTrue(uris.any { it.contains("yaml.example") })
        assertTrue(uris.any { it.contains("json.example") })
    }

    @Test fun acceptsWrappedAndUrlSafeBase64() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString((yaml + "\n" + localUri).toByteArray()).chunked(37).joinToString("\n")
        assertEquals(2, SubscriptionConverter.toStandardUris(encoded).size)
    }

    @Test fun extractsMultipleOutsideUrlsFromOneLineAndDeduplicatesThem() {
        assertEquals(
            SubscriptionImportInput.MultipleRemoteSubscriptions(
                listOf("https://one.example/sub", "http://two.example/sub")
            ),
            SubscriptionInputDetector.detect(
                "https://one.example/sub http://two.example/sub\n" + encode("https://one.example/sub")
            )
        )
    }

    @Test fun protectsJsonAndYamlFieldUrlsAndEncodedCredentials() {
        val configuration = """
            {
              "outbounds":[{"type":"trojan","server":"json.example","server_port":443,
                "password":"dGhpcy1pcy1hLXBhc3N3b3Jk","tls":{"enabled":true},
                "transport":{"type":"ws","path":"https://transport.example/path"}}],
              "route":{"rule_set":[{"url":"https://rules.example/data"}]}
            }
        """.trimIndent()
        val yamlWithUrl = yaml + "\nexternal-ui-url: https://ui.example/\n"
        val input = SubscriptionInputDetector.detect(configuration + "\n" + yamlWithUrl)
            as SubscriptionImportInput.FullContent
        val uris = SubscriptionConverter.toStandardUris(input.content)
        assertEquals(2, uris.size)
        assertTrue(uris.first().contains("dGhpcy1pcy1hLXBhc3N3b3Jk"))
        assertFalse(uris.any { it.startsWith("https://") })
    }

    @Test fun preservesSeveralYamlDocumentsInOneLocalSource() {
        val input = SubscriptionInputDetector.detect(
            yaml + "\n---\n" + yaml.replace("yaml.example", "second.example") +
                "\n" + json + "\n" + localUri + "\nhttps://remote.example/sub"
        ) as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://remote.example/sub"), input.urls)
        assertEquals(4, SubscriptionConverter.toStandardUris(input.content).size)
    }

    @Test fun supportsAdjacentJsonDocumentsAndTrailingSubscriptionUrl() {
        val input = SubscriptionInputDetector.detect(
            json + json.replace("json.example", "second.example") + " https://remote.example/sub"
        ) as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://remote.example/sub"), input.urls)
        assertEquals(2, SubscriptionConverter.toStandardUris(input.content).size)
    }

    @Test fun keepsVmessPayloadEncodedInsideItsUri() {
        val vmess = "vmess://" + encode("""{"add":"vmess.example","port":443,"id":"id","ps":"node"}""")
        assertEquals(listOf(vmess), SubscriptionConverter.toStandardUris(encode(encode(vmess))))
    }

    @Test fun discardsUnrelatedAndInvalidConfigurationWithoutCreatingNodes() {
        assertTrue(SubscriptionConverter.toStandardUris(
            """{"log":{"level":"debug"},"route":{"url":"https://rules.example"}}"""
        ).isEmpty())
        assertTrue(SubscriptionConverter.toStandardUris("""{"outbounds":[{"type":"direct"},{"type":"selector"}]}""").isEmpty())
        assertTrue(SubscriptionConverter.toStandardUris("////not-base64").isEmpty())
        assertTrue(SubscriptionConverter.toStandardUris("external-ui-url: https://ui.example").isEmpty())
    }

    @Test fun remoteResponseUsesTheSameRecursiveConfigurationConversion() {
        // 远程响应和本地文本都经由 SubscriptionConverter，格式不会绑定到输入渠道。
        assertEquals(
            SubscriptionConverter.toStandardUris(yaml + "\n" + json + "\n" + localUri),
            SubscriptionConverter.toStandardUris(encode(encode(yaml + "\n" + json + "\n" + localUri)))
        )
    }

    @Test fun malformedContentDoesNotSwallowOtherSources() {
        val input = SubscriptionInputDetector.detect(
            "{\"outbounds\": [\nhttps://remote.example/sub\n" + yaml + "\nunrelated-text\n" + localUri
        ) as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://remote.example/sub"), input.urls)
        assertEquals(2, SubscriptionConverter.toStandardUris(input.content).size)
    }

    @Test fun commaSeparatedRemoteUrlsAreIndividualSources() {
        val input = SubscriptionInputDetector.detect("https://one.example/sub,https://two.example/sub")
            as SubscriptionImportInput.MultipleRemoteSubscriptions
        assertEquals(listOf("https://one.example/sub", "https://two.example/sub"), input.urls)
    }

    @Test fun explanationTextDoesNotBecomeAnExtraLocalSource() {
        assertEquals(
            SubscriptionImportInput.RemoteSubscription("https://remote.example/sub"),
            SubscriptionInputDetector.detect("Here is the subscription:\nhttps://remote.example/sub")
        )
    }

    @Test fun yamlProxyObjectAndListAreConvertedAndUnrelatedFieldsDropped() {
        val node = """
            type: vless
            server: single.example
            port: 443
            uuid: 11111111-1111-1111-1111-111111111111
            unused-field: ignored
        """.trimIndent()
        assertEquals(1, SubscriptionConverter.toStandardUris(node).size)
        val list = """
            - name: first
              type: trojan
              server: first.example
              port: 443
              password: secret
            - {name: second, type: ss, server: second.example, port: 8388, cipher: aes-128-gcm, password: secret}
        """.trimIndent()
        val uris = SubscriptionConverter.toStandardUris(list)
        assertEquals(2, uris.size)
        assertTrue(uris.first().startsWith("trojan://"))
        assertTrue(uris.last().startsWith("ss://"))
    }

    @Test fun encodedAndPlainSourcesCanShareOneLine() {
        val input = SubscriptionInputDetector.detect(
            localUri + " " + encode(encode(json)) + " https://remote.example/sub"
        ) as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://remote.example/sub"), input.urls)
        assertEquals(2, SubscriptionConverter.toStandardUris(input.content).size)
    }

    @Test fun urlInsideNodeUriRemainsAConnectionParameter() {
        val node = localUri + "?ech=https://dns.example/query,https://fallback.example/query"
        val input = SubscriptionInputDetector.detect(node + "\nhttps://remote.example/sub")
            as SubscriptionImportInput.MixedSubscription
        assertEquals(listOf("https://remote.example/sub"), input.urls)
        assertEquals(listOf(node), SubscriptionConverter.toStandardUris(input.content))
    }
}
