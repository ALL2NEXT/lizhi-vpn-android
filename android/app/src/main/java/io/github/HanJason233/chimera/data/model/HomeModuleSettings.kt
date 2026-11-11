package io.github.HanJason233.chimera.data.model

/** 首页模块的独立显示开关。 */
data class HomeModuleSettings(
    val showIpAddressTest: Boolean = true,
    val showSelectedGroup: Boolean = true,
    val showSelectedNode: Boolean = true
) {
    companion object {
        val DEFAULT = HomeModuleSettings()
    }
}
