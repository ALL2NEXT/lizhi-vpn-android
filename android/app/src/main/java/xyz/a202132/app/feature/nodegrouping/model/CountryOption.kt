package xyz.a202132.app.feature.nodegrouping.model

/** “地区”分组展示的简化选项，不向界面暴露订阅来源等内部信息。 */
data class CountryOption(
    val code: String,
    val name: String,
    val flag: String,
    val nodeCount: Int
)
