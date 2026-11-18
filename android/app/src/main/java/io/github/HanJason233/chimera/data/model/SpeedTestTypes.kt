package io.github.HanJason233.chimera.data.model

enum class ManualGroupTestStage {
    TCPING,
    URL_TEST,
    BANDWIDTH,
    LINK_TYPE,
    IP_QUALITY
}

/** How many nodes a speed-test stage should take from its upstream result. */
enum class SpeedTestSelectionMode {
    PERCENT,
    COUNT
}

fun formatSpeedTestSelection(mode: SpeedTestSelectionMode, value: Int): String = when (mode) {
    SpeedTestSelectionMode.PERCENT -> "$value%"
    SpeedTestSelectionMode.COUNT -> "$value 个"
}
