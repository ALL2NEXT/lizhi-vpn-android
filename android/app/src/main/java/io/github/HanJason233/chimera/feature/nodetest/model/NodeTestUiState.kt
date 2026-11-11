package io.github.HanJason233.chimera.feature.nodetest.model

import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestResult
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestState

/** 保存解锁检测结果及当前运行状态，供首页测试卡片展示。 */
internal data class UnlockTestUiState(
    val items: List<UnlockTestResult> = emptyList(),
    val isRunning: Boolean = false,
    val message: String = ""
) {
    val completedCount: Int
        get() = items.count { it.state !in setOf(UnlockTestState.IDLE, UnlockTestState.TESTING) }
}

/** 描述测速流程当前阶段和完成进度。 */
internal data class SpeedTestProgress(
    val title: String,
    val stage: String,
    val completed: Int = 0,
    val total: Int = 0,
    val isManualGroupTest: Boolean = false
)
