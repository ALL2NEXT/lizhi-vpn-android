package io.github.HanJason233.chimera.service

import io.github.HanJason233.chimera.data.model.Node

/**
 * 在刷新后的同一订阅中寻找断线前的节点。
 * 端口有可能被订阅动态调整，因此不参与匹配；服务地址优先于名称。
 */
internal fun findRecoveryNode(disconnectedNode: Node, refreshedNodes: List<Node>): Node? {
    val disconnectedServer = normalizeRecoveryServer(disconnectedNode.server)
    return refreshedNodes.firstOrNull {
        normalizeRecoveryServer(it.server) == disconnectedServer
    } ?: refreshedNodes.firstOrNull {
        it.name.trim().equals(disconnectedNode.name.trim(), ignoreCase = true)
    }
}

private fun normalizeRecoveryServer(server: String): String = server
    .trim()
    .removePrefix("[")
    .removeSuffix("]")
    .trimEnd('.')
    .lowercase()
