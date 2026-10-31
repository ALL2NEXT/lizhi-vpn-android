package io.github.HanJason233.chimera.data.model

/** 主题方案使用的显示模式。 */
enum class SkinThemeMode(val storageName: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromStorage(value: String?): SkinThemeMode = entries.firstOrNull {
            it.storageName == value
        } ?: LIGHT
    }
}
