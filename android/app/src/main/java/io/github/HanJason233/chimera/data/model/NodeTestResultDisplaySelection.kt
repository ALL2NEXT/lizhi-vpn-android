package io.github.HanJason233.chimera.data.model

/** 节点列表当前展示的测试项，以及本轮测试结果的起始时间。 */
data class NodeTestResultDisplaySelection(
    val stage: ManualGroupTestStage = ManualGroupTestStage.TCPING,
    val runStartedAt: Long = 0L
)
