package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.a202132.app.network.UnlockTestCategory
import xyz.a202132.app.network.UnlockTestResult
import xyz.a202132.app.network.UnlockTestState
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.UnlockTestUiState
import xyz.a202132.app.viewmodel.UnlockTestViewModel

/** 测试是独立辅助模块，不代表正常数据流必经的阶段。 */
@Composable
fun OutboundAfterScreen(
    onBack: () -> Unit,
    onOpenSpeedTestSchemes: () -> Unit
) {
    val testViewModel: UnlockTestViewModel = viewModel()
    val testState by testViewModel.uiState.collectAsStateWithLifecycle()

    AppScreenScaffold(title = "测试", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "提供独立检测工具；所有服务探测都通过当前已连接节点发出，不改变 VPN 路由配置。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StageSettingCard(
                title = "启动前节点选择/测速",
                description = "在连接启动前按测速方案选择节点，是连接流程的辅助功能。",
                actionLabel = "打开测速设置",
                onAction = onOpenSpeedTestSchemes
            )
            UnlockTestOverview(
                state = testState,
                onStart = testViewModel::startAllTests
            )

            UnlockTestCategory.values().forEach { category ->
                val categoryItems = testState.items.filter { it.definition.category == category }
                if (categoryItems.isNotEmpty()) {
                    Text(
                        text = category.title,
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    categoryItems.forEach { UnlockTestResultCard(it) }
                }
            }

            Text(
                text = "检测结果仅供参考。平台页面、风控策略和地区授权可能随时变化；“支持”不代表账号、订阅或全部内容一定可用。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnlockTestOverview(
    state: UnlockTestUiState,
    onStart: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "流媒体与 AI 解锁检测",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = state.message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.isRunning) {
                val progress = if (state.items.isEmpty()) 0f
                else state.completedCount.toFloat() / state.items.size.toFloat()
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${state.completedCount}/${state.items.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                onClick = onStart
            ) {
                Text(if (state.completedCount > 0) "重新检测全部" else "开始检测全部")
            }
        }
    }
}

@Composable
private fun UnlockTestResultCard(item: UnlockTestResult) {
    val stateColor = when (item.state) {
        UnlockTestState.UNLOCKED -> Color(0xFF2E7D32)
        UnlockTestState.PARTIAL, UnlockTestState.WARNING -> Color(0xFFED8B00)
        UnlockTestState.BLOCKED, UnlockTestState.ERROR -> MaterialTheme.colorScheme.error
        UnlockTestState.TESTING -> MaterialTheme.colorScheme.primary
        UnlockTestState.IDLE, UnlockTestState.NOT_CONNECTED -> MaterialTheme.colorScheme.outline
    }
    val symbol = when (item.state) {
        UnlockTestState.UNLOCKED -> "✓"
        UnlockTestState.PARTIAL, UnlockTestState.WARNING -> "!"
        UnlockTestState.BLOCKED, UnlockTestState.ERROR -> "×"
        UnlockTestState.IDLE, UnlockTestState.NOT_CONNECTED -> "—"
        UnlockTestState.TESTING -> ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = stateColor.copy(alpha = 0.14f),
                contentColor = stateColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (item.state == UnlockTestState.TESTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = stateColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.definition.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            append(item.statusText)
                            item.delayMs?.let { append(" · ").append(it).append(" ms") }
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = stateColor
                    )
                }
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StageSettingCard(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAction
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
