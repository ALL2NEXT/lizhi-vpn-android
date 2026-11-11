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

    @Query("SELECT n.* FROM nodes AS n INNER JOIN node_group_members AS m ON n.id = m.nodeId WHERE m.groupId = :groupId ORDER BY m.sortOrder ASC, n.sortOrder ASC, n.latency ASC")
    fun getNodesByGroup(groupId: String): Flow<List<Node>>

    @Query("SELECT DISTINCT n.* FROM nodes AS n INNER JOIN node_group_members AS m ON n.id = m.nodeId WHERE m.groupId IN (:groupIds) ORDER BY n.sortOrder ASC, n.latency ASC")
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

    @Query(
        """
        UPDATE node_groups SET
            iconMode = :iconMode,
            customIconUrl = :customIconUrl,
            localIconFile = :localIconFile
        WHERE id = :groupId
        """
    )
    suspend fun updateNodeGroupIcon(
        groupId: String,
        iconMode: String,
        customIconUrl: String?,
        localIconFile: String?
    ): Int

    @Query(
        """
        UPDATE node_groups SET
            name = :name,
            subscriptionUrl = :subscriptionUrl,
            userAgentMode = :userAgentMode,
            customUserAgent = :customUserAgent,
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
        userAgentMode: String,
        customUserAgent: String?,
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
        subscriptionUrl: String?,
        userAgentMode: String,
        customUserAgent: String?
    ): Boolean {
        if (
            updateNodeGroupMetadataFields(
                groupId,
                name,
                subscriptionUrl,
                userAgentMode,
                customUserAgent
            ) == 0
        ) return false
        updateLocalGroupNodeNames(groupId, name)
        return true
    }

    @Query(
        """
        UPDATE node_groups SET
            name = :name,
            subscriptionUrl = :subscriptionUrl,
            userAgentMode = :userAgentMode,
            customUserAgent = :customUserAgent
        WHERE id = :groupId
        """
    )
    suspend fun updateNodeGroupMetadataFields(
        groupId: String,
        name: String,
        subscriptionUrl: String?,
        userAgentMode: String,
        customUserAgent: String?
    ): Int

    @Transaction
    suspend fun replaceLocalGroupNodes(
        groupId: String,
        name: String,
        subscriptionUrl: String?,
        userAgentMode: String,
        customUserAgent: String?,
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
                userAgentMode = userAgentMode,
                customUserAgent = customUserAgent,
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

    @Query("DELETE FROM home_node_test_results WHERE nodeId = :nodeId")
    suspend fun deleteHomeNodeTestResults(nodeId: String): Int

    @Query("DELETE FROM nodes WHERE id = :nodeId AND source = 'SUBSCRIPTION' AND subscriptionSourceId = :sourceId")
    suspend fun deleteSubscriptionNodeRow(sourceId: String, nodeId: String): Int

    /** 直接删除本地节点记录和测试结果，订阅刷新仍可重新导入源中的节点。 */
    @Transaction
    suspend fun deleteSubscriptionNodeFromSource(sourceId: String, nodeId: String): Boolean {
        if (deleteSubscriptionNodeRow(sourceId, nodeId) == 0) return false
        deleteHomeNodeTestResults(nodeId)
        return true
    }

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
            val updatedRows = updateTcpingResult(
                update.nodeId,
                update.latency,
                update.isAvailable,
                testedAt
            )
            val stored = getHomeNodeTestResults(update.nodeId)
                ?: HomeNodeTestResultsEntity(nodeId = update.nodeId)
            saveHomeNodeTestResults(
                stored.copy(tcpingLatency = update.latency, updatedAt = testedAt)
            )
            updatedRows
        }
    }

    @Query("UPDATE nodes SET latency = :latency, urlTestLatency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateUrlTestResult(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Transaction
    suspend fun updateUrlTestResults(updates: List<NodeLatencyUpdate>, testedAt: Long): Int {
        return updates.sumOf { update ->
            val updatedRows = updateUrlTestResult(
                update.nodeId,
                update.latency,
                update.isAvailable,
                testedAt
            )
            val stored = getHomeNodeTestResults(update.nodeId)
                ?: HomeNodeTestResultsEntity(nodeId = update.nodeId)
            saveHomeNodeTestResults(
                stored.copy(urlTestLatency = update.latency, updatedAt = testedAt)
            )
            updatedRows
        }
    }

    @Query("UPDATE nodes SET country = :country, countryName = :countryName WHERE id = :nodeId")
    suspend fun updateNodeLocation(nodeId: String, country: String?, countryName: String?): Int

    /** 查询规则改变后清空旧结果，确保节点按新顺序重新识别。 */
    @Query("UPDATE nodes SET country = NULL, countryName = NULL")
    suspend fun clearNodeLocations(): Int

    @Transaction
    suspend fun updateNodeLocations(updates: List<NodeLocationUpdate>): Int {
        return updates.sumOf { update ->
            updateNodeLocation(update.nodeId, update.country, update.countryName)
        }
    }

    @Query("SELECT * FROM home_node_test_results WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getHomeNodeTestResults(nodeId: String): HomeNodeTestResultsEntity?

    @Query("SELECT * FROM home_node_test_results WHERE nodeId = :nodeId LIMIT 1")
    fun observeHomeNodeTestResults(nodeId: String): Flow<HomeNodeTestResultsEntity?>

    @Query("SELECT * FROM home_node_test_results")
    fun observeAllHomeNodeTestResults(): Flow<List<HomeNodeTestResultsEntity>>

    @Query(
        """
        UPDATE nodes SET
            latency = -1,
            tcpingLatency = -1,
            urlTestLatency = -1,
            isAvailable = 1,
            lastTestedAt = 0,
            downloadMbps = 0,
            uploadMbps = 0
        WHERE id IN (:nodeIds)
        """
    )
    suspend fun resetNodeTestFields(nodeIds: List<String>): Int

    @Query("DELETE FROM home_node_test_results WHERE nodeId IN (:nodeIds)")
    suspend fun deleteStoredNodeTestResults(nodeIds: List<String>): Int

    /** 清除节点列表展示的测试结果，并兼容 SQLite 的 IN 参数数量限制。 */
    @Transaction
    suspend fun clearNodeTestResults(nodeIds: List<String>): Int {
        var clearedNodeCount = 0
        nodeIds.distinct().chunked(900).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                clearedNodeCount += resetNodeTestFields(chunk)
                deleteStoredNodeTestResults(chunk)
            }
        }
        return clearedNodeCount
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHomeNodeTestResults(results: HomeNodeTestResultsEntity)

    @Transaction
    suspend fun saveLinkTypeTestResult(nodeId: String, resultJson: String) {
        val stored = getHomeNodeTestResults(nodeId) ?: HomeNodeTestResultsEntity(nodeId = nodeId)
        saveHomeNodeTestResults(
            stored.copy(
                linkTypeResultJson = resultJson,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Transaction
    suspend fun saveUnlockTestResult(nodeId: String, resultJson: String) {
        val stored = getHomeNodeTestResults(nodeId) ?: HomeNodeTestResultsEntity(nodeId = nodeId)
        saveHomeNodeTestResults(
            stored.copy(
                unlockTestResultJson = resultJson,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Query("UPDATE nodes SET downloadMbps = :downloadMbps WHERE id = :nodeId")
    suspend fun updateNodeDownloadMbps(nodeId: String, downloadMbps: Float): Int

    @Transaction
    suspend fun updateDownloadMbps(nodeId: String, downloadMbps: Float): Int {
        val updatedRows = updateNodeDownloadMbps(nodeId, downloadMbps)
        val stored = getHomeNodeTestResults(nodeId) ?: HomeNodeTestResultsEntity(nodeId = nodeId)
        saveHomeNodeTestResults(
            stored.copy(
                downloadMbps = downloadMbps,
                updatedAt = System.currentTimeMillis()
            )
        )
        return updatedRows
    }

}
