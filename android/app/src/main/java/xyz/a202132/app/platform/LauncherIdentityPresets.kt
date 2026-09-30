package xyz.a202132.app.platform

import xyz.a202132.app.R

data class LauncherIdentityPreset(
    val id: String,
    val displayName: String,
    val previewIconResource: Int,
    val aliasName: String
)

object LauncherIdentityPresets {
    // 仅保留前三种名称和图标组合，供主题设置与桌面入口共用。
    val all = listOf(
        LauncherIdentityPreset("android", "Firefly", R.mipmap.ic_launcher, "LauncherAliasAndroid"),
        LauncherIdentityPreset("shield", "流萤", R.mipmap.ic_launcher_shield, "LauncherAliasShield"),
        LauncherIdentityPreset("bolt", "星桥", R.mipmap.ic_launcher_bolt, "LauncherAliasBolt")
    )

    fun find(id: String): LauncherIdentityPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}
