package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.feature.nodegrouping.model.CountryOption
import xyz.a202132.app.util.CountryCodeResolver

/** 地区分组策略：跨订阅聚合地区并统计节点数量。 */
internal object CountryGroupingStrategy {
    fun groups(nodes: List<Node>): List<CountryOption> =
        nodes.asSequence()
            .mapNotNull(Node::getCountryCode)
            .groupingBy { it }
            .eachCount()
            .mapNotNull { (code, nodeCount) ->
                val name = CountryCodeResolver.displayName(code) ?: return@mapNotNull null
                CountryOption(
                    code = code,
                    name = name,
                    flag = CountryCodeResolver.flagEmoji(code) ?: return@mapNotNull null,
                    nodeCount = nodeCount
                )
            }
            .sortedWith(compareBy(CountryOption::name, CountryOption::code))
}
