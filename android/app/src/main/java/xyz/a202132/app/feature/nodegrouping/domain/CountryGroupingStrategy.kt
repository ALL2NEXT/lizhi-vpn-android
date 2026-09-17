package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.feature.nodegrouping.model.CountryOption
import xyz.a202132.app.util.CountryCodeResolver

/** 地区分组策略：跨订阅聚合地区并统计节点数量。 */
internal object CountryGroupingStrategy {
    // 按从中国周边向外扩展的顺序展示常见地区，避免地区名排序导致位置变化。
    private val proximityOrder = listOf(
        "CN", "HK", "MO", "TW", "KR", "JP", "MN", "RU",
        "VN", "LA", "MM", "TH", "KH", "SG", "MY", "BN", "PH", "ID",
        "BT", "NP", "BD", "IN", "PK", "AF", "KZ", "KG", "TJ", "UZ", "TM",
        "AU", "NZ",
        "DE", "GB", "FR", "NL", "CH", "IT", "ES", "SE", "NO", "FI", "DK",
        "TR", "AE", "IL", "SA",
        "CA", "US", "MX", "BR", "AR", "CL",
        "ZA", "EG", "NG", "KE"
    ).withIndex().associate { (index, code) -> code to index }

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
            .sortedWith(
                compareBy<CountryOption> { proximityOrder[it.code] ?: Int.MAX_VALUE }
                    .thenBy(CountryOption::name)
                    .thenBy(CountryOption::code)
            )
}
