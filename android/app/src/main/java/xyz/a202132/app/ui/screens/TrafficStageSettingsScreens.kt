package xyz.a202132.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** 入站前设置只承载核心入站前已经生效的功能入口。 */
@Composable
fun InboundBeforeScreen(
    onBack: () -> Unit,
    onOpenProxyScope: () -> Unit,
    onOpenSpeedTestSchemes: () -> Unit
) {
    AppScreenScaffold(title = "入站前", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "这里的设置发生在核心入站之前。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StageSettingCard(
                title = "分应用代理",
                description = "在 Android VPN/TUN 接入前筛选应用，决定哪些应用流量交给本地数据面。",
                actionLabel = "打开分应用代理",
                onAction = onOpenProxyScope
            )
            StageSettingCard(
                title = "测试",
                description = "配置主页测速方案及其触发方式，在建立核心入站前选择测速节点。",
                actionLabel = "打开测试设置",
                onAction = onOpenSpeedTestSchemes
            )
        }
    }
}

/** 出站后暂时只提供位置占位，不提前引入尚未定义的处理逻辑。 */
@Composable
fun OutboundAfterScreen(onBack: () -> Unit) {
    AppScreenScaffold(title = "出站后", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StageSettingCard(
                title = "暂无设置",
                description = "出站后处理功能暂未接入，后续可在这里增加核心出站完成后的处理。",
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
