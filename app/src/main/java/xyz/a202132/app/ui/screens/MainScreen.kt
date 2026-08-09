package xyz.a202132.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.*
import xyz.a202132.app.ui.dialogs.*
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.viewmodel.MainViewModel

private val WEB_SPEED_TESTS = listOf(
    "中科大测速（墙内测速）" to AppConfig.USTC_SPEED_TEST_URL,
    "南大测速（墙内测速）" to AppConfig.NJU_SPEED_TEST_URL,
    "奈飞测速（墙外测速）" to AppConfig.NETFLIX_SPEED_TEST_URL,
    "Google测速（墙外测速）" to AppConfig.GOOGLE_SPEED_TEST_URL
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenProxyScope: () -> Unit = {},
    onOpenNodeList: () -> Unit = {},
    onOpenOtherConfig: () -> Unit = {},
    onOpenFeatures: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerAnimationSpec = remember {
        tween<Float>(durationMillis = 250, easing = FastOutSlowInEasing)
    }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch {
            drawerState.animateTo(DrawerValue.Closed, drawerAnimationSpec)
            action()
        }
    }
    
    // Collect状态
    val currentNode by viewModel.currentNode.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val testingLabel by viewModel.testingLabel.collectAsStateWithLifecycle()
    val smartConnectEnabled by viewModel.smartConnectEnabled.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val infoDialogMessage by viewModel.infoDialogMessage.collectAsStateWithLifecycle()
    val isUserAgreementAccepted by viewModel.isUserAgreementAccepted.collectAsStateWithLifecycle()
    
    // 自动化测试设置/状态
    // val showBackupFailedDialog by viewModel.showBackupFailedDialog.collectAsState() // Removed
    
    // 显示错误Toast
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }
    
    // 移动网络提醒
    val cellularWarning by xyz.a202132.app.service.ServiceManager.cellularWarning.collectAsStateWithLifecycle()
    LaunchedEffect(cellularWarning) {
        cellularWarning?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            xyz.a202132.app.service.ServiceManager.clearCellularWarning()
        }
    }

    // Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                DrawerContent(
                    onCheckUpdate = { closeDrawerThen { viewModel.checkUpdate() } },
                    onOpenProxyScope = { closeDrawerThen(onOpenProxyScope) },
                    onOpenOtherConfig = { closeDrawerThen(onOpenOtherConfig) },
                    onOpenFeatures = { closeDrawerThen(onOpenFeatures) },
                    onClose = {
                        scope.launch {
                            drawerState.animateTo(DrawerValue.Closed, drawerAnimationSpec)
                        }
                    },
                    websiteUrl = backendSettings.websiteUrl,
                    feedbackEmail = backendSettings.feedbackEmail,
                    feedbackUrl = backendSettings.feedbackUrl,
                    githubUrl = backendSettings.githubUrl
                )
            }
        },
        gesturesEnabled = true
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = "流萤加速器",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    actions = {
                        // 工具菜单按钮
                        var showToolsMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showToolsMenu = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "工具",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showToolsMenu,
                                onDismissRequest = { showToolsMenu = false }
                            ) {
                                WEB_SPEED_TESTS.forEach { (label, url) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            showToolsMenu = false
                                            openWebSpeedTest(context, url)
                                        }
                                    )
                                }
                            }
                        }
                        
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // 当前节点信息
                if (currentNode != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "当前节点",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentNode!!.getFlagEmoji(),
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentNode!!.getDisplayName(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        if (currentNode!!.latency > 0) {
                            LatencyBadge(node = currentNode!!)
                        }

                        if (currentNode!!.downloadMbps > 0f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "带宽: %.1f Mbps".format(currentNode!!.downloadMbps),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (isLoading) {
                    Text(
                        text = "正在获取节点...",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 连接按钮
                ConnectButton(
                    vpnState = vpnState,
                    enabled = !isTesting,
                    onClick = {
                        when (vpnState) {
                            VpnState.CONNECTED -> onStartVpn { viewModel.toggleVpn() }
                            VpnState.DISCONNECTED -> onStartVpn {
                                if (smartConnectEnabled) {
                                    viewModel.startSmartConnection()
                                } else {
                                    viewModel.startSelectedOrFirstConnection()
                                }
                            }
                            else -> Unit
                        }
                    },
                    customLabel = if (vpnState == VpnState.DISCONNECTED) {
                        if (isTesting) {
                            testingLabel ?: "智能连接中..."
                        } else if (smartConnectEnabled) {
                            "点击上方按钮进行智能连接"
                        } else {
                            "点击上方按钮进行连接"
                        }
                    } else {
                        null
                    }
                )
                
                // 流量统计 (仅在连接时显示)
                if (vpnState == VpnState.CONNECTED) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TrafficStats(viewModel)
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 节点选择器
                NodeSelector(
                    currentNode = currentNode,
                    onClick = onOpenNodeList
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // 节点列表弹窗
    infoDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearInfoDialogMessage() },
            title = { Text("提示") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearInfoDialogMessage() }) {
                    Text("知道了")
                }
            }
        )
    }

    // 通知弹窗
    notice?.let { noticeInfo ->
        NoticeDialog(
            notice = noticeInfo,
            onDismiss = { viewModel.dismissNotice() }
        )
    }
    
    // 更新弹窗
    if (updateInfo != null) {
        UpdateDialog(
            version = updateInfo?.version ?: "",
            changelog = updateInfo?.changelog ?: "",
            isForce = updateInfo?.isForce == 1,
            onUpdate = { viewModel.openDownloadUrl() },
            onIgnore = { viewModel.ignoreCurrentUpdate() },
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
    
    // 下载状态监听
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    val installLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        // 从设置页返回后再次尝试安装
        if (context.packageManager.canRequestPackageInstalls()) {
            viewModel.installApk()
        }
    }
    
    // 下载完成后自动安装
    LaunchedEffect(downloadState.status) {
        if (downloadState.status == xyz.a202132.app.network.DownloadStatus.COMPLETED) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    showPermissionDialog = true
                } else {
                    viewModel.installApk()
                }
            } else {
                viewModel.installApk()
            }
        }
    }
    
    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    installLauncher.launch(intent)
                }
            }
        )
    }
    
    // 下载弹窗
    if (downloadState.status == xyz.a202132.app.network.DownloadStatus.DOWNLOADING || 
        downloadState.status == xyz.a202132.app.network.DownloadStatus.PAUSED ||
        downloadState.status == xyz.a202132.app.network.DownloadStatus.ERROR) {
        DownloadDialog(
            progress = downloadState.progress,
            speed = downloadState.speed,
            status = downloadState.status,
            errorMessage = downloadState.error,
            onPause = { viewModel.pauseDownload() },
            onResume = { viewModel.resumeDownload() },
            onCancel = { viewModel.cancelDownload() },
            onRetry = { viewModel.retryDownload() }
        )
    }
    
    // 连续下载失败后提示从官网下载
    var showWebsiteFallback by remember { mutableStateOf(false) }
    val websiteUrl = backendSettings.websiteUrl
    
    LaunchedEffect(downloadState.consecutiveFailures) {
        if (downloadState.consecutiveFailures >= 3 && websiteUrl.isNotBlank()) {
            showWebsiteFallback = true
        }
    }
    
    if (showWebsiteFallback) {
        Dialog(onDismissRequest = { showWebsiteFallback = false }) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
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
                        text = "APP已连续 ${downloadState.consecutiveFailures} 次下载失败，是否选择从官网下载？",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Text("继续重试")
                        }
                        
                        Button(
                            onClick = {
                                showWebsiteFallback = false
                                viewModel.cancelDownload()
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(websiteUrl))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("去官网")
                        }
                    }
                }
            }
        }
    }
    
    // 用户协议弹窗 (仅在未同意时显示)
    if (!isUserAgreementAccepted) {
        UserAgreementDialog(
            onAgree = { viewModel.acceptUserAgreement() },
            onDisagree = { (context as? android.app.Activity)?.finish() }
        )
    }

}

/**
 * 将每秒更新的流量状态限制在这一小块组合树中，避免整张主页随统计值重组。
 */
@Composable
private fun TrafficStats(viewModel: MainViewModel) {
    val uploadSpeed by viewModel.uploadSpeed.collectAsStateWithLifecycle()
    val downloadSpeed by viewModel.downloadSpeed.collectAsStateWithLifecycle()
    val uploadTotal by viewModel.uploadTotal.collectAsStateWithLifecycle()
    val downloadTotal by viewModel.downloadTotal.collectAsStateWithLifecycle()

    TrafficStatsRow(
        uploadSpeed = uploadSpeed,
        downloadSpeed = downloadSpeed,
        uploadTotal = uploadTotal,
        downloadTotal = downloadTotal
    )
}

private fun openWebSpeedTest(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
