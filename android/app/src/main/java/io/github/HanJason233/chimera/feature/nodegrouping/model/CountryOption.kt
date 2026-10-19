package io.github.HanJason233.chimera.feature.nodegrouping.model

import io.github.HanJason233.chimera.data.model.NodeGroupType

enum class CountryContinent(val title: String) {
    ASIA("亚洲"),
    NORTH_AMERICA("北美洲"),
    OCEANIA("大洋洲"),
    EUROPE("欧洲"),
    AFRICA("非洲"),
    SOUTH_AMERICA("南美洲"),
    ANTARCTICA("南极洲")
}

/** “地区”分组展示的简化选项，不向界面暴露订阅来源等内部信息。 */
data class CountryOption(
    val code: String,
    val name: String,
    val marker: String,
    val nodeCount: Int,
    val continent: CountryContinent,
    val type: NodeGroupType = NodeGroupType.REGION
)
