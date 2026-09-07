package xyz.a202132.app.data.model

/** URL 延迟的统计口径。 */
enum class UrlTestMode(val displayName: String) {
    /** 包含代理协议、目标 TCP、TLS 和首包时间。 */
    COLD("冷连接"),

    /** 首次请求仅预热连接，统计第二次请求到首包的时间。 */
    WARM("暖连接");

    companion object {
        fun fromStored(value: String?): UrlTestMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: COLD
    }
}
