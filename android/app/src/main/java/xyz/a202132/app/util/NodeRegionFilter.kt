package xyz.a202132.app.util

import xyz.a202132.app.data.model.Node

/** 汇总全部普通订阅节点中实际存在且可识别的地区。 */
fun availableNodeRegionCodes(nodes: List<Node>): List<String> =
    nodes.asSequence()
        .mapNotNull(Node::getCountryCode)
        .distinct()
        .sortedWith(compareBy({ CountryCodeResolver.displayName(it) ?: it }, { it }))
        .toList()

/**
 * 按地区白名单过滤节点。白名单为空时不做过滤，避免用户必须逐项勾选才能看到节点。
 */
fun filterNodesByRegionWhitelist(nodes: List<Node>, selectedRegionCodes: Set<String>): List<Node> {
    val whitelist = selectedRegionCodes.mapNotNullTo(mutableSetOf()) {
        CountryCodeResolver.normalizeRegionCode(it)
    }
    if (whitelist.isEmpty()) return nodes
    return nodes.filter { node -> node.getCountryCode() in whitelist }
}
