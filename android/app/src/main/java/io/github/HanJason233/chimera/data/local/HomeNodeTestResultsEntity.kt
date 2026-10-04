package io.github.HanJason233.chimera.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按节点保存首页测试结果，避免订阅刷新替换节点记录或应用进程回收时丢失。
 * 链路类型与 IP 解锁结果用 JSON 保存，便于结果字段随检测项演进。
 */
@Entity(tableName = "home_node_test_results")
data class HomeNodeTestResultsEntity(
    @PrimaryKey val nodeId: String,
    val tcpingLatency: Int = -1,
    val urlTestLatency: Int = -1,
    val downloadMbps: Float = 0f,
    val linkTypeResultJson: String = "",
    val unlockTestResultJson: String = "",
    val updatedAt: Long = 0L
)
