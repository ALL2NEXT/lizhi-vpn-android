package xyz.a202132.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class NodeRegionFilterTest {
    @Test
    fun `地区汇总兼容显式代码 地区名称和旗帜`() {
        val nodes = listOf(
            node("jp", "日本节点", country = "JP"),
            node("us", "美国高速节点"),
            node("hk", "🇭🇰 香港 01"),
            node("unknown", "高速节点")
        )

        assertEquals(setOf("JP", "US", "HK"), availableNodeRegionCodes(nodes).toSet())
    }

    @Test
    fun `空白名单不进行过滤`() {
        val nodes = listOf(node("jp", "日本"), node("us", "美国"))

        assertSame(nodes, filterNodesByRegionWhitelist(nodes, emptySet()))
    }

    @Test
    fun `非空白名单只保留已勾选地区`() {
        val nodes = listOf(
            node("jp", "日本"),
            node("us", "美国"),
            node("unknown", "高速节点")
        )

        assertEquals(
            listOf("jp"),
            filterNodesByRegionWhitelist(nodes, setOf("jp")).map(Node::id)
        )
    }

    @Test
    fun `地区代码可以生成并还原旗帜`() {
        assertEquals("🇪🇺", CountryCodeResolver.flagEmoji("eu"))
        assertEquals("EU", CountryCodeResolver.fromFlagEmoji("欧洲 🇪🇺 节点"))
    }

    private fun node(id: String, name: String, country: String? = null) = Node(
        id = id,
        name = name,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        country = country
    )
}
