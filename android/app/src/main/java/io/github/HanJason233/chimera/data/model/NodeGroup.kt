package io.github.HanJason233.chimera.data.model

/** 顶层分组只有仓库源和由仓库源按地区规则动态汇总的地区组。 */
enum class NodeGroupType {
    WAREHOUSE,
    REGION
}

/** 仓库源下的两种来源分类。 */
enum class WarehouseGroupType {
    OFFICIAL,
    PERSONAL
}

/** node_groups 表内部使用的记录标记，与面向用户的分组分类分开。 */
enum class NodeGroupStorageType {
    SUBSCRIPTION,
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

    /** 私人源继续使用既有 imported: 标识，保留本地选中状态兼容性。 */
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
    val warehouseType: WarehouseGroupType = WarehouseGroupType.PERSONAL,
    val nodeCount: Int,
    val isVirtual: Boolean = false,
    val subscriptionUrl: String? = null,
    val subscriptionUploadBytes: Long? = null,
    val subscriptionDownloadBytes: Long? = null,
    val subscriptionTotalBytes: Long? = null,
    val subscriptionExpireAt: Long? = null,
    val subscriptionUpdatedAt: Long? = null
)
