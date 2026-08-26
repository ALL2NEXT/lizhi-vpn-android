package xyz.a202132.app.data.model

import java.util.UUID

/** 一个可切换、可编辑的皮肤方案。 */
data class SkinScheme(
    val id: String,
    val name: String,
    val baseColorArgb: Long = SkinSettings.DEFAULT_BASE_COLOR_ARGB,
    /** Android 12 及以上使用系统壁纸生成的动态配色，低版本回退到基础颜色。 */
    val monetEnabled: Boolean = false,
    /** 左侧设置栏顶部背景图。 */
    val customImagePath: String? = null,
    /** 主页连接按钮在未连接、连接中、已连接状态下的自定义图片。 */
    val homeDisconnectedImagePath: String? = null,
    val homeConnectingImagePath: String? = null,
    val homeConnectedImagePath: String? = null
) {
    val isDefault: Boolean
        get() = id == DEFAULT_SCHEME_ID

    companion object {
        const val DEFAULT_SCHEME_ID = "default"

        fun default(): SkinScheme = SkinScheme(
            id = DEFAULT_SCHEME_ID,
            name = "默认方案",
            monetEnabled = true
        )

        fun create(name: String): SkinScheme = SkinScheme(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "我的方案" }
        )
    }
}

/** 用户可持久化的应用皮肤配置。 */
data class SkinSettings(
    val baseColorArgb: Long = DEFAULT_BASE_COLOR_ARGB,
    val monetEnabled: Boolean = true,
    val customImagePath: String? = null,
    val homeDisconnectedImagePath: String? = null,
    val homeConnectingImagePath: String? = null,
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
