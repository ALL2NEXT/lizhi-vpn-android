package xyz.a202132.app.data.model

/**
 * sing-box TUN userspace/kernel network stack.
 */
enum class TunStack(
    val configValue: String,
    val displayName: String,
    val description: String
) {
    GVISOR("gvisor", "gvisor（兼容）", "兼容性最佳，默认选项"),
    SYSTEM("system", "system（省电）", "使用系统网络栈，功耗更低"),
    MIXED("mixed", "mixed（混合）", "按流量类型混合处理")
}
