package io.github.HanJason233.chimera.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.network.download.DownloadStatus
import io.github.HanJason233.chimera.ui.dialogs.DownloadDialog
import io.github.HanJason233.chimera.ui.dialogs.UpdateDialog
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun UpdateFlowHost(
    viewModel: MainViewModel,
    websiteUrl: String
) {
    val context = LocalContext.current
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showWebsiteFallback by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    if (updateInfo != null) {
        UpdateDialog(
            version = updateInfo?.version ?: "",
            changelog = updateInfo?.changelog ?: "",
            isForce = updateInfo?.isForce == 1,
            onUpdate = viewModel::openDownloadUrl,
            onSkip = viewModel::skipUpdate,
            onDismiss = viewModel::dismissUpdate
        )
    }

    // 下载完成后继续安装；同一状态只会在进入页面时触发一次。
    LaunchedEffect(downloadState.status) {
        if (downloadState.status == DownloadStatus.COMPLETED) {
            viewModel.installApk()
        }
    }

    if (downloadState.status == DownloadStatus.DOWNLOADING ||
        downloadState.status == DownloadStatus.PAUSED ||
        downloadState.status == DownloadStatus.ERROR
    ) {
        DownloadDialog(
            progress = downloadState.progress,
            speed = downloadState.speed,
            status = downloadState.status,
            errorMessage = downloadState.error,
            onPause = viewModel::pauseDownload,
            onResume = viewModel::resumeDownload,
            onCancel = viewModel::cancelDownload,
            onRetry = viewModel::retryDownload
        )
    }

    LaunchedEffect(downloadState.consecutiveFailures, websiteUrl) {
        if (downloadState.consecutiveFailures >= 3 && websiteUrl.isNotBlank()) {
            showWebsiteFallback = true
        }
    }

    if (showWebsiteFallback) {
        Dialog(onDismissRequest = { showWebsiteFallback = false }) {
            AppDialogSurface(
                m3Shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "下载失败",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "APP已连续${downloadState.consecutiveFailures}次下载失败，是否选择从官网下载？",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showWebsiteFallback = false },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("继续重试")
                        }
                        Button(
                            onClick = {
                                showWebsiteFallback = false
                                viewModel.cancelDownload()
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("去官网")
                        }
                    }
                }
            }
        }
    }
}
