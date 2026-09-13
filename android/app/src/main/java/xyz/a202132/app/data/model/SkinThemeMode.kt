package xyz.a202132.app.data.model

/** 样式方案使用的主题模式。 */
enum class SkinThemeMode(val storageName: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromStorage(value: String?): SkinThemeMode = entries.firstOrNull {
            it.storageName == value
        } ?: SYSTEM
    }
}
