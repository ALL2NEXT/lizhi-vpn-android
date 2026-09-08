package xyz.a202132.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class CountryNodeSelectionTest {
    @Test
    fun `地区选项会跨订阅组合并统计节点数且忽略未知地区`() {
        val nodes = listOf(
            node("jp-a", "日本 01", sourceId = "a"),
            node("us", "美国 01", sourceId = "a"),
            node("jp-b", "🇯🇵 日本 02", sourceId = "b"),
            node("unknown", "高速节点", sourceId = "b")
        )

        val optionsByCode = countryOptions(nodes).associateBy(CountryOption::code)

        assertEquals(setOf("JP", "US"), optionsByCode.keys)
        assertEquals(2, optionsByCode.getValue("JP").nodeCount)
        assertEquals(1, optionsByCode.getValue("US").nodeCount)
    }

    @Test
    fun `地区节点优先选择可用且延迟最低的节点`() {
        val nodes = listOf(
            node("unavailable", "日本 01", latency = 20, isAvailable = false),
            node("slow", "日本 02", latency = 180),
            node("fast", "日本 03", latency = 60),
            node("untested", "日本 04", latency = -1)
        )

        assertEquals("fast", preferredNodeForCountry(nodes, "jp")?.id)
    }

    @Test
    fun `地区节点不存在时不返回其他地区节点`() {
        assertNull(preferredNodeForCountry(listOf(node("jp", "日本")), "US"))
    }

    private fun node(
        id: String,
        name: String,
        sourceId: String = "source",
        latency: Int = -1,
        isAvailable: Boolean = true
    ) = Node(
        id = id,
        name = name,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        latency = latency,
        isAvailable = isAvailable,
        subscriptionSourceId = sourceId
    )
}
