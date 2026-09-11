package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeGroupKeyTest {
    @Test
    fun keysRoundTripThroughStableIds() {
        val keys = listOf(
            NodeGroupKey.Subscription("source-1"),
            NodeGroupKey.Country("JP"),
            NodeGroupKey.Favorites,
            NodeGroupKey.Imported("group-1")
        )

        keys.forEach { key ->
            assertEquals(key, NodeGroupKey.parse(key.value))
        }
    }

    @Test
    fun invalidKeysAreIgnored() {
        assertNull(NodeGroupKey.parse(null))
        assertNull(NodeGroupKey.parse(""))
        assertNull(NodeGroupKey.parse("unknown:group"))
    }
}
