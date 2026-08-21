package xyz.a202132.windows.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import xyz.a202132.windows.model.NodeType

class NodeLinkParserTest {
    private val parser = NodeLinkParser()

    @Test
    fun `parses current supported URI protocols`() {
        val nodes = parser.parseSubscription(
            """
            vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#🇯🇵 Tokyo
            trojan://secret@example.net:8443?sni=example.net#US Node
            hy2://password@hy.example.org:443?sni=hy.example.org#HK Node
            """.trimIndent()
        )

        assertEquals(listOf(NodeType.VLESS, NodeType.TROJAN, NodeType.HYSTERIA2), nodes.map { it.type })
        assertEquals("Tokyo", nodes.first().displayName())
        assertEquals("🇯🇵", nodes.first().flagEmoji())
    }

    @Test
    fun `parses base64 subscription and vmess metadata`() {
        val vmessJson = """{"ps":"Tokyo 02","add":"vmess.example.com","port":"443","id":"id"}"""
        val link = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val subscription = Base64.getEncoder().encodeToString(link.toByteArray())

        val node = parser.parseSubscription(subscription).single()

        assertEquals(NodeType.VMESS, node.type)
        assertEquals("vmess.example.com", node.server)
        assertEquals("Tokyo 02", node.name)
        assertNotNull(node.id)
    }
}
