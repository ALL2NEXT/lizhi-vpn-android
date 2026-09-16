package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.ui.components.AppScreenScaffold

/** 测试是独立辅助模块，不代表正常数据流必经的阶段。 */
@Composable
fun OutboundAfterScreen(
    onBack: () -> Unit,
    onOpenSpeedTestSchemes: () -> Unit
) {
    AppScreenScaffold(title = "测试", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "提供独立的测试工具，不属于正常数据流的必经阶段。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StageSettingCard(
                title = "启动前节点选择/测速",
                description = "在连接启动前按测速方案选择节点，是连接启动流程的辅助功能，不属于 Android VPN/TUN 或代理核心的数据路径。",
                actionLabel = "打开测速设置",
                onAction = onOpenSpeedTestSchemes
            )
            StageSettingCard(
                title = "检测工具（后续扩展）",
                description = "后续可在此加入 TCPing、URL Test、出口 IP、DNS 泄漏、IPv6 检测、流媒体解锁、送中测试等独立检测。",
                actionLabel = null,
                onAction = {}
            )
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
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
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
