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

/** 展示游客与正式用户的身份状态及当前可完成的账户任务。 */
@Composable
fun TaskCenterScreen(
    accountInfo: AccountInfo?,
    onOpenAccountSettings: () -> Unit,
    onBack: () -> Unit
) {
    val isOfficialUser = !accountInfo?.email.isNullOrBlank()
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
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "当前身份",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            accountInfo == null -> "账户同步中…"
                            isBanned -> "Lv0 · 账户受限"
                            isOfficialUser -> "正式用户"
                            else -> "游客"
                        },
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isBanned) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        text = when {
                            accountInfo == null -> "正在读取账户信息。"
                            isBanned -> "当前账户已被封禁或注册异常，暂不可完成账户任务。"
                            isOfficialUser -> "你已绑定邮箱，是正式用户。"
                            else -> "当前未绑定邮箱，是游客用户。"
                        },
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (accountInfo != null && !isBanned && !isOfficialUser) {
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
                        Text(
                            text = "绑定邮箱和密码",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "完成绑定后成为正式用户，并获得一次性经验奖励。经验达到升级门槛后可提升等级。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
