package xyz.a202132.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.SpeedShareMode
import xyz.a202132.app.data.model.TelegramTaskStatus
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

/** 展示账户任务及其完成状态。 */
@Composable
fun TaskCenterScreen(
    viewModel: MainViewModel,
    onOpenAccountSettings: () -> Unit,
    onBack: () -> Unit
) {
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val taskStatus by viewModel.telegramTaskStatus.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingTelegramTask.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmittingTelegramTask.collectAsStateWithLifecycle()
    val speedShareMode by viewModel.speedShareMode.collectAsStateWithLifecycle()
    val speedShareStatus by viewModel.speedShareStatus.collectAsStateWithLifecycle()
    val speedShareMessage by viewModel.speedShareMessage.collectAsStateWithLifecycle()
    val taskMessage by viewModel.telegramTaskMessage.collectAsStateWithLifecycle()
    val account = accountInfo
    var telegramCode by remember(account?.uid) {
        mutableStateOf("")
    }
    var showTelegramCodeDialog by remember(account?.uid) {
        mutableStateOf(false)
    }
    val context = LocalContext.current
    val isBanned = account?.level == 0
    val isFormal = account?.email?.isNotBlank() == true
    val canSubmit = account != null &&
        !isBanned &&
        isFormal &&
        taskStatus?.status != "claimed" &&
        telegramCode.trim().isNotBlank() &&
        telegramCode.trim().length <= 128 &&
        !isSubmitting

    LaunchedEffect(account?.uid) {
        if (account != null) viewModel.loadTelegramTask()
    }
    LaunchedEffect(Unit) {
        viewModel.clearTelegramTaskMessage()
    }
    LaunchedEffect(taskStatus?.status) {
        if (taskStatus?.status == "claimed") {
            telegramCode = ""
            showTelegramCodeDialog = false
        }
    }

    fun openTelegramGroup() {
        val url = taskStatus?.groupUrl.orEmpty()
        if (url.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "无法打开 TG 群链接", Toast.LENGTH_SHORT).show()
        }
    }

    AppScreenScaffold(
        title = "任务中心",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.loadTelegramTask() },
                enabled = account != null && !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新任务状态"
                )
            }
        }
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
                    Text(
                        text = "智能测速结果共享",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "只上传节点标识和测速数值，不上传订阅链接。智能共享不额外测速，每个账户每个订阅组每小时最多一次，成功上报会获得经验。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (speedShareMode == SpeedShareMode.SMART) "已开启" else "已关闭",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = speedShareMode == SpeedShareMode.SMART,
                            enabled = isFormal && !isBanned,
                            onCheckedChange = { enabled ->
                                viewModel.setSpeedShareMode(
                                    if (enabled) SpeedShareMode.SMART else SpeedShareMode.OFF
                                )
                            }
                        )
                    }
                    Text(
                        text = when (speedShareMode) {
                            SpeedShareMode.OFF -> "当前未共享测速结果"
                            SpeedShareMode.SMART -> if (speedShareStatus?.smartReportedThisHour == true) {
                                "智能共享：本小时已上传 ${speedShareStatus?.smartReportedGroupCount ?: 0} 个订阅组结果，其他订阅组仍可上传"
                            } else {
                                "智能共享：下次连接前自动上传已有测速结果，成功奖励 ${speedShareStatus?.smartRewardExperience ?: 5} 经验"
                            }
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    speedShareMessage?.let { message ->
                        Text(
                            text = message,
                            color = if (message.contains("失败") || message.contains("无法") || message.contains("封禁")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontSize = 13.sp
                        )
                    }
                }
            }

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
                        text = when {
                            account == null -> "正在读取任务状态。"
                            !account.email.isNullOrBlank() -> "已完成绑定，任务奖励已发放。"
                            isBanned -> "Lv0 账户暂不可完成此任务。"
                            else -> "完成绑定后可获得一次性经验奖励。"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            account == null -> "同步中…"
                            !account.email.isNullOrBlank() -> "已完成"
                            isBanned -> "暂不可完成"
                            else -> "未完成"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !account?.email.isNullOrBlank() -> MaterialTheme.colorScheme.primary
                            isBanned -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (account != null && account.email.isBlank() && !isBanned) {
                        TaskActionRow {
                            Button(onClick = onOpenAccountSettings) {
                                Text("去绑定")
                            }
                        }
                    }
                }
            }

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
                        text = "加入 Telegram 群组",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "加入后台配置的 TG 群后，输入群内获取的口令，验证一致即可完成任务。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when {
                        account == null -> Text("账户同步中…")
                        isLoading && taskStatus == null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                                Text("正在读取任务状态…")
                            }
                        }
                        taskStatus?.enabled != true -> {
                            Text(
                                text = "后台暂未开放 TG 加群任务。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            TaskActionRow {
                                OutlinedButton(onClick = ::openTelegramGroup) {
                                    Text("打开 TG 群链接")
                                }
                                Button(
                                    onClick = {
                                        telegramCode = ""
                                        showTelegramCodeDialog = true
                                    },
                                    enabled = isFormal && !isBanned && taskStatus?.status != "claimed"
                                ) {
                                    Text("我已加入")
                                }
                            }
                            Text(
                                text = "任务奖励：${taskStatus?.rewardExperience ?: 0} 经验",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = telegramTaskStatusText(taskStatus),
                                fontWeight = FontWeight.Bold,
                                color = when (taskStatus?.status) {
                                    "claimed" -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    taskMessage?.let { message ->
                        Text(
                            text = message,
                            color = if (message.contains("失败") || message.contains("无法") || message.contains("封禁")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontSize = 13.sp
                        )
                    }
                }
            }

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
                        text = "服务器 / 节点贡献",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "任务内容：贡献可用的服务器或节点，或者助推 TG 群组。完成后请进入 TG 群组联系管理员，管理员确认贡献有效后，会在后台为你的账户发放自定义经验奖励。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TaskActionRow {
                        OutlinedButton(
                            onClick = ::openTelegramGroup,
                            enabled = taskStatus?.groupUrl?.isNotBlank() == true
                        ) {
                            Text("打开 TG 群组")
                        }
                    }
                    if (taskStatus?.groupUrl.isNullOrBlank()) {
                        Text(
                            text = "TG 群组尚未配置，请稍后再试。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (showTelegramCodeDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showTelegramCodeDialog = false },
            title = { Text("输入进群口令") },
            text = {
                OutlinedTextField(
                    value = telegramCode,
                    onValueChange = { telegramCode = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmitting,
                    singleLine = true,
                    label = { Text("进群口令") },
                    placeholder = { Text("输入群内获取的口令") }
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitTelegramTask(telegramCode) },
                    enabled = canSubmit
                ) {
                    Text(if (isSubmitting) "验证中…" else "验证口令")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTelegramCodeDialog = false },
                    enabled = !isSubmitting
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/** 统一任务卡片操作按钮的右对齐方式和按钮间距。 */
@Composable
private fun TaskActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private fun telegramTaskStatusText(status: TelegramTaskStatus?): String {
    return when (status?.status) {
        "claimed" -> "已完成，任务奖励已发放"
        else -> "未完成"
    }
}
