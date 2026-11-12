package io.github.HanJason233.chimera.data.model

/** 主题方案使用的底部导航栏样式。 */
enum class HomeNavigationStyle(val storageName: String, val displayName: String) {
    // displayName 是设置页的显示文案；storageName 用于持久化，不随文案调整。
    CLASSIC("classic", "经典"),
    CAPSULE("capsule", "胶囊"),
    NATIVE("native", "原生");

    companion object {
        fun fromStorage(value: String?): HomeNavigationStyle =
            entries.firstOrNull { it.storageName == value } ?: CLASSIC
    }
}
