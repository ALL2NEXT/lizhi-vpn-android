package io.github.HanJason233.chimera.data.model

/** 测试分类和执行顺序共用同一份定义，界面分组不改变执行顺序。 */
enum class SpeedTestCategory(val title: String) {
    LINK_QUALITY("链路质量测试"),
    LINK_TYPE("链路类型测试"),
    IP_QUALITY("IP 质量测试")
}

enum class SpeedTestStage(val number: Int, val title: String, val category: SpeedTestCategory) {
    TCPING(1, "TCPing（可达性）", SpeedTestCategory.LINK_QUALITY),
    URL_TEST(2, "204 URL（连通性）", SpeedTestCategory.LINK_QUALITY),
    BANDWIDTH(3, "带宽测速", SpeedTestCategory.LINK_QUALITY),
    LINK_TYPE(4, "链路类型检测", SpeedTestCategory.LINK_TYPE),
    IP_QUALITY(5, "流媒体与 AI 解锁检测", SpeedTestCategory.IP_QUALITY);

    val shortTitle: String
        get() = when (this) {
            TCPING -> "TCPing"
            URL_TEST -> "204 URL"
            BANDWIDTH -> "带宽"
            LINK_TYPE -> "链路类型"
            IP_QUALITY -> "IP 质量"
        }
}

/** 每项测试的输入范围由执行顺序推导，避免保存可互相矛盾的阶段作用域配置。 */
sealed interface SpeedTestStageScope {
    data object CurrentGroup : SpeedTestStageScope
    data class PreviousResult(val stage: SpeedTestStage) : SpeedTestStageScope
    data object ConnectedNode : SpeedTestStageScope
}

data class SpeedTestStageSelection(val mode: SpeedTestSelectionMode, val value: Int)

fun SpeedTestSettings.isStageEnabled(stage: SpeedTestStage): Boolean = when (stage) {
    SpeedTestStage.TCPING -> tcpingEnabled
    SpeedTestStage.URL_TEST -> urlTestEnabled
    SpeedTestStage.BANDWIDTH -> bandwidthEnabled
    SpeedTestStage.LINK_TYPE -> linkTypeTestEnabled
    SpeedTestStage.IP_QUALITY -> unlockTestsEnabled
}

val SpeedTestSettings.enabledQualityStages: List<SpeedTestStage>
    get() = SpeedTestStage.entries.filter {
        it.category == SpeedTestCategory.LINK_QUALITY && isStageEnabled(it)
    }

/** 跳过关闭的质量测试，承接最近一项已开启的测试；第四、第五项始终使用连接节点。 */
fun SpeedTestSettings.inputScopeFor(stage: SpeedTestStage): SpeedTestStageScope {
    if (stage.category != SpeedTestCategory.LINK_QUALITY) return SpeedTestStageScope.ConnectedNode
    val previousStage = enabledQualityStages.lastOrNull { it.number < stage.number }
    return previousStage?.let { SpeedTestStageScope.PreviousResult(it) }
        ?: SpeedTestStageScope.CurrentGroup
}

fun SpeedTestSettings.selectionFor(stage: SpeedTestStage): SpeedTestStageSelection = when (stage) {
    SpeedTestStage.TCPING -> SpeedTestStageSelection(tcpingSelectionMode, tcpingSelectionValue)
    SpeedTestStage.URL_TEST -> SpeedTestStageSelection(urlTestSelectionMode, urlTestSelectionValue)
    SpeedTestStage.BANDWIDTH -> SpeedTestStageSelection(bandwidthSelectionMode, bandwidthSelectionValue)
    else -> error("连接后的测试不筛选节点")
}

fun SpeedTestSettings.inputScopeLabel(stage: SpeedTestStage): String = when (val input = inputScopeFor(stage)) {
    SpeedTestStageScope.CurrentGroup -> "当前组"
    is SpeedTestStageScope.PreviousResult -> "${input.stage.number}. ${input.stage.shortTitle} 的可用结果"
    SpeedTestStageScope.ConnectedNode -> "连接后的当前节点（1 个）"
}
