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

/** 仅用于将旧版多主题配置迁移为一份全局外观设置。 */
internal data class LegacySkinScheme(
    val id: String,
    val appDisplayName: String = SkinSettings.DEFAULT_APP_DISPLAY_NAME,
    val appIconPresetId: String = SkinSettings.DEFAULT_APP_ICON_PRESET_ID,
    val baseColorArgb: Long = SkinSettings.DEFAULT_BASE_COLOR_ARGB,
    /** 主题模式，默认明亮。 */
    val themeMode: SkinThemeMode = SkinThemeMode.LIGHT,
    /** 主题方案的底部导航栏样式。 */
    val navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
    /** Android 12 及以上使用系统壁纸生成的动态配色，低版本回退到基础颜色。 */
    val monetEnabled: Boolean = false,
    /** 旧版自定义启动器图标路径，保留用于读取旧配置。 */
    val customImagePath: String? = null,
    /** 主页连接按钮在未连接、连接中、断开中、已连接状态下的自定义图片。 */
    val homeDisconnectedImagePath: String? = null,
    val homeConnectingImagePath: String? = null,
    val homeDisconnectingImagePath: String? = null,
    val homeConnectedImagePath: String? = null,
    /** 主页连接图标周围显示的动画类型。 */
    val homeIconEffect: HomeIconEffect = HomeIconEffect.RADIAL_PARTICLE,
    /** 自定义主题默认保留主页特效，内置默认主题在 default() 中关闭。 */
    val homeIconEffectEnabled: Boolean = true,
    /** 四种连接状态的图标动画独立配置。 */
    val homeDisconnectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    val homeConnectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    val homeDisconnectingIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    val homeConnectedIconEffect: HomeIconMotionEffect = HomeIconMotionEffect.NONE,
    /** 四种连接状态的粒子特效独立开关；旧版统一开关仅用于迁移和降级兼容。 */
    val homeDisconnectedParticleEffectEnabled: Boolean = true,
    val homeConnectingParticleEffectEnabled: Boolean = true,
    val homeDisconnectingParticleEffectEnabled: Boolean = true,
    val homeConnectedParticleEffectEnabled: Boolean = true,
    /** 此主题下节点卡片显示的协议、安全层、传输方式和核心标签。 */
    val showNodeProtocol: Boolean = false,
    val showNodeSecurity: Boolean = false,
    val showNodeTransport: Boolean = false,
    val showNodeCore: Boolean = false,
    val showNodeAddress: Boolean = true,
    val showNodePort: Boolean = true
) {
    val isDefault: Boolean
        get() = id == DEFAULT_SCHEME_ID

    companion object {
        const val DEFAULT_SCHEME_ID = "default"

        fun default(): LegacySkinScheme = LegacySkinScheme(
            id = DEFAULT_SCHEME_ID,
            themeMode = SkinThemeMode.LIGHT,
            monetEnabled = true,
            homeIconEffectEnabled = false,
            homeConnectingIconEffect = HomeIconMotionEffect.ROTATE,
            homeDisconnectingIconEffect = HomeIconMotionEffect.BREATHE,
            homeDisconnectedParticleEffectEnabled = false,
            homeConnectingParticleEffectEnabled = false,
            homeDisconnectingParticleEffectEnabled = false,
            homeConnectedParticleEffectEnabled = false
        )

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
    val showNodeAddress: Boolean = true,
    val showNodePort: Boolean = true
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
