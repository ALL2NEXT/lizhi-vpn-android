package io.github.HanJason233.chimera.data.model

/** 可排序的地区组查询方式；按卡片顺序执行，首个命中结果会停止后续查询。 */
enum class RegionLookupMethod(
    val title: String,
    val description: String
) {
    NODE_NAME(
        "节点名称匹配地区",
        "从节点名称中的地区名称、国家代码或国旗识别地区。"
    ),
    LOCAL_GEOIP(
        "本地 GeoIP 数据库",
        "解析节点服务器地址后，使用本机 geoip.dat 查询归属地区。"
    ),
    REMOTE_API(
        "远程 API",
        "解析节点服务器地址后，通过远程 IP 归属地 API 查询。"
    );

    companion object {
        /** 默认先识别节点名称，再尝试远程 IP 查询。 */
        val defaultRules = listOf(NODE_NAME, REMOTE_API)
    }
}
