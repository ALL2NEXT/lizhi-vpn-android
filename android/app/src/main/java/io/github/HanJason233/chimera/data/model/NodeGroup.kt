package io.github.HanJason233.chimera.data.model

/**
 * 节点组的统一类型。地区组是动态虚拟组，其余类型可以由本地数据持久化。
 */
enum class NodeGroupType {
    SUBSCRIPTION,
    COUNTRY,
    IMPORTED
}

/**
 * 节点组的稳定标识，避免使用显示名称作为选中状态或数据库关联键。
 */
sealed interface NodeGroupKey {
    val value: String

    data class Subscription(val sourceId: String) : NodeGroupKey {
        override val value: String = "subscription:$sourceId"
    }

    data class Country(val countryCode: String) : NodeGroupKey {
        override val value: String = "country:$countryCode"
    }

    /** 用户创建的本地订阅组使用数据库中持久化的组 ID。 */
    data class Imported(val groupId: String) : NodeGroupKey {
        override val value: String = "imported:$groupId"
    }

    companion object {
        fun parse(value: String?): NodeGroupKey? {
            val normalized = value?.trim().orEmpty()
            return when {
                normalized.startsWith("subscription:") -> {
                    normalized.substringAfter("subscription:")
                        .takeIf(String::isNotBlank)
                        ?.let(::Subscription)
                }
                normalized.startsWith("country:") -> {
                    normalized.substringAfter("country:")
                        .takeIf(String::isNotBlank)
                        ?.let(::Country)
                }
                normalized.startsWith("imported:") -> {
                    normalized.substringAfter("imported:")
                        .takeIf(String::isNotBlank)
                        ?.let(::Imported)
                }
                else -> null
            }
        }
    }
}

/** 供统一分组列表使用的展示摘要。 */
data class NodeGroupSummary(
    val id: String,
    val name: String,
    val type: NodeGroupType,
    val nodeCount: Int,
    val isVirtual: Boolean = false,
    val subscriptionUrl: String? = null
)
