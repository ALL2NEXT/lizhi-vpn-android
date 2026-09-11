package xyz.a202132.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户创建的持久化节点组；地区组和收藏组仍由应用提供虚拟入口。 */
@Entity(
    tableName = "node_groups",
    indices = [Index(value = ["type"])]
)
data class NodeGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = NodeGroupType.IMPORTED.name,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L
)

/** 节点与用户组的多对多关系，允许一个节点同时属于收藏组和导入组。 */
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
