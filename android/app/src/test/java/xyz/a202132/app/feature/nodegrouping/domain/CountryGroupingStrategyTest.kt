package xyz.a202132.app.feature.nodegrouping.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.feature.nodegrouping.model.CountryOption

class CountryGroupingStrategyTest {
    @Test
    fun `地区选项会跨订阅组合并统计节点数且忽略未知地区`() {
        val nodes = listOf(
            node("jp-a", "日本 01", sourceId = "a"),
            node("us", "美国 01", sourceId = "a"),
            node("jp-b", "🇯🇵 日本 02", sourceId = "b"),
            node("unknown", "高速节点", sourceId = "b")
        )

        val optionsByCode = CountryGroupingStrategy.groups(nodes).associateBy(CountryOption::code)

        assertEquals(setOf("JP", "US"), optionsByCode.keys)
        assertEquals(2, optionsByCode.getValue("JP").nodeCount)
        assertEquals(1, optionsByCode.getValue("US").nodeCount)
    }

    private fun node(
        id: String,
        name: String,
        sourceId: String = "source"
    ) = Node(
        id = id,
        name = name,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        subscriptionSourceId = sourceId
    )
}
