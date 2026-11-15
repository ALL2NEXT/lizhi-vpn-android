package io.github.HanJason233.chimera.network.nodetest.latency

import android.app.Application
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LatencyTesterProgressTest {
    @Test
    fun onlyActiveWorkersShowTestingAndResultsArriveBeforeBatchFinishes() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val nodes = nodes(server.localPort)
            val active = ConcurrentHashMap.newKeySet<String>()
            val results = ConcurrentHashMap<String, LatencyTester.LatencyResult>()
            val started = Channel<String>(Channel.UNLIMITED)
            val finished = Channel<String>(Channel.UNLIMITED)
            val gates = nodes.associate { it.id to CompletableDeferred<Unit>() }
            val batch = async {
                LatencyTester().testAllNodes(
                    nodes = nodes,
                    concurrency = 5,
                    onNodeTestingChanged = { id, testing ->
                        if (testing) {
                            active.add(id)
                            started.trySend(id)
                        } else {
                            active.remove(id)
                            finished.trySend(id)
                        }
                    },
                    onNodeResult = { result ->
                        gates.getValue(result.nodeId).await()
                        results[result.nodeId] = result
                    }
                )
            }
            try {
                withTimeout(15_000) {
                    val firstFive = (0 until 5).map { started.receive() }.toSet()
                    assertEquals(nodes.take(5).map { it.id }.toSet(), firstFive)
                    assertEquals(firstFive, active.toSet())
                    assertTrue(results.isEmpty())

                    // 先完成第三个节点，不能等前两个或整批结束才发布它的结果。
                    val completedId = nodes[2].id
                    gates.getValue(completedId).complete(Unit)
                    assertEquals(completedId, finished.receive())
                    val nextId = started.receive()
                    assertEquals(nodes[5].id, nextId)
                    assertEquals(firstFive - completedId + nextId, active.toSet())
                    assertTrue(results.getValue(completedId).isAvailable)
                    assertFalse(batch.isCompleted)

                    gates.values.forEach { it.complete(Unit) }
                    assertEquals(nodes.map { it.id }, batch.await().map { it.nodeId })
                    assertEquals(40, results.size)
                    assertTrue(active.isEmpty())
                }
            } finally {
                batch.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellationClearsActiveNodesAndDoesNotStartQueuedNodes() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val nodes = nodes(server.localPort)
            val active = ConcurrentHashMap.newKeySet<String>()
            val started = Channel<String>(Channel.UNLIMITED)
            val batch = async {
                LatencyTester().testAllNodes(
                    nodes = nodes,
                    concurrency = 5,
                    onNodeTestingChanged = { id, testing ->
                        if (testing) {
                            active.add(id)
                            started.trySend(id)
                        } else {
                            active.remove(id)
                        }
                    },
                    onNodeResult = { awaitCancellation() }
                )
            }
            try {
                withTimeout(15_000) {
                    repeat(5) { started.receive() }
                    batch.cancelAndJoin()
                    assertTrue(active.isEmpty())
                    assertTrue(started.tryReceive().isFailure)
                }
            } finally {
                batch.cancelAndJoin()
            }
        }
    }

    private fun nodes(port: Int) = (0 until 40).map { index ->
        Node(
            id = "node-$index",
            name = "节点 $index",
            type = NodeType.VMESS,
            server = InetAddress.getLoopbackAddress().hostAddress!!,
            port = port,
            rawLink = ""
        )
    }
}
