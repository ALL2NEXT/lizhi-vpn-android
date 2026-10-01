package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.NodeSource

internal data class CountryGroupingSources(
    val subscriptionSourceIds: Set<String>,
    val personalGroupIds: Set<String>,
    val includeAll: Boolean = false
)

/** Resolves the selected warehouse groups used to build region groups. */
internal object CountryGroupingSourceResolver {
    fun resolve(selectedSourceIds: Set<String>?): CountryGroupingSources {
        if (selectedSourceIds == null) {
            return CountryGroupingSources(
                subscriptionSourceIds = emptySet(),
                personalGroupIds = emptySet(),
                includeAll = true
            )
        }

        val subscriptionIds = mutableSetOf<String>()
        val personalIds = mutableSetOf<String>()
        selectedSourceIds.forEach { id ->
            when (val group = NodeGroupKey.parse(id)) {
                is NodeGroupKey.Subscription -> subscriptionIds += group.sourceId
                is NodeGroupKey.Imported -> personalIds += group.groupId
                else -> Unit
            }
        }
        return CountryGroupingSources(subscriptionIds, personalIds)
    }

    fun nodes(
        allNodes: List<Node>,
        personalGroupNodes: List<Node>,
        sources: CountryGroupingSources
    ): List<Node> {
        if (sources.includeAll) return allNodes
        val subscriptionNodes = allNodes.filter { node ->
            node.source == NodeSource.SUBSCRIPTION &&
                node.subscriptionSourceId?.let { it in sources.subscriptionSourceIds } == true
        }
        return (subscriptionNodes + personalGroupNodes).distinctBy(Node::id)
    }
}
