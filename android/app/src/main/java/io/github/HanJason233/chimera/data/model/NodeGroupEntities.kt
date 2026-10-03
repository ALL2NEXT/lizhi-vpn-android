package io.github.HanJason233.chimera.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/** 私人源的持久化记录；官方源来自订阅源，地区组由应用动态汇总。 */
@Entity(
    tableName = "node_groups",
    indices = [Index(value = ["type"])]
)
data class NodeGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = NodeGroupStorageType.IMPORTED.name,
    val subscriptionUrl: String? = null,
    val subscriptionUploadBytes: Long? = null,
    val subscriptionDownloadBytes: Long? = null,
    val subscriptionTotalBytes: Long? = null,
    val subscriptionExpireAt: Long? = null,
    val subscriptionUpdatedAt: Long = 0L,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "'AUTO_REMOTE'")
    val iconMode: String = PersonalSourceIconMode.AUTO_REMOTE.name,
    val customIconUrl: String? = null,
    val localIconFile: String? = null
)

/** 节点与用户组的多对多关系。 */
@Entity(
    tableName = "node_group_members",
    primaryKeys = ["groupId", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = NodeGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Node::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["nodeId"])]
)
data class NodeGroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val sortOrder: Int = 0
)
