package xyz.a202132.app.viewmodel

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource

internal data class SmartTestConcurrency(
    val tcping: Int,
    val urlTest: Int,
    val isPublicNodePool: Boolean
)

/** Resolves smart-connection concurrency without changing manual node-test settings. */
internal object PublicNodePoolOptimization {
    fun resolveConcurrency(
        nodes: List<Node>,
        enabled: Boolean,
        nodeThreshold: Int,
        publicPoolTcpingConcurrency: Int,
        publicPoolUrlTestConcurrency: Int,
        defaultTcpingConcurrency: Int,
        defaultUrlTestConcurrency: Int
    ): SmartTestConcurrency {
        val sourceId = nodes.firstOrNull()?.subscriptionSourceId
        val isSingleSubscriptionGroup = !sourceId.isNullOrBlank() && nodes.all { node ->
            node.source == NodeSource.SUBSCRIPTION && node.subscriptionSourceId == sourceId
        }
        val isPublicNodePool = enabled &&
            nodes.size > nodeThreshold &&
            isSingleSubscriptionGroup

        return if (isPublicNodePool) {
            SmartTestConcurrency(
                tcping = publicPoolTcpingConcurrency,
                urlTest = publicPoolUrlTestConcurrency,
                isPublicNodePool = true
            )
        } else {
            SmartTestConcurrency(
                tcping = defaultTcpingConcurrency,
                urlTest = defaultUrlTestConcurrency,
                isPublicNodePool = false
            )
        }
    }
}
