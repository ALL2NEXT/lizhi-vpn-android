package xyz.a202132.app.util

import xyz.a202132.app.data.model.Node

/** “地区”入口展示的简化选项；订阅来源等进阶信息不会暴露给用户。 */
data class CountryOption(
    val code: String,
    val name: String,
    val flag: String,
    val nodeCount: Int
)

/** 将全部普通订阅节点跨订阅组合并为地区选项，并统计每个地区的节点数。 */
fun countryOptions(nodes: List<Node>): List<CountryOption> =
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

/**
 * 为小白用户选择一个地区内最合适的节点：优先可用且有有效延迟的节点，
 * 再按延迟和订阅内顺序兜底。
 */
fun preferredNodeForCountry(nodes: List<Node>, countryCode: String): Node? {
    val normalizedCode = CountryCodeResolver.normalizeCountryCode(countryCode) ?: return null
    return nodes.asSequence()
        .filter { it.getCountryCode() == normalizedCode }
        .minWithOrNull(
            compareBy<Node> { !it.isAvailable }
                .thenBy { it.latency <= 0 }
                .thenBy { if (it.latency > 0) it.latency else Int.MAX_VALUE }
                .thenBy(Node::sortOrder)
                .thenBy(Node::id)
        )
}
