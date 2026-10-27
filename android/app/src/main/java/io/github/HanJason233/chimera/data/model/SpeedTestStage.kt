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

/** 跳过关闭的测试项，后续阶段承接最近一项已开启测试筛选后的线路。 */
fun SpeedTestSettings.inputScopeFor(stage: SpeedTestStage): SpeedTestStageScope {
    val previousStage = SpeedTestStage.entries
        .asSequence()
        .filter { it.number < stage.number && isStageEnabled(it) }
        .filterNot { it == SpeedTestStage.IP_QUALITY && unlockTestIds.isEmpty() }
        .maxByOrNull { it.number }
    return previousStage?.let { SpeedTestStageScope.PreviousResult(it) }
        ?: SpeedTestStageScope.CurrentGroup
}

fun SpeedTestSettings.selectionFor(stage: SpeedTestStage): SpeedTestStageSelection = when (stage) {
    SpeedTestStage.TCPING -> SpeedTestStageSelection(tcpingSelectionMode, tcpingSelectionValue)
    SpeedTestStage.URL_TEST -> SpeedTestStageSelection(urlTestSelectionMode, urlTestSelectionValue)
    SpeedTestStage.BANDWIDTH -> SpeedTestStageSelection(bandwidthSelectionMode, bandwidthSelectionValue)
    else -> error("线路筛选阶段使用检测条件，不使用前 N 个保留数")
}

fun SpeedTestSettings.inputScopeLabel(stage: SpeedTestStage): String = when (val input = inputScopeFor(stage)) {
    SpeedTestStageScope.CurrentGroup -> "当前组"
    is SpeedTestStageScope.PreviousResult -> "${input.stage.number}. ${input.stage.shortTitle} 的可用结果"
    SpeedTestStageScope.ConnectedNode -> "连接后的当前节点（1 个）"
}
