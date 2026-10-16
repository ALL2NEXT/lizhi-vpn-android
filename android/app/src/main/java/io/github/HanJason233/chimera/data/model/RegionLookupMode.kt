package io.github.HanJason233.chimera.data.model

/** 节点地区组 IP 归属地查询方式。 */
enum class RegionLookupMode(val title: String, val description: String) {
    LOCAL("本地", "只查询本地 geoip.dat；未命中不调用远程归属地接口，域名节点仍需解析到 IP。"),
    REMOTE("远程", "通过远程 IP 归属地服务查询。"),
    HYBRID("混合", "优先查询本地 geoip.dat，未命中时再查询远程服务。")
}
