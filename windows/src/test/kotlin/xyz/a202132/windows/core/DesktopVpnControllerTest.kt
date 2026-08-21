package xyz.a202132.windows.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.NodeType

class DesktopVpnControllerTest {
    @Test
    fun `imported nodes are merged as favorites`() {
        val existing = node("existing", favorite = false)
        val imported = node("imported", favorite = false)

        val merged = mergeImportedNodes(listOf(existing), listOf(imported))

        assertEquals(listOf("existing", "imported"), merged.map(DesktopNode::id))
        assertTrue(merged.single { it.id == "imported" }.isFavorite)
        assertTrue(merged.single { it.id == "existing" }.isFavorite.not())
    }

    @Test
    fun `reimporting an existing node marks it favorite without duplicating`() {
        val existing = node("same", favorite = false)

        val merged = mergeImportedNodes(listOf(existing), listOf(existing))

        assertEquals(1, merged.size)
        assertTrue(merged.single().isFavorite)
    }

    private fun node(id: String, favorite: Boolean) = DesktopNode(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        isFavorite = favorite
    )
}
