package xyz.a202132.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.SubscriptionGroup

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY CASE WHEN latency < 0 THEN 1 ELSE 0 END, latency ASC")
    fun getAllAvailableNodes(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC, latency ASC")
    fun getAllNodes(): Flow<List<Node>>

    @Query("SELECT * FROM nodes WHERE source = 'SUBSCRIPTION' ORDER BY sortOrder ASC, latency ASC")
    fun getSubscriptionNodes(): Flow<List<Node>>

    @Query("SELECT DISTINCT subscriptionSourceId AS sourceId, subscriptionSourceName AS name FROM nodes WHERE source = 'SUBSCRIPTION' AND subscriptionSourceId IS NOT NULL ORDER BY subscriptionSourceName ASC")
    fun getSubscriptionGroups(): Flow<List<SubscriptionGroup>>

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' ORDER BY favoriteCreatedAt ASC, sortOrder ASC, latency ASC")
    fun getFavoriteNodes(): Flow<List<Node>>

    @Query("SELECT favoriteSourceNodeId FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId IS NOT NULL")
    fun getFavoriteSourceNodeIds(): Flow<List<String?>>
    
    @Query("SELECT * FROM nodes WHERE isAvailable = 1 ORDER BY CASE WHEN latency < 0 THEN 1 ELSE 0 END, latency ASC LIMIT 1")
    suspend fun getBestNode(): Node?
    
    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): Node?

    @Query("SELECT * FROM nodes WHERE source = 'FAVORITE' AND favoriteSourceNodeId = :sourceNodeId LIMIT 1")
    suspend fun getFavoriteBySourceNodeId(sourceNodeId: String): Node?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)
    
    @Update
    suspend fun updateNode(node: Node)
    
    @Delete
    suspend fun deleteNode(node: Node)
    
    @Query("DELETE FROM nodes")
    suspend fun deleteAllNodes()

    @Query("DELETE FROM nodes WHERE source = 'SUBSCRIPTION'")
    suspend fun deleteSubscriptionNodes()

    @Query("DELETE FROM nodes WHERE id = :nodeId")
    suspend fun deleteNodeById(nodeId: String)

    @Transaction
    suspend fun replaceAllNodes(nodes: List<Node>) {
        deleteAllNodes()
        insertNodes(nodes)
    }

    @Transaction
    suspend fun replaceSubscriptionNodes(nodes: List<Node>) {
        deleteSubscriptionNodes()
        insertNodes(nodes)
    }
    
    @Query("UPDATE nodes SET latency = :latency, tcpingLatency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateTcpingResult(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Query("UPDATE nodes SET latency = :latency, urlTestLatency = :latency, isAvailable = :isAvailable, lastTestedAt = :testedAt WHERE id = :nodeId")
    suspend fun updateUrlTestResult(nodeId: String, latency: Int, isAvailable: Boolean, testedAt: Long): Int

    @Query("UPDATE nodes SET downloadMbps = :downloadMbps WHERE id = :nodeId")
    suspend fun updateDownloadMbps(nodeId: String, downloadMbps: Float): Int

    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun getNodeCount(): Int
}
