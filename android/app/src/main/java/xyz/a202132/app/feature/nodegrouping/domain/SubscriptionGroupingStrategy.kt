package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.SubscriptionGroup

/** 订阅分组策略：保证缓存中的订阅不存在时稳定回退到第一组。 */
internal object SubscriptionGroupingStrategy {
    fun resolveActiveSourceId(
        groups: List<SubscriptionGroup>,
        storedSourceId: String?
    ): String? = storedSourceId
        ?.takeIf { sourceId -> groups.any { it.sourceId == sourceId } }
        ?: groups.firstOrNull()?.sourceId
}
