package io.github.HanJason233.chimera.data.local

import androidx.room.Entity

/** 用户从官方订阅源移除的节点，刷新该源时继续忽略。 */
@Entity(
    tableName = "blocked_subscription_nodes",
    primaryKeys = ["sourceId", "nodeId"]
)
data class BlockedSubscriptionNodeEntity(
    val sourceId: String,
    val nodeId: String
)
