package xyz.a202132.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.SubscriptionGroup

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

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, latency ASC")
    fun getAllNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' ORDER BY sortOrder ASC, latency ASC")
    fun getSubscriptionNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId = :sourceId ORDER BY sortOrder ASC, latency ASC")
    fun getSubscriptionNodesBySource(sourceId: String): Flow<List<Node>>

    @Query("SELECT DISTINCT subscriptionSourceId AS sourceId, subscriptionSourceName AS name FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId IS NOT NULL ORDER BY subscriptionSourceName ASC")
    fun getSubscriptionGroups(): Flow<List<SubscriptionGroup>>

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' ORDER BY favoriteCreatedAt ASC, sortOrder ASC, latency ASC")
    fun getFavoriteNodes(): Flow<List<Node>>

    @Query("SELECT favoriteSourceNodeId FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId IS NOT NULL")
    fun getFavoriteSourceNodeIds(): Flow<List<String?>>
    
    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): Node?

    @Query("SELECT * FROM nodes WHERE id = :id")
    fun observeNodeById(id: String): Flow<Node?>

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId = :sourceNodeId LIMIT 1")
    suspend fun getFavoriteBySourceNodeId(sourceNodeId: String): Node?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)
    
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
