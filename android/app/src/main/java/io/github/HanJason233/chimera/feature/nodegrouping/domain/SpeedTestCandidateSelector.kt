package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.SpeedTestStageScope
import io.github.HanJason233.chimera.data.model.inputScopeFor
import io.github.HanJason233.chimera.data.model.isStageEnabled
import io.github.HanJason233.chimera.data.model.selectionFor

/** Selects the first part of an ordered list for one speed-test stage. */
internal object SpeedTestCandidateSelector {
    /** 与编辑页使用相同的阶段作用域；前项结果必须已过滤并按质量排序。 */
    fun <T> selectForStage(
        scheme: SpeedTestScheme,
        stage: SpeedTestStage,
        initialCandidates: List<T>,
        previousResults: Map<SpeedTestStage, List<T>>
    ): List<T> {
        require(scheme.isStageEnabled(stage)) { "测试阶段未开启：$stage" }
        val candidates = when (val scope = scheme.inputScopeFor(stage)) {
            is SpeedTestStageScope.Initial -> initialCandidates
            is SpeedTestStageScope.PreviousResult -> checkNotNull(previousResults[scope.stage]) {
                "前项测试尚未完成：${scope.stage}"
            }
            SpeedTestStageScope.ConnectedNode -> error("连接后的测试不参与节点筛选")
        }
        val selection = scheme.selectionFor(stage)
        return takeTop(candidates, selection.mode, selection.value)
    }

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
