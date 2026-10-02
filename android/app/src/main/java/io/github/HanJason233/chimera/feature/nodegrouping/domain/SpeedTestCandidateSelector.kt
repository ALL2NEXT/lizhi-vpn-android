package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode

/** Selects the first part of an ordered list for one speed-test stage. */
internal object SpeedTestCandidateSelector {
    fun <T> takeTop(
        items: List<T>,
        mode: SpeedTestSelectionMode,
        value: Int
    ): List<T> {
        if (items.isEmpty()) return emptyList()

        val normalizedValue = value.coerceAtLeast(1)
        val count = when (mode) {
            SpeedTestSelectionMode.PERCENT ->
                ((items.size.toLong() * normalizedValue + 99L) / 100L)
                    .toInt()
                    .coerceIn(1, items.size)

            SpeedTestSelectionMode.COUNT -> normalizedValue.coerceAtMost(items.size)
        }
        return items.take(count)
    }
}
