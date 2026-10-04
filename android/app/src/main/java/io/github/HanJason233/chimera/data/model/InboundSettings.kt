package io.github.HanJason233.chimera.data.model

/** 局域网入站配置；核心仅在 VPN 运行期间加载该监听器。 */
data class LanInboundSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USERNAME,
    val password: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 10808
        const val DEFAULT_USERNAME = "chimera"
    }
}
