package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.SpeedTestStageScope
import io.github.HanJason233.chimera.data.model.inputScopeFor
import io.github.HanJason233.chimera.data.model.isStageEnabled
import io.github.HanJason233.chimera.data.model.selectionFor

/** Resolves stage inputs and retains the best ordered results for later stages. */
internal object SpeedTestCandidateSelector {
    /** 前项结果已经按本项测试结果筛选并排序；当前阶段不在测试前截断候选节点。 */
    fun <T> candidatesForStage(
        scheme: SpeedTestScheme,
        stage: SpeedTestStage,
        currentGroupCandidates: List<T>,
        previousResults: Map<SpeedTestStage, List<T>>
    ): List<T> {
        require(scheme.isStageEnabled(stage)) { "测试阶段未开启：$stage" }
        val candidates = when (val scope = scheme.inputScopeFor(stage)) {
            SpeedTestStageScope.CurrentGroup -> currentGroupCandidates
            is SpeedTestStageScope.PreviousResult -> checkNotNull(previousResults[scope.stage]) {
                "前项测试尚未完成：${scope.stage}"
            }
            SpeedTestStageScope.ConnectedNode -> error("连接后的测试不参与节点筛选")
        }
        return candidates
    }

    fun <T> retainResultsForStage(
        scheme: SpeedTestScheme,
        stage: SpeedTestStage,
        orderedResults: List<T>
    ): List<T> {
        require(scheme.isStageEnabled(stage)) { "测试阶段未开启：$stage" }
        val selection = scheme.selectionFor(stage)
        return takeTop(orderedResults, selection.mode, selection.value)
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
