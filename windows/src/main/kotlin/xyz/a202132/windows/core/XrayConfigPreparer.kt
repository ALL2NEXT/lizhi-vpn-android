package xyz.a202132.windows.core

import java.nio.file.Path
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings

internal data class PreparedXrayConfig(
    val content: String,
    val nodes: List<DesktopNode>,
    val bridgePorts: Map<String, Int>
)

/** 校验完整 Xray 配置，并仅在恢复流程中隔离异常节点。 */
internal class XrayConfigPreparer(
    private val processManager: XrayProcessManager,
    private val generator: WindowsXrayConfigGenerator = WindowsXrayConfigGenerator()
) {
    fun prepare(
        executable: Path,
        nodes: List<DesktopNode>,
        bridgePorts: Map<String, Int>,
        selectedNodeId: String?,
        settings: DesktopSettings = DesktopSettings()
    ): PreparedXrayConfig {
        require(nodes.isNotEmpty()) { "没有需要 Xray 处理的节点" }

        val fullGeneration = runCatching { generator.generate(nodes, bridgePorts, settings) }
        val fullContent = fullGeneration.getOrNull()
        val fullCheck = fullContent?.let { processManager.checkConfig(executable, it) }
            ?: Result.failure(fullGeneration.exceptionOrNull() ?: error("Xray 配置生成失败"))
        if (fullCheck.isSuccess && fullContent != null) {
            return PreparedXrayConfig(fullContent, nodes, bridgePorts)
        }

        System.err.println("完整 Xray 配置校验失败，开始隔离不兼容节点：${fullCheck.exceptionOrNull()?.message}")
        val compatible = nodes.filter { node ->
            val generated = runCatching {
                generator.generateTestConfig(node, bridgePorts.getValue(node.id), settings)
            }
            val check = generated.getOrNull()?.let { processManager.checkConfig(executable, it) }
                ?: Result.failure(generated.exceptionOrNull() ?: error("Xray 节点配置生成失败"))
            if (check.isFailure) {
                System.err.println(
                    "跳过 Xray 不兼容节点：id=${node.id}, type=${node.type}, " +
                        "server=${node.server}, reason=${check.exceptionOrNull()?.message}"
                )
            }
            check.isSuccess
        }
        require(compatible.isNotEmpty()) { "当前没有可由 Xray 启动的节点" }
        require(
            selectedNodeId == null ||
                nodes.none { it.id == selectedNodeId } ||
                compatible.any { it.id == selectedNodeId }
        ) { "所选节点配置不被 Xray 核心支持" }

        val compatibleIds = compatible.mapTo(hashSetOf(), DesktopNode::id)
        val compatiblePorts = bridgePorts.filterKeys(compatibleIds::contains)
        val content = generator.generate(compatible, compatiblePorts, settings)
        processManager.checkConfig(executable, content).getOrThrow()
        return PreparedXrayConfig(content, compatible, compatiblePorts)
    }
}
