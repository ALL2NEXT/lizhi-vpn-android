package xyz.a202132.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    val taskMessage by viewModel.telegramTaskMessage.collectAsStateWithLifecycle()
    val account = accountInfo
    var telegramNickname by remember(account?.uid, taskStatus?.telegramNickname) {
        mutableStateOf(taskStatus?.telegramNickname.orEmpty())
    }
    val context = LocalContext.current
    val isBanned = account?.level == 0
    // 待审核和永久拒绝状态都不能修改昵称；驳回状态允许重新编辑并提交。
    val isApproved = taskStatus?.status == "approved"
    val isPending = taskStatus?.status == "pending"
    val isRejected = taskStatus?.status == "rejected"
    val canSubmit = account != null &&
        !isBanned &&
        !isApproved &&
        !isPending &&
        !isRejected &&
        telegramNickname.trim().isNotBlank() &&
        telegramNickname.trim().length <= 64 &&
        !isSubmitting

    LaunchedEffect(account?.uid) {
        if (account != null) viewModel.loadTelegramTask()
    }
    LaunchedEffect(Unit) {
        viewModel.clearTelegramTaskMessage()
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
                        Button(
                            onClick = onOpenAccountSettings,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("去绑定")
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
                        text = "加入 Telegram 群",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "加入后台配置的 TG 群后，提交你的 TG 昵称，管理员核实后完成任务。",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = ::openTelegramGroup) {
                                    Text("打开 TG 群链接")
                                }
                                TextButton(onClick = viewModel::loadTelegramTask) {
                                    Text("刷新状态")
                                }
                            }
                            Text(
                                text = "任务奖励：${taskStatus?.rewardExperience ?: 0} 经验",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = telegramNickname,
                                onValueChange = { telegramNickname = it.take(64) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isBanned && !isApproved && !isPending &&
                                    !isRejected && !isSubmitting,
                                singleLine = true,
                                label = { Text("TG 昵称") },
                                placeholder = { Text("例如：@yourname") }
                            )
                            Text(
                                text = telegramTaskStatusText(taskStatus),
                                fontWeight = FontWeight.Bold,
                                color = when (taskStatus?.status) {
                                    "approved" -> MaterialTheme.colorScheme.primary
                                    "returned" -> MaterialTheme.colorScheme.tertiary
                                    "rejected" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (!isApproved && !isPending && !isRejected) {
                                Button(
                                    onClick = { viewModel.submitTelegramTask(telegramNickname) },
                                    enabled = canSubmit,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(if (isSubmitting) "提交中…" else "提交审核")
                                }
                            }
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
        }
    }
}

private fun telegramTaskStatusText(status: TelegramTaskStatus?): String {
    return when (status?.status) {
        "approved" -> "已通过，任务奖励已发放"
        "pending" -> "已提交，等待后台人工审核"
        "returned" -> "已驳回，请修改 TG 昵称后重新提交"
        "rejected" -> "已拒绝，无法再次提交"
        else -> "未提交"
    }
}
