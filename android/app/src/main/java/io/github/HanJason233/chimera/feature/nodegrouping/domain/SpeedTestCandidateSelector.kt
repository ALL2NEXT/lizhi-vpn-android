package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.SpeedTestStageScope
import io.github.HanJason233.chimera.data.model.inputScopeFor
import io.github.HanJason233.chimera.data.model.isStageEnabled
import io.github.HanJason233.chimera.data.model.selectionFor

/** Resolves stage inputs and retains the best ordered results for later stages. */
internal object SpeedTestCandidateSelector {
    /** 前项结果已经按本项测试结果筛选并排序；当前阶段不在测试前截断候选节点。 */
    fun <T> candidatesForStage(
        settings: SpeedTestSettings,
        stage: SpeedTestStage,
        currentGroupCandidates: List<T>,
        previousResults: Map<SpeedTestStage, List<T>>
    ): List<T> {
        require(settings.isStageEnabled(stage)) { "测试阶段未开启：$stage" }
        val candidates = when (val scope = settings.inputScopeFor(stage)) {
            SpeedTestStageScope.CurrentGroup -> currentGroupCandidates
            is SpeedTestStageScope.PreviousResult -> checkNotNull(previousResults[scope.stage]) {
                "前项测试尚未完成：${scope.stage}"
            }
            SpeedTestStageScope.ConnectedNode -> error("当前测速方案不能用单个连接节点作为批量筛选输入")
        }
        return candidates
    }

    fun <T> retainResultsForStage(
        settings: SpeedTestSettings,
        stage: SpeedTestStage,
        orderedResults: List<T>
    ): List<T> {
        require(settings.isStageEnabled(stage)) { "测试阶段未开启：$stage" }
        val selection = settings.selectionFor(stage)
        return takeTop(orderedResults, selection.mode, selection.value)
    }

    fun concurrencyForStage(
        settings: SpeedTestSettings,
        stage: SpeedTestStage,
        testedCount: Int
    ): Int {
        val (mode, value) = when (stage) {
            SpeedTestStage.TCPING -> settings.tcpingConcurrencyMode to settings.tcpingConcurrency
            SpeedTestStage.URL_TEST -> settings.urlTestConcurrencyMode to settings.urlTestConcurrency
            SpeedTestStage.BANDWIDTH -> settings.bandwidthTestConcurrencyMode to settings.bandwidthTestConcurrency
            else -> error("连接后的测试没有节点并发数量")
        }
        val normalizedValue = value.coerceAtLeast(1)
        return when (mode) {
            SpeedTestSelectionMode.PERCENT ->
                ((testedCount.coerceAtLeast(1).toLong() * normalizedValue + 99L) / 100L)
                    .toInt()
                    .coerceIn(1, testedCount.coerceAtLeast(1))

            SpeedTestSelectionMode.COUNT -> normalizedValue
        }
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
