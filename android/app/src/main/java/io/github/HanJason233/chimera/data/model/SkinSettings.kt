package io.github.HanJason233.chimera.data.model

import java.util.UUID

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
    BLINK("blink", "闪烁");

    companion object {
        fun fromStorage(value: String?): HomeIconMotionEffect = entries.firstOrNull {
            it.storageName == value
        } ?: NONE
    }
}

/** 一个可切换、可编辑的主题方案。 */
data class SkinScheme(
    val id: String,
    val name: String,
    /** 与启动器名称一致的内置身份名称，显示名由图标预设 ID 决定。 */
    val appDisplayName: String = DEFAULT_APP_DISPLAY_NAME,
    /** 内置启动器名称与图标组合的稳定 ID。 */
    val appIconPresetId: String = DEFAULT_APP_ICON_PRESET_ID,
    val baseColorArgb: Long = SkinSettings.DEFAULT_BASE_COLOR_ARGB,
    /** 主题模式，默认跟随系统。 */
    val themeMode: SkinThemeMode = SkinThemeMode.SYSTEM,
    /** 主题方案的底部导航栏样式。 */
    val navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
    /** 主题方案的弹窗 Material 实现。 */
    val dialogMaterialVersion: DialogMaterialVersion = DialogMaterialVersion.MATERIAL_3,
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
    val homeConnectedParticleEffectEnabled: Boolean = true
) {
    val isDefault: Boolean
        get() = id == DEFAULT_SCHEME_ID

    companion object {
        const val DEFAULT_SCHEME_ID = "default"
        const val DEFAULT_APP_DISPLAY_NAME = "Chimera"
        const val DEFAULT_APP_ICON_PRESET_ID = "android"

        fun default(): SkinScheme = SkinScheme(
            id = DEFAULT_SCHEME_ID,
            name = "默认主题",
            themeMode = SkinThemeMode.SYSTEM,
            monetEnabled = true,
            homeIconEffectEnabled = false,
            homeDisconnectedParticleEffectEnabled = false,
            homeConnectingParticleEffectEnabled = false,
            homeDisconnectingParticleEffectEnabled = false,
            homeConnectedParticleEffectEnabled = false
        )

        fun create(name: String): SkinScheme = SkinScheme(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "我的主题" }
        )
    }
}

/** 用户可持久化的应用主题配置。 */
data class SkinSettings(
    val baseColorArgb: Long = DEFAULT_BASE_COLOR_ARGB,
    val themeMode: SkinThemeMode = SkinThemeMode.SYSTEM,
    val monetEnabled: Boolean = true,
    val customImagePath: String? = null,
    val homeDisconnectedImagePath: String? = null,
    val homeConnectingImagePath: String? = null,
    val homeDisconnectingImagePath: String? = null,
    val homeConnectedImagePath: String? = null,
    val activeSchemeId: String = SkinScheme.DEFAULT_SCHEME_ID,
    val schemes: List<SkinScheme> = listOf(SkinScheme.default())
) {
    val activeScheme: SkinScheme
        get() = schemes.firstOrNull { it.id == activeSchemeId }
            ?: schemes.firstOrNull { it.isDefault }
            ?: SkinScheme.default()

    companion object {
        const val DEFAULT_BASE_COLOR_ARGB: Long = 0xFF37474FL
    }
}
