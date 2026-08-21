package xyz.a202132.app.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Ordered parallel map backed by a fixed number of coroutine workers.
 * Unlike `items.map { async { ... } }`, memory use stays bounded when a
 * subscription contains thousands of nodes.
 */
internal suspend fun <T, R> parallelMapOrdered(
    items: List<T>,
    maxConcurrency: Int,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    if (items.isEmpty()) return@coroutineScope emptyList()

    val nextIndex = AtomicInteger(0)
    val results = arrayOfNulls<Any?>(items.size)
    val workerCount = maxConcurrency.coerceIn(1, items.size)

    List(workerCount) {
        async {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= items.size) break
                results[index] = transform(items[index])
            }
        }
    }.awaitAll()

    @Suppress("UNCHECKED_CAST")
    results.map { it as R }
}
