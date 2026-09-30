package xyz.a202132.app.data.model

/** 主题方案使用的底部导航栏样式。 */
enum class HomeNavigationStyle(val storageName: String, val displayName: String) {
    CLASSIC("classic", "经典"),
    CAPSULE("capsule", "胶囊式"),
    NATIVE("native", "原生式");

    companion object {
        fun fromStorage(value: String?): HomeNavigationStyle =
            entries.firstOrNull { it.storageName == value } ?: CLASSIC
    }
}
