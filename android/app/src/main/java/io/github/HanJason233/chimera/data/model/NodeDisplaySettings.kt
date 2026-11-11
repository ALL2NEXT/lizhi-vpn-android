package io.github.HanJason233.chimera.data.model

/** 节点卡片地址、端口和标签的显示设置。 */
data class NodeDisplaySettings(
    val showAddress: Boolean = true,
    val showPort: Boolean = true,
    val showProtocol: Boolean = false,
    val showSecurity: Boolean = false,
    val showTransport: Boolean = false,
    val showCore: Boolean = false
)
