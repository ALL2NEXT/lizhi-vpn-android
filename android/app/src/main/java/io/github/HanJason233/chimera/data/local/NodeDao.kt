package io.github.HanJason233.chimera.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupEntity
import io.github.HanJason233.chimera.data.model.NodeGroupMemberEntity
import io.github.HanJason233.chimera.data.model.SubscriptionGroup

data class NodeLatencyUpdate(
    val nodeId: String,
    val latency: Int,
    val isAvailable: Boolean
)

data class NodeLocationUpdate(
    val nodeId: String,
    val country: String?,
    val countryName: String?
)

data class NodeGroupWithNodeCount(
    @Embedded val group: NodeGroupEntity,
    @ColumnInfo(name = "nodeCount") val nodeCount: Int
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, latency ASC")
    fun getAllNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' ORDER BY sortOrder ASC, latency ASC")
    fun getSubscriptionNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId = :sourceId ORDER BY sortOrder ASC, latency ASC")
    fun getSubscriptionNodesBySource(sourceId: String): Flow<List<Node>>

    @Query("SELECT subscriptionSourceId AS sourceId, COALESCE(MAX(subscriptionSourceName), subscriptionSourceId) AS name, COALESCE(MAX(subscriptionUpdatedAt), 0) AS updatedAt FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId IS NOT NULL GROUP BY subscriptionSourceId ORDER BY CAST(subscriptionSourceId AS INTEGER) ASC, name ASC")
    fun getSubscriptionGroups(): Flow<List<SubscriptionGroup>>

    /** 私人源摘要；地区组由应用按地区规则动态汇总。 */
    @Query("SELECT * FROM node_groups WHERE type IN ('IMPORTED', 'SUBSCRIPTION') ORDER BY sortOrder ASC, createdAt ASC")
    fun getUserSubscriptionGroups(): Flow<List<NodeGroupEntity>>

    @Query(
        """
        SELECT g.*, COUNT(m.nodeId) AS nodeCount
        FROM node_groups AS g
        LEFT JOIN node_group_members AS m ON g.id = m.groupId
        WHERE g.type IN ('IMPORTED', 'SUBSCRIPTION')
        GROUP BY g.id
        ORDER BY g.sortOrder ASC, g.createdAt ASC
        """
    )
    fun getUserSubscriptionGroupsWithCount(): Flow<List<NodeGroupWithNodeCount>>

    @Query(
        """
        SELECT n.* FROM nodes AS n
        INNER JOIN node_group_members AS m ON n.id = m.nodeId
        WHERE m.groupId = :groupId
        ORDER BY m.sortOrder ASC, n.sortOrder ASC, n.latency ASC
        """
    )
    fun getNodesByGroup(groupId: String): Flow<List<Node>>

    @Query(
        """
        SELECT DISTINCT n.* FROM nodes AS n
        INNER JOIN node_group_members AS m ON n.id = m.nodeId
        WHERE m.groupId IN (:groupIds)
        ORDER BY n.sortOrder ASC, n.latency ASC
        """
    )
    fun getNodesByGroups(groupIds: List<String>): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): Node?

    @Query("SELECT * FROM node_groups WHERE id = :groupId LIMIT 1")
    suspend fun getNodeGroupById(groupId: String): NodeGroupEntity?

    @Query("SELECT * FROM nodes WHERE id = :id")
    fun observeNodeById(id: String): Flow<Node?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodeGroup(group: NodeGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodeGroupMembers(members: List<NodeGroupMemberEntity>)

    @Transaction
    suspend fun insertNodeGroupWithNodes(
        group: NodeGroupEntity,
        nodes: List<Node>,
        members: List<NodeGroupMemberEntity>
    ) {
        insertNodeGroup(group)
        if (nodes.isNotEmpty()) insertNodes(nodes)
        if (members.isNotEmpty()) insertNodeGroupMembers(members)
    }

    @Transaction
    suspend fun appendNodesToGroup(
        nodes: List<Node>,
        members: List<NodeGroupMemberEntity>
    ) {
        if (nodes.isNotEmpty()) insertNodes(nodes)
        if (members.isNotEmpty()) insertNodeGroupMembers(members)
    }

    @Query("DELETE FROM node_group_members WHERE groupId = :groupId")
    suspend fun deleteNodeGroupMembers(groupId: String)

    @Query("DELETE FROM node_groups WHERE id = :groupId")
    suspend fun deleteNodeGroup(groupId: String)

    @Query("DELETE FROM node_group_members WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun deleteNodeGroupMember(groupId: String, nodeId: String): Int

    @Query("DELETE FROM nodes WHERE source = 'LOCAL_GROUP' AND subscriptionSourceId = :groupId")
    suspend fun deleteLocalGroupNodes(groupId: String)

    @Query("UPDATE node_groups SET name = :name, subscriptionUrl = :subscriptionUrl WHERE id = :groupId")
    suspend fun updateNodeGroup(groupId: String, name: String, subscriptionUrl: String?): Int

    @Query(
        """
        UPDATE node_groups SET
            name = :name,
            subscriptionUrl = :subscriptionUrl,
            subscriptionUploadBytes = :subscriptionUploadBytes,
            subscriptionDownloadBytes = :subscriptionDownloadBytes,
            subscriptionTotalBytes = :subscriptionTotalBytes,
            subscriptionExpireAt = :subscriptionExpireAt,
            subscriptionUpdatedAt = :subscriptionUpdatedAt
        WHERE id = :groupId
        """
    )
    suspend fun updateNodeGroupWithSubscriptionInfo(
        groupId: String,
        name: String,
        subscriptionUrl: String?,
        subscriptionUploadBytes: Long?,
        subscriptionDownloadBytes: Long?,
        subscriptionTotalBytes: Long?,
        subscriptionExpireAt: Long?,
        subscriptionUpdatedAt: Long
    ): Int

    @Query("UPDATE nodes SET subscriptionSourceName = :name WHERE source = 'LOCAL_GROUP' AND subscriptionSourceId = :groupId")
    suspend fun updateLocalGroupNodeNames(groupId: String, name: String): Int

    @Transaction
    suspend fun updateNodeGroupMetadata(
        groupId: String,
        name: String,
        subscriptionUrl: String?
    ): Boolean {
        if (updateNodeGroup(groupId, name, subscriptionUrl) == 0) return false
        updateLocalGroupNodeNames(groupId, name)
        return true
    }

    @Transaction
    suspend fun replaceLocalGroupNodes(
        groupId: String,
        name: String,
        subscriptionUrl: String?,
        subscriptionUploadBytes: Long?,
        subscriptionDownloadBytes: Long?,
        subscriptionTotalBytes: Long?,
        subscriptionExpireAt: Long?,
        subscriptionUpdatedAt: Long,
        nodes: List<Node>,
        members: List<NodeGroupMemberEntity>
    ): Boolean {
        if (
            updateNodeGroupWithSubscriptionInfo(
                groupId = groupId,
                name = name,
                subscriptionUrl = subscriptionUrl,
                subscriptionUploadBytes = subscriptionUploadBytes,
                subscriptionDownloadBytes = subscriptionDownloadBytes,
                subscriptionTotalBytes = subscriptionTotalBytes,
                subscriptionExpireAt = subscriptionExpireAt,
                subscriptionUpdatedAt = subscriptionUpdatedAt
            ) == 0
        ) return false
        updateLocalGroupNodeNames(groupId, name)
        deleteNodeGroupMembers(groupId)
        deleteLocalGroupNodes(groupId)
        if (nodes.isNotEmpty()) insertNodes(nodes)
        if (members.isNotEmpty()) insertNodeGroupMembers(members)
        return true
    }

    @Transaction
    suspend fun deleteNodeGroupWithNodes(groupId: String) {
        deleteNodeGroupMembers(groupId)
        deleteLocalGroupNodes(groupId)
        deleteNodeGroup(groupId)
    }

    @Query("DELETE FROM nodes WHERE id = :nodeId AND source = 'LOCAL_GROUP' AND subscriptionSourceId = :groupId")
    suspend fun deleteLocalGroupNode(groupId: String, nodeId: String): Int

    @Transaction
    suspend fun deleteLocalGroupNodeFromGroup(groupId: String, nodeId: String): Boolean {
        if (deleteNodeGroupMember(groupId, nodeId) == 0) return false
        return deleteLocalGroupNode(groupId, nodeId) > 0
    }

    @Transaction
    suspend fun replaceNodeGroupMembers(groupId: String, members: List<NodeGroupMemberEntity>) {
        deleteNodeGroupMembers(groupId)
        insertNodeGroupMembers(members)
    }
    
    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION'")
    suspend fun deleteSubscriptionNodes()

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId = :sourceId")
    suspend fun deleteSubscriptionNodesBySource(sourceId: String)

    @Query("DELETE FROM nodes WHERE id = :nodeId")
    suspend fun deleteNodeById(nodeId: String)

    @Query("DELETE FROM nodes WHERE id IN (:nodeIds)")
    suspend fun deleteNodesByIds(nodeIds: List<String>): Int

    @Transaction
    suspend fun replaceSubscriptionNodes(nodes: List<Node>) {
        deleteSubscriptionNodes()
        insertNodes(nodes)
    }

    @Transaction
    suspend fun replaceSubscriptionNodesBySource(sourceId: String, nodes: List<Node>) {
        deleteSubscriptionNodesBySource(sourceId)
        insertNodes(nodes)
    }
    
    @Query("UPDATE nodes SET latency = :latency, tcpingLatency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateTcpingResult(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Transaction
    suspend fun updateTcpingResults(updates: List<NodeLatencyUpdate>, testedAt: Long): Int {
        return updates.sumOf { update ->
            updateTcpingResult(update.nodeId, update.latency, update.isAvailable, testedAt)
        }
    }

    @Query("UPDATE nodes SET latency = :latency, urlTestLatency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateUrlTestResult(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Transaction
    suspend fun updateUrlTestResults(updates: List<NodeLatencyUpdate>, testedAt: Long): Int {
        return updates.sumOf { update ->
            updateUrlTestResult(update.nodeId, update.latency, update.isAvailable, testedAt)
        }
    }

    @Query("UPDATE nodes SET country = :country, countryName = :countryName WHERE id = :nodeId")
    suspend fun updateNodeLocation(nodeId: String, country: String?, countryName: String?): Int

    @Transaction
    suspend fun updateNodeLocations(updates: List<NodeLocationUpdate>): Int {
        return updates.sumOf { update ->
            updateNodeLocation(update.nodeId, update.country, update.countryName)
        }
    }

    @Query("UPDATE nodes SET downloadMbps = :downloadMbps WHERE id = :nodeId")
    suspend fun updateDownloadMbps(nodeId: String, downloadMbps: Float): Int

}
