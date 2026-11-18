package io.github.HanJason233.chimera.data.model

/** 主页连接图标可用的动画特效，后续可按需扩展。 */
enum class HomeIconEffect(val storageName: String, val displayName: String) {
    RADIAL_PARTICLE("radial_particle", "环绕粒子");

    companion object {
        fun fromStorage(value: String?): HomeIconEffect = entries.firstOrNull {
            it.storageName == value
        } ?: RADIAL_PARTICLE
    }
}

/** 各连接状态图标可单独选择的动态效果。 */
enum class HomeIconMotionEffect(val storageName: String, val displayName: String) {
    NONE("none", "无"),
    ROTATE("rotate", "旋转"),
    BREATHE("breathe", "呼吸");

    companion object {
        fun fromStorage(value: String?): HomeIconMotionEffect = when (value) {
            "blink" -> BREATHE
            else -> entries.firstOrNull { it.storageName == value } ?: NONE
        }
    }
}

/** 应用唯一的全局外观配置，修改后直接作用于整个应用。 */
data class SkinSettings(
    /** 启动器图标预设决定应用显示名称和图标。 */
    val appDisplayName: String = DEFAULT_APP_DISPLAY_NAME,
    val appIconPresetId: String = DEFAULT_APP_ICON_PRESET_ID,
    val baseColorArgb: Long = DEFAULT_BASE_COLOR_ARGB,
    val themeMode: SkinThemeMode = SkinThemeMode.LIGHT,
    val navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
    val monetEnabled: Boolean = true,
    /** 旧版自定义启动器图标路径，仅保留用于兼容读取。 */
    val customImagePath: String? = null,
    val homeDisconnectedImagePath: String? = null,
    val homeConnectingImagePath: String? = null,
    val homeDisconnectingImagePath: String? = null,
    val homeConnectedImagePath: String? = null,
    val homeIconEffect: HomeIconEffect = HomeIconEffect.RADIAL_PARTICLE,
    val homeIconEffectEnabled: Boolean = false,
    val homeDisconnectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    val homeConnectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.ROTATE,
    val homeDisconnectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.BREATHE,
    val homeConnectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    val homeDisconnectedParticleEffectEnabled: Boolean = false,
    val homeConnectingParticleEffectEnabled: Boolean = false,
    val homeDisconnectingParticleEffectEnabled: Boolean = false,
    val homeConnectedParticleEffectEnabled: Boolean = false,
    val showNodeProtocol: Boolean = false,
    val showNodeSecurity: Boolean = false,
    val showNodeTransport: Boolean = false,
    val showNodeCore: Boolean = false,
    val showNodeAddress: Boolean = false,
    val showNodePort: Boolean = false,
    /** 首页圆形测试面板展示的项目，与测速运行开关独立保存。 */
    val homeTestDisplayStages: List<SpeedTestStage> = listOf(
        SpeedTestStage.TCPING,
        SpeedTestStage.URL_TEST,
        SpeedTestStage.BANDWIDTH
    )
) {
    val nodeDisplaySettings: NodeDisplaySettings
        get() = NodeDisplaySettings(
            showAddress = showNodeAddress,
            showPort = showNodePort,
            showProtocol = showNodeProtocol,
            showSecurity = showNodeSecurity,
            showTransport = showNodeTransport,
            showCore = showNodeCore
        )

    companion object {
        const val DEFAULT_APP_DISPLAY_NAME = "荔枝VPN"
        const val DEFAULT_APP_ICON_PRESET_ID = "android"
        const val DEFAULT_BASE_COLOR_ARGB: Long = 0xFF37474FL
    }
}
