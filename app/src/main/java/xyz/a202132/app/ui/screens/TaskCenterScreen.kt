package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.ui.components.AppScreenScaffold

/** 展示账户任务及其完成状态。 */
@Composable
fun TaskCenterScreen(
    accountInfo: AccountInfo?,
    onOpenAccountSettings: () -> Unit,
    onBack: () -> Unit
) {
    val isTaskCompleted = !accountInfo?.email.isNullOrBlank()
    val isBanned = accountInfo?.level == 0

    AppScreenScaffold(
        title = "任务中心",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(
                            text = "绑定邮箱和密码",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                accountInfo == null -> "正在读取任务状态。"
                                isTaskCompleted -> "已完成绑定，任务奖励已发放。"
                                isBanned -> "Lv0 账户暂不可完成此任务。"
                                else -> "完成绑定后可获得一次性经验奖励。经验达到升级门槛后可提升等级。"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = when {
                            accountInfo == null -> "同步中…"
                            isTaskCompleted -> "已完成"
                            isBanned -> "暂不可完成"
                            else -> "未完成"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isTaskCompleted -> MaterialTheme.colorScheme.primary
                            isBanned -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (accountInfo != null && !isTaskCompleted && !isBanned) {
                        Button(
                            onClick = onOpenAccountSettings,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.End)
                        ) {
                            Text("去绑定")
                        }
                    }
                }
            }
        }
    }
}
