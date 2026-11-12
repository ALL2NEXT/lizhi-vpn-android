package io.github.HanJason233.chimera.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.security.detection.NativeSecurityDetector
import io.github.HanJason233.chimera.security.detection.ScreenshotCaptureDetector
import io.github.HanJason233.chimera.security.detection.SecurityCheckItem
import io.github.HanJason233.chimera.security.detection.SecurityCheckOptions
import io.github.HanJason233.chimera.security.detection.SecurityCheckStatus
import io.github.HanJason233.chimera.security.detection.buildSecuritySelfCheckReport
import io.github.HanJason233.chimera.security.detection.securityEventDescription
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.SecuritySelfCheckViewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SecuritySelfCheckScreen(
    onBack: () -> Unit,
    viewModel: SecuritySelfCheckViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screenshotStatus by ScreenshotCaptureDetector.status.collectAsStateWithLifecycle()
    val findings by NativeSecurityDetector.findings.collectAsStateWithLifecycle()
    val report = remember(state.inspection, screenshotStatus) {
        state.inspection?.let {
            buildSecuritySelfCheckReport(it, SecurityCheckOptions.current(), screenshotStatus)
        }
    }
    val events = remember(findings) { findings.mapNotNull { securityEventDescription(it.code) } }
    val activity = LocalContext.current.securityCheckActivity()
    val screenshotsBlocked = activity?.window?.attributes?.flags?.let {
        it and WindowManager.LayoutParams.FLAG_SECURE != 0
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }

    AppScreenScaffold(
        title = "安全自检",
        onBack = onBack,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("security-self-check"),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("summary") {
                val summaryColor = when {
                    report == null -> MaterialTheme.colorScheme.primary
                    report.riskCount > 0 -> MaterialTheme.colorScheme.error
                    report.noticeCount > 0 || report.unavailableCount > 0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = summaryColor.copy(alpha = 0.08f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = summaryColor,
                            modifier = Modifier.size(32.dp))
                        Text(
                            if (state.checking) "正在检查设备环境…" else report?.title ?: "检查当前设备环境",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (report == null) "正在读取本机环境，不会发送外部网络测试请求。"
                            else "已完成 " + report.completedCount + " 项环境检查 · " +
                                (report.riskCount + report.noticeCount) + " 项需关注 · " +
                                report.unavailableCount + " 项无法检测",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.inspection?.let { inspection ->
                            Text(
                                "检查时间：" + DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.MEDIUM
                                ).format(Date(inspection.inspectedAtMillis)) +
                                    " · 耗时 " + inspection.durationMillis + " ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = viewModel::refresh,
                            enabled = !state.checking,
                            modifier = Modifier.fillMaxWidth().testTag("security-self-check-run")
                        ) {
                            if (state.checking) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                if (state.checking) "检测中…" else "重新检测",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            item("protection") {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("当前防护", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        ProtectionStateRow("业务请求保护", BuildConfig.SECURITY_BLOCK_HTTP_PROXY)
                        ProtectionStateRow("当前窗口截图保护", screenshotsBlocked)
                        Text(
                            "请求保护会绕过系统代理，并拒绝证书验证失败或未加密的业务请求。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (BuildConfig.SECURITY_CRASH_ON_FINDING) {
                            Text(
                                "黑名单、USB 调试和开发者选项仅上报；其他环境风险及抓包相关命中会立即退出应用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (report != null) {
                item("environment-heading") { SectionLabel("环境检查") }
                items(report.items, key = { it.id }) { item -> SecurityCheckResultCard(item) }
            }
            item("events-heading") { SectionLabel("本次运行的安全事件") }
            item("events") {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (events.isEmpty()) {
                            Text("暂未记录安全事件", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            events.forEach { (title, description) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    Text(description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(
                            "这里展示此前记录的事件，与本次环境检查结果分开显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item("scope") {
                Text(
                    "自检仅反映已启用、已完成的检查。未发现异常信号不能保证设备绝对安全。",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(title, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ProtectionStateRow(title: String, enabled: Boolean?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            when (enabled) { true -> "已开启"; false -> "未开启"; null -> "无法读取" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SecurityCheckResultCard(item: SecurityCheckItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val color = securityStatusColor(item.status)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).testTag("security-check-" + item.id),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    when (item.status) {
                        SecurityCheckStatus.PASS -> Icons.Outlined.CheckCircle
                        SecurityCheckStatus.RISK, SecurityCheckStatus.NOTICE -> Icons.Outlined.Warning
                        else -> Icons.AutoMirrored.Outlined.HelpOutline
                    }, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)
                )
                Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.1f)) {
                    Text(item.status.label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
            Text(item.summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.details.isNotEmpty()) {
                if (expanded) {
                    item.details.forEach { detail ->
                        Text("• " + detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (expanded) "收起详情" else "查看详情（" + item.details.size + "）")
                }
            }
        }
    }
}

@Composable
private fun securityStatusColor(status: SecurityCheckStatus): Color = when (status) {
    SecurityCheckStatus.RISK -> MaterialTheme.colorScheme.error
    SecurityCheckStatus.NOTICE, SecurityCheckStatus.UNAVAILABLE -> MaterialTheme.colorScheme.tertiary
    SecurityCheckStatus.PASS, SecurityCheckStatus.MONITORING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private tailrec fun Context.securityCheckActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.securityCheckActivity() else null
    else -> null
}
