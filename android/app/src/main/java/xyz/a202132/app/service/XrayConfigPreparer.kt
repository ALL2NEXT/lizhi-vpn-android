package xyz.a202132.app.service

import android.util.Log
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.util.XrayConfigGenerator

internal data class PreparedXrayConfig(
    val content: String,
    val nodes: List<Node>,
    val bridgePorts: Map<String, Int>
)

/** 校验完整 Xray 配置，并仅在恢复流程中隔离异常节点。 */
internal class XrayConfigPreparer(
    private val processManager: XrayProcessManager,
    private val generator: XrayConfigGenerator = XrayConfigGenerator()
) {
    suspend fun prepare(
        nodes: List<Node>,
        bridgePorts: Map<String, Int>,
        selectedNodeId: String?,
        hysteriaUpMbps: Int = 0,
        hysteriaDownMbps: Int = 0
    ): PreparedXrayConfig {
        require(nodes.isNotEmpty()) { "没有需要 Xray 处理的节点" }

        val fullGeneration = runCatching {
            generator.generateConfig(nodes, bridgePorts, hysteriaUpMbps, hysteriaDownMbps)
        }
        val fullContent = fullGeneration.getOrNull()
        val fullCheck = if (fullContent != null) {
            processManager.checkConfig(fullContent)
        } else {
            Result.failure(fullGeneration.exceptionOrNull() ?: IllegalArgumentException("Xray 配置生成失败"))
        }
        if (fullCheck.isSuccess && fullContent != null) {
            return PreparedXrayConfig(fullContent, nodes, bridgePorts)
        }

        Log.w(TAG, "Complete Xray config validation failed; isolating incompatible nodes", fullCheck.exceptionOrNull())
        val compatible = nodes.filter { node ->
            val port = bridgePorts.getValue(node.id)
            val generated = runCatching {
                generator.generateTestConfig(node, port, hysteriaUpMbps, hysteriaDownMbps)
            }
            val check = generated.getOrNull()?.let { processManager.checkConfig(it) }
                ?: Result.failure(generated.exceptionOrNull() ?: IllegalArgumentException("Xray 节点配置生成失败"))
            check
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "Xray-incompatible node skipped: id=${node.id}, type=${node.type}, server=${node.server}",
                        error
                    )
                }
                .isSuccess
        }
        require(compatible.isNotEmpty()) { "当前没有可由 Xray 启动的节点" }
        require(selectedNodeId == null || nodes.none { it.id == selectedNodeId } || compatible.any { it.id == selectedNodeId }) {
            "所选节点配置不被 Xray 核心支持"
        }

        val compatiblePorts = bridgePorts.filterKeys { id -> compatible.any { it.id == id } }
        val content = generator.generateConfig(
            compatible,
            compatiblePorts,
            hysteriaUpMbps,
            hysteriaDownMbps
        )
        processManager.checkConfig(content).getOrThrow()
        return PreparedXrayConfig(content, compatible, compatiblePorts)
    }

    private companion object {
        const val TAG = "XrayConfigPreparer"
    }
}
