package xyz.a202132.app.data.model

/**
 * Per-core TUN implementation preference.
 *
 * HEV is the compatibility data plane. The remaining public options select
 * the core-owned native TUN stack. AUTO and NATIVE are retained only so
 * installations using the first global selector can be migrated safely.
 */
enum class TunImplementation(
    val displayName: String,
    val description: String,
    val nativeStack: TunStack? = null
) {
    HEV_SOCKS5(
        "HEV tun2socks",
        "HEV \u63a5\u7ba1 Android TUN\uff0c\u518d\u8f6c\u53d1\u5230\u6838\u5fc3 SOCKS5"
    ),
    GVISOR(
        "gVisor",
        "\u7531\u5185\u6838\u76f4\u63a5\u5904\u7406 TUN\uff0c\u517c\u5bb9\u6027\u4f18\u5148",
        TunStack.GVISOR
    ),
    SYSTEM(
        "system",
        "\u4f7f\u7528\u7cfb\u7edf\u7f51\u7edc\u6808\uff0c\u529f\u8017\u66f4\u4f4e",
        TunStack.SYSTEM
    ),
    MIXED(
        "mixed",
        "\u6839\u636e\u6d41\u91cf\u7c7b\u578b\u6df7\u5408\u5904\u7406",
        TunStack.MIXED
    ),

    /** Legacy global selector values; hidden from the new per-core UI. */
    @Deprecated("legacy setting") AUTO(
        "AUTO",
        "legacy"
    ),
    @Deprecated("legacy setting") NATIVE(
        "native",
        "legacy",
        TunStack.GVISOR
    );

    companion object {
        fun optionsFor(core: ProxyCore): List<TunImplementation> = when (core) {
            ProxyCore.XRAY -> listOf(HEV_SOCKS5, GVISOR)
            ProxyCore.SING_BOX -> listOf(HEV_SOCKS5, GVISOR, SYSTEM, MIXED)
            ProxyCore.MIHOMO -> listOf(HEV_SOCKS5, GVISOR, SYSTEM, MIXED)
        }

        fun defaultFor(@Suppress("UNUSED_PARAMETER") core: ProxyCore): TunImplementation =
            HEV_SOCKS5

        fun fromNativeStack(stack: TunStack): TunImplementation = when (stack) {
            TunStack.GVISOR -> GVISOR
            TunStack.SYSTEM -> SYSTEM
            TunStack.MIXED -> MIXED
        }
    }
}
