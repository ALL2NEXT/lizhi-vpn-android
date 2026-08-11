package xyz.a202132.app.network

import android.util.Log
import kotlinx.coroutines.*
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.util.parallelMapOrdered
import java.net.HttpURLConnection
import java.net.URL

/**
 * 节点延迟测试器
 * 支持 TCPing (Socket) 和 URL Test (ClashAPI HTTP)
 */
class LatencyTester {
    
    private val tag = "LatencyTester"

    private inline fun debugNode(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    private fun reportProgress(
        completed: Int,
        total: Int,
        lastReportedAtNanos: java.util.concurrent.atomic.AtomicLong,
        callback: ((completed: Int, total: Int) -> Unit)?
    ) {
        if (callback == null) return
        val now = System.nanoTime()
        if (completed == total) {
            lastReportedAtNanos.set(now)
            callback(completed, total)
            return
        }
        val previous = lastReportedAtNanos.get()
        if (
            now - previous >= PROGRESS_INTERVAL_NANOS &&
            lastReportedAtNanos.compareAndSet(previous, now)
        ) {
            callback(completed, total)
        }
    }

    private data class UrlTestAttemptResult(
        val result: LatencyResult,
        val shouldRetry: Boolean
    )
    
    data class LatencyResult(
        val nodeId: String,
        val latency: Int,
        val isAvailable: Boolean
    )
    
    // ==================== TCPing (Socket) ====================
    
    /**
     * 测试单个节点 - Socket 连接测试
     * 如果连接成功或被拒绝(RST)，都视为网络连通
     */
    private suspend fun testNodeSimple(node: Node, timeoutMs: Long): LatencyResult {
        return try {
            debugNode { ">>> TCPing: ${node.name} (${node.server}:${node.port})" }
            
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            
            try {
                socket.connect(java.net.InetSocketAddress(node.server, node.port), timeoutMs.toInt())
                
                val elapsed = (System.currentTimeMillis() - start).toInt() 
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                debugNode { "<<< TCPing OK: ${node.name}, ${finalLatency}ms" }
                LatencyResult(node.id, finalLatency, true)
                
            } catch (e: java.net.ConnectException) {
                val elapsed = (System.currentTimeMillis() - start).toInt()
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                if (e.message?.contains("refused") == true) {
                    debugNode { "<<< TCPing REFUSED (Available): ${node.name}, ${finalLatency}ms" }
                    LatencyResult(node.id, finalLatency, true)
                } else {
                    throw e
                }
            } finally {
                runCatching { socket.close() }
            }
            
        } catch (e: java.net.SocketTimeoutException) {
            debugNode { "<<< TCPing TIMEOUT: ${node.name}" }
            LatencyResult(node.id, -2, false)
        } catch (e: Exception) {
            debugNode { "<<< TCPing FAILED: ${node.name}, error=${e.message}" }
            LatencyResult(node.id, -2, false)
        }
    }
    
    /**
     * 批量 TCPing - 并发执行
     */
    suspend fun testAllNodes(
        nodes: List<Node>,
        timeoutMs: Long = xyz.a202132.app.AppConfig.TCPING_TEST_TIMEOUT,
        concurrency: Int = xyz.a202132.app.AppConfig.TCPING_CONCURRENCY,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyResult> = withContext(Dispatchers.IO) {
        Log.d(tag, "========== TCPing START: ${nodes.size} nodes ==========")
        
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(0L)
        val totalCount = nodes.size

        val results = parallelMapOrdered(nodes, concurrency) { node ->
            val result = testNodeSimple(node, timeoutMs.coerceAtLeast(500L))
            val current = completedCount.incrementAndGet()
            reportProgress(current, totalCount, lastProgressAt, onProgress)
            result
        }
        Log.d(tag, "========== TCPing END: ${results.size} results ==========")
        results
    }
    
    // ==================== URL Test (ClashAPI) ====================
    
    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
    
    /**
     * 通过 ClashAPI 测试单个节点的 HTTP 握手延迟
     * 需要 VPN 已连接（sing-box 运行中）
     */
    private suspend fun urlTestNodeOnce(
        node: Node,
        clashApiPort: Int,
        targetUrl: String,
        timeoutMs: Long
    ): UrlTestAttemptResult = withContext(Dispatchers.IO) {
        try {
            debugNode { ">>> URL Test: ${node.name} (${node.id})" }
            
            val encodedId = java.net.URLEncoder.encode(node.id, "UTF-8")
            val encodedTargetUrl = java.net.URLEncoder.encode(targetUrl, "UTF-8")
            val timeout = timeoutMs.coerceAtLeast(500L).toInt()
            
            val apiUrl = "http://127.0.0.1:$clashApiPort/proxies/$encodedId/delay?url=$encodedTargetUrl&timeout=$timeout"
            
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = timeout + 1000
            connection.readTimeout = timeout + 1000
            connection.requestMethod = "GET"
            
            try {
                val responseCode = connection.responseCode
                
                if (responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().readText()
                    // Response: {"delay": 123}
                    val delayMatch = Regex("\"delay\"\\s*:\\s*(\\d+)").find(responseBody)
                    val delay = delayMatch?.groupValues?.get(1)?.toIntOrNull()
                    
                    if (delay != null && delay > 0) {
                        debugNode { "<<< URL Test OK: ${node.name}, ${delay}ms" }
                        UrlTestAttemptResult(
                            result = LatencyResult(node.id, delay, true),
                            shouldRetry = false
                        )
                    } else {
                        debugNode { "<<< URL Test invalid delay: ${node.name}, response=$responseBody" }
                        UrlTestAttemptResult(
                            result = LatencyResult(node.id, -2, false),
                            shouldRetry = false
                        )
                    }
                } else {
                    val errorBody = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                    debugNode { "<<< URL Test HTTP $responseCode: ${node.name}, error=$errorBody" }
                    UrlTestAttemptResult(
                        result = LatencyResult(node.id, -2, false),
                        shouldRetry = responseCode == 503 || responseCode == 504
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            debugNode { "<<< URL Test FAILED: ${node.name}, error=${e.message}" }
            UrlTestAttemptResult(
                result = LatencyResult(node.id, -2, false),
                shouldRetry = true
            )
        }
    }

    private suspend fun urlTestNode(
        node: Node,
        clashApiPort: Int,
        targetUrl: String,
        timeoutMs: Long,
        retryCount: Int
    ): LatencyResult = withContext(Dispatchers.IO) {
        var lastResult = LatencyResult(node.id, -2, false)
        repeat(retryCount.coerceAtLeast(0) + 1) { attempt ->
            val attemptResult = urlTestNodeOnce(node, clashApiPort, targetUrl, timeoutMs)
            lastResult = attemptResult.result
            if (lastResult.isAvailable || !attemptResult.shouldRetry || attempt >= retryCount) {
                return@withContext lastResult
            }
            debugNode { "URL Test retry ${attempt + 1}/$retryCount: ${node.name} (${node.id})" }
        }
        lastResult
    }
    
    /**
     * 批量 URL Test - 并发执行 (通过 ClashAPI)
     * @param clashApiPort ClashAPI 端口 (VPN=9090, 无头=19090)
     */
    suspend fun urlTestAllNodes(
        nodes: List<Node>,
        clashApiPort: Int = 9090,
        targetUrl: String = xyz.a202132.app.AppConfig.URL_TEST_URL,
        timeoutMs: Long = xyz.a202132.app.AppConfig.URL_TEST_TIMEOUT,
        retryCount: Int = xyz.a202132.app.AppConfig.URL_TEST_RETRY_COUNT,
        concurrency: Int = xyz.a202132.app.AppConfig.URL_TEST_CONCURRENCY,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyResult> = withContext(Dispatchers.IO) {
        Log.d(tag, "========== URL Test START: ${nodes.size} nodes (port=$clashApiPort) ==========")
        
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(0L)
        val totalCount = nodes.size

        val results = parallelMapOrdered(nodes, concurrency) { node ->
            val result = urlTestNode(node, clashApiPort, targetUrl, timeoutMs, retryCount)
            val current = completedCount.incrementAndGet()
            reportProgress(current, totalCount, lastProgressAt, onProgress)
            result
        }
        Log.d(tag, "========== URL Test END: ${results.size} results ==========")
        results
    }
}
