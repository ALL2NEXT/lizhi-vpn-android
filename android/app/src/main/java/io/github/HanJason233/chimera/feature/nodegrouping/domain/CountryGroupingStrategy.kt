package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.util.CountryCodeResolver

/** 地区组策略：将所选仓库源的节点按地区聚合并统计数量。 */
internal object CountryGroupingStrategy {
    // 按从中国周边向外扩展的顺序展示常见地区，避免地区名排序导致位置变化。
    private val proximityOrder = listOf(
        "CN", "TW", "HK", "MO", "KR", "JP", "MN", "RU",
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
                val name = when (code) {
                    "CN" -> "中国大陆"
                    "TW" -> "中国台湾"
                    "HK" -> "中国香港"
                    "MO" -> "中国澳门"
                    else -> CountryCodeResolver.displayName(code) ?: return@mapNotNull null
                }
                CountryOption(
                    code = code,
                    name = name,
                    marker = if (code == "TW") "TW" else {
                        CountryCodeResolver.flagEmoji(code) ?: return@mapNotNull null
                    },
                    nodeCount = nodeCount
                )
            }
            .sortedWith(
                compareBy<CountryOption> { proximityOrder[it.code] ?: Int.MAX_VALUE }
                    .thenBy(CountryOption::name)
                    .thenBy(CountryOption::code)
            )
}
