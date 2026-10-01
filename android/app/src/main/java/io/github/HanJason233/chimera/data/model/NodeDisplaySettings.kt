package io.github.HanJason233.chimera.data.model

/** 节点卡片标签的分层显示设置，默认保持卡片简洁，由用户按需开启。 */
data class NodeDisplaySettings(
    val showProtocol: Boolean = false,
    val showSecurity: Boolean = false,
    val showTransport: Boolean = false,
    val showCore: Boolean = false
)
