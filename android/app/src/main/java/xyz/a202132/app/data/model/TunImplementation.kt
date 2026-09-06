package xyz.a202132.app.data.model

/**
 * User preference for the Android TUN data plane.
 *
 * AUTO keeps backend policy in control. NATIVE lets sing-box/Xray own the
 * Android TUN directly, while HEV uses HEV tun2socks and feeds the selected
 * core through its local SOCKS5 inbound.
 */
enum class TunImplementation(
    val displayName: String,
    val description: String
) {
    AUTO("\u81ea\u52a8\uff08\u6838\u5fc3\u4f18\u5148\uff09", "\u7531\u5f53\u524d\u5185\u6838\u9009\u62e9\uff0c\u9ed8\u8ba4\u4f7f\u7528\u539f\u751f TUN"),
    NATIVE("\u539f\u751f TUN", "\u7531 sing-box/Xray \u76f4\u63a5\u5904\u7406 Android TUN"),
    HEV_SOCKS5("HEV tun2socks", "HEV \u63a5\u7ba1 TUN \u540e\u8f6c\u53d1\u5230\u6838\u5fc3 SOCKS5\uff0c\u517c\u5bb9\u6027\u66f4\u597d\u4f46\u591a\u4e00\u5c42\u8f6c\u53d1")
}
