package io.github.HanJason233.chimera.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.HanJason233.chimera.util.CountryCodeResolver

/**
 * 代理节点数据模型
 */
@Entity(tableName = "nodes")
@TypeConverters(NodeTypeConverter::class)
data class Node(
    @PrimaryKey
    val id: String,                       // 唯一标识 (MD5 of rawLink)
    val name: String,                     // 节点名称
    val type: NodeType,                   // 协议类型
    val server: String,                   // 服务器地址
    val port: Int,                        // 端口
    val rawLink: String,                  // 原始链接
    val country: String? = null,          // 国家代码 (如 "JP", "US")
    val countryName: String? = null,      // 国家名称 (如 "日本", "美国")
    val latency: Int = -1,                // 延迟(ms), -1表示未测试
    val tcpingLatency: Int = -1,          // TCPing延迟(ms), -1表示未测试
    val urlTestLatency: Int = -1,         // 204 URLTest延迟(ms), -1表示未测试
    val subscriptionSourceId: String? = null,
    val subscriptionSourceName: String? = null,
    val subscriptionUpdatedAt: Long = 0L, // 仓库源最近更新时间戳
    val isAvailable: Boolean = true,      // 是否可用
    val lastTestedAt: Long = 0,           // 上次测试时间戳
    val sortOrder: Int = 0,               // 排序顺序
    val downloadMbps: Float = 0f,         // 下载带宽(Mbps)
    val uploadMbps: Float = 0f,           // 上传带宽(Mbps)
    val autoTestStatus: String = "",      // 自动化测试状态
    val autoTestedAt: Long = 0,           // 自动化测试时间戳
    val source: NodeSource = NodeSource.SUBSCRIPTION
) {
    /**
     * 读取原始节点链接。
     */
    fun getRawLinkPlain(): String = rawLink

    /**
     * 获取节点地区代码；名称中的具体地区优先于旗帜，兼容“🇨🇳 中国台湾”等节点命名。
     */
    fun getCountryCode(): String? =
        CountryCodeResolver.fromNodeName(name)
            ?: CountryCodeResolver.fromNodeName(countryName)
            ?: CountryCodeResolver.normalizeCountryCode(country)
            ?: CountryCodeResolver.fromNodeName(country)
            ?: CountryCodeResolver.fromFlagEmoji(name)
            ?: CountryCodeResolver.fromFlagEmoji(countryName)

    /** 获取地区标记；台湾使用字母代码，无法识别时显示全球图标。 */
    fun getRegionMarker(): String {
        val code = getCountryCode() ?: return "🌐"
        return if (code == "TW") "TW" else CountryCodeResolver.flagEmoji(code) ?: "🌐"
    }
    
    /**
     * 获取显示名称（去除国旗emoji）
     */
    fun getDisplayName(): String {
        val result = StringBuilder()
        var i = 0
        while (i < name.length) {
            val codePoint = name.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            
            // 检查当前代码点是否为区域指示符（U+1F1E6 至 U+1F1FF）
            if (codePoint in 0x1F1E6..0x1F1FF) {
                // 跳过当前字符，如果下一个字符也是，则一并跳过。
                if (i + charCount < name.length) {
                    val nextCodePoint = name.codePointAt(i + charCount)
                    if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                        i += charCount + Character.charCount(nextCodePoint)
                        continue
                    }
                }
                i += charCount
                continue
            }

            result.append(String(Character.toChars(codePoint)))
            i += charCount
        }
        return result.toString().trim()
    }

    /** 获取连接信息中展示的订阅组名称。 */
    fun getConnectionGroupName(): String =
        subscriptionSourceName?.trim()?.takeIf(String::isNotEmpty)
            ?: subscriptionSourceId?.trim()?.takeIf(String::isNotEmpty)
            ?: "未分组订阅"
    
    /**
     * 获取延迟显示文本
     */
    fun getLatencyText(): String {
        return when {
            latency == -1 -> "未测试"
            latency == -2 -> "超时"
            !isAvailable -> "不可用"
            else -> "${latency}ms"
        }
    }
    
    /**
     * 获取延迟等级 (用于颜色显示)
     */
    fun getLatencyLevel(): LatencyLevel {
        return when {
            latency < 0 || !isAvailable -> LatencyLevel.BAD
            latency <= 200 -> LatencyLevel.GOOD
            latency <= 400 -> LatencyLevel.MEDIUM
            else -> LatencyLevel.BAD
        }
    }
}

enum class LatencyLevel {
    GOOD, MEDIUM, BAD
}

enum class NodeSource {
    SUBSCRIPTION,
    /** 用户创建的本地订阅组节点。 */
    LOCAL_GROUP
}

/** A distinct subscription source currently available in the local node database. */
data class SubscriptionGroup(
    val sourceId: String,
    val name: String,
    val updatedAt: Long = 0L
)

/**
 * 节点类型转换器
 */
class NodeTypeConverter {
    @TypeConverter
    fun fromNodeType(type: NodeType): String = type.protocol
    
    @TypeConverter
    fun toNodeType(protocol: String): NodeType = 
        NodeType.entries.find { it.protocol == protocol } ?: NodeType.UNKNOWN

    @TypeConverter
    fun fromNodeSource(source: NodeSource): String = source.name

    @TypeConverter
    fun toNodeSource(source: String): NodeSource =
        runCatching { NodeSource.valueOf(source) }.getOrDefault(NodeSource.SUBSCRIPTION)
}
