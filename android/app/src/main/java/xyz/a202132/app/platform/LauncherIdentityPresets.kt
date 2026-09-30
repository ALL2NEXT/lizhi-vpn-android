package xyz.a202132.app.platform

data class LauncherIdentityPreset(
    val id: String,
    val displayName: String,
    val iconDescription: String,
    val aliasName: String
)

object LauncherIdentityPresets {
    val all = listOf(
        LauncherIdentityPreset("android", "Firefly", "Android 机器人", "LauncherAliasAndroid"),
        LauncherIdentityPreset("shield", "流萤", "盾牌", "LauncherAliasShield"),
        LauncherIdentityPreset("bolt", "星桥", "闪电", "LauncherAliasBolt"),
        LauncherIdentityPreset("cloud", "云舟", "云朵", "LauncherAliasCloud"),
        LauncherIdentityPreset("public", "极光", "地球", "LauncherAliasPublic"),
        LauncherIdentityPreset("rocket", "蓝鲸", "火箭", "LauncherAliasRocket"),
        LauncherIdentityPreset("wifi", "轻舟", "Wi-Fi", "LauncherAliasWifi"),
        LauncherIdentityPreset("hub", "夜航", "节点", "LauncherAliasHub")
    )

    fun find(id: String): LauncherIdentityPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}
