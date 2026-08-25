package xyz.a202132.app.data.model

/** 节点卡片协议标签的分层显示设置，默认完整展示三个层级。 */
data class NodeDisplaySettings(
    val showProtocol: Boolean = true,
    val showSecurity: Boolean = true,
    val showTransport: Boolean = true
)
