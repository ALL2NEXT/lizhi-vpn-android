package xyz.a202132.app.service

import android.util.Log
import io.nekohasekai.libbox.Libbox
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.util.SingBoxConfigGenerator

/**
 * Builds a VPN configuration while keeping expensive per-node core validation
 * out of the normal connection path.
 */
internal class VpnConfigPreparer(
    private val configGenerator: SingBoxConfigGenerator = SingBoxConfigGenerator(),
    private val validateConfig: (String) -> Unit = Libbox::checkConfig
) {
    fun prepare(
        nodes: List<Node>,
        selectedNodeId: String?,
        proxyMode: ProxyMode,
        ipv6Mode: IPv6RoutingMode,
        internalSocksPort: Int,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int,
        tunStack: TunStack,
        xrayBridgePorts: Map<String, Int> = emptyMap()
    ): String {
        val candidates = nodes.filter { node ->
            node.type != NodeType.UNKNOWN &&
                node.server.isNotBlank() &&
                node.port in 1..65_535
        }
        requireUsableSelection(candidates, selectedNodeId)

        fun generate(source: List<Node>): String = configGenerator.generateConfig(
            source,
            selectedNodeId,
            proxyMode,
            ipv6Mode,
            AppConfig.VPN_MTU,
            internalSocksPort,
            hysteriaUpMbps,
            hysteriaDownMbps,
            tunStack,
            xrayBridgePorts.filterKeys { id -> source.any { it.id == id } }
        )

        val fastConfig = runCatching { generate(candidates) }
            .mapCatching { config ->
                validateConfig(config)
                config
            }
        fastConfig.getOrNull()?.let { return it }

        Log.w(
            TAG,
            "Complete config validation failed; isolating incompatible nodes",
            fastConfig.exceptionOrNull()
        )
        val compatibleNodes = candidates.filter { node ->
            node.id in xrayBridgePorts || isCoreCompatible(node)
        }
        val skippedCount = candidates.size - compatibleNodes.size
        if (skippedCount > 0) {
            Log.w(TAG, "Skipped $skippedCount core-incompatible nodes before VPN start")
        }
        requireUsableSelection(compatibleNodes, selectedNodeId)

        return generate(compatibleNodes).also(validateConfig)
    }

    private fun requireUsableSelection(nodes: List<Node>, selectedNodeId: String?) {
        require(nodes.isNotEmpty()) { "当前节点列表没有核心支持的节点" }
        require(selectedNodeId == null || nodes.any { it.id == selectedNodeId }) {
            "所选节点配置不被核心支持"
        }
    }

    private fun isCoreCompatible(node: Node): Boolean = runCatching {
        validateConfig(configGenerator.generateTestConfig(node))
    }.onFailure { error ->
        Log.w(
            TAG,
            "Core-incompatible node skipped: id=${node.id}, type=${node.type}, server=${node.server}",
            error
        )
    }.isSuccess

    private companion object {
        const val TAG = "VpnConfigPreparer"
    }
}
