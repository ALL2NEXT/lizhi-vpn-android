package xyz.a202132.windows.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppStateTest {
    private val sourceOne = node("one", "source-1", favorite = true)
    private val sourceTwo = node("two", "source-2")
    private val importedFavorite = node("local", null, favorite = true)

    @Test
    fun `subscription scope only exposes selected source`() {
        val state = DesktopAppState(
            nodes = listOf(sourceOne, sourceTwo, importedFavorite),
            nodeCategory = NodeCategory.SUBSCRIPTION,
            selectedSubscriptionSourceId = "source-2"
        )

        assertEquals(listOf("two"), state.visibleNodes.map(DesktopNode::id))
    }

    @Test
    fun `missing source selection falls back to first subscription group`() {
        val state = DesktopAppState(
            nodes = listOf(sourceOne, sourceTwo, importedFavorite),
            nodeCategory = NodeCategory.SUBSCRIPTION
        )

        assertEquals(listOf("one"), state.visibleNodes.map(DesktopNode::id))
    }

    @Test
    fun `favorites scope only exposes favorites`() {
        val state = DesktopAppState(
            nodes = listOf(sourceOne, sourceTwo, importedFavorite),
            nodeCategory = NodeCategory.FAVORITES
        )

        assertEquals(listOf("one", "local"), state.visibleNodes.map(DesktopNode::id))
    }

    @Test
    fun `node flag is inferred from country code or node name`() {
        assertEquals("\uD83C\uDDEF\uD83C\uDDF5", node("JP Tokyo", "source-1").flagEmoji())
        assertEquals("\uD83C\uDDE8\uD83C\uDDF3", node("Singapore CN", "source-1").copy(countryCode = "CN").flagEmoji())
    }

    private fun node(id: String, sourceId: String?, favorite: Boolean = false) = DesktopNode(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        isFavorite = favorite,
        subscriptionSourceId = sourceId,
        subscriptionSourceName = sourceId
    )
}
