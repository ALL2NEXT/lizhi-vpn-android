package xyz.a202132.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class RecoveryNodeMatcherTest {

    @Test
    fun serverMatchHasPriorityAndIgnoresPort() {
        val disconnected = node("old", "原节点", "1.2.3.4", 443)
        val sameName = node("name", "原节点", "9.9.9.9", 443)
        val sameServer = node("server", "新名称", "1.2.3.4", 8443)

        assertEquals("server", findRecoveryNode(disconnected, listOf(sameName, sameServer))?.id)
    }

    @Test
    fun nameIsUsedWhenServerChanged() {
        val disconnected = node("old", "东京 A", "1.2.3.4", 443)
        val refreshed = node("new", "东京 A", "5.6.7.8", 8443)

        assertEquals("new", findRecoveryNode(disconnected, listOf(refreshed))?.id)
    }

    private fun node(id: String, name: String, server: String, port: Int) = Node(
        id = id,
        name = name,
        type = NodeType.VLESS,
        server = server,
        port = port,
        rawLink = "vless://$id@$server:$port#$name"
    )
}
