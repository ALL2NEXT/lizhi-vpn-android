package io.github.HanJason233.chimera.network.nodetest.bandwidth

import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.network.nodetest.common.NodeTestProxyManager

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProxyCore

/** 通过临时节点代理，以多条独立连接在固定计时窗口内统计下载流量。 */
internal class NodeDownloadBandwidthTester(private val context: Context) {
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    /** 取消带宽测试的所有活动下载请求。 */
    fun cancelActiveTests() {
        activeCalls.toList().forEach { call -> call.cancel() }
    }

    suspend fun test(
        node: Node,
        downloadUrl: String,
        durationMs: Long,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int,
        core: ProxyCore
    ): Float = withContext(Dispatchers.IO) {
        val session = NodeTestProxyManager.createSession(
            context,
            node,
            hysteriaUpMbps,
            hysteriaDownMbps,
            core
        )
        if (!session.start()) return@withContext 0f

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", session.socksPort))
        // 每条测速流使用独立连接池，确保 HTTP/2 也不会把四条流合并到同一条 TCP 连接。
        val clients = List(DOWNLOAD_STREAM_COUNT) {
            NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val effectiveDurationMs = durationMs.coerceAtLeast(MIN_DURATION_MS)
            val startedAt = System.nanoTime()
            val measuredAt = startedAt + WARM_UP_DURATION_MS * NANOS_PER_MILLISECOND
            val deadline = measuredAt + effectiveDurationMs * NANOS_PER_MILLISECOND

            val samples = coroutineScope {
                clients.map { client ->
                    async(Dispatchers.IO) {
                        downloadStream(client, request, measuredAt, deadline)
                    }
                }.awaitAll()
            }
            // 如果所有连接都在正式窗口结束前失败，不用残缺样本推算带宽。
            if (samples.none(DownloadSample::completedWindow)) return@withContext 0f
            val totalBytes = samples.sumOf(DownloadSample::bytes)
            val elapsedNanos = ((System.nanoTime().coerceAtMost(deadline) - measuredAt)
                .coerceAtLeast(NANOS_PER_MILLISECOND))
            val speedMbps = (
                totalBytes * 8.0 / elapsedNanos * NANOS_PER_SECOND / BITS_PER_MEGABIT
            ).toFloat()
            Log.d(
                TAG,
                "Download bandwidth finished: node=${node.id}, streams=$DOWNLOAD_STREAM_COUNT, " +
                    "warmUpMs=$WARM_UP_DURATION_MS, bytes=$totalBytes, speedMbps=$speedMbps"
            )
            speedMbps
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Download bandwidth test failed for ${node.getDisplayName()}", error)
            0f
        } finally {
            clients.forEach { client ->
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            session.stop()
        }
    }

    private suspend fun downloadStream(
        client: OkHttpClient,
        request: Request,
        measuredAt: Long,
        deadline: Long
    ): DownloadSample {
        var totalBytes = 0L
        // 文件提前下载完后继续请求；同一连接先预热传输，但预热流量不计入结果。
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val call = client.newCall(request)
            activeCalls.add(call)
            call.timeout().timeout(remainingMs(deadline), TimeUnit.MILLISECONDS)
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (!response.isSuccessful) return DownloadSample(totalBytes, false)
                    val source = response.body?.source()
                        ?: throw IOException("ResponseBody is null")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (System.nanoTime() < deadline) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        if (System.nanoTime() >= measuredAt) totalBytes += read
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() < deadline) {
                    Log.d(TAG, "Download stream ended early: ${error.javaClass.simpleName}")
                    return DownloadSample(totalBytes, false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                activeCalls.remove(call)
            }
        }
        return DownloadSample(totalBytes, true)
    }

    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)

    private data class DownloadSample(val bytes: Long, val completedWindow: Boolean)

    private companion object {
        const val TAG = "NodeBandwidthTester"
        const val DOWNLOAD_STREAM_COUNT = 4
        const val WARM_UP_DURATION_MS = 1_000L
        const val MIN_DURATION_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val BITS_PER_MEGABIT = 1_000_000.0
    }
}
