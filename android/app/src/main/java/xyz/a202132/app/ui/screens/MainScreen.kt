package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.SpeedTestScheme
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.ui.components.*
import xyz.a202132.app.ui.dialogs.*
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenNodeList: () -> Unit = {},
    onOpenOtherConfig: () -> Unit = {},
    onOpenInbound: () -> Unit = {},
    onOpenCoreRouting: () -> Unit = {},
    onOpenOutbound: () -> Unit = {},
    onOpenInboundBefore: () -> Unit = {},
    onOpenOutboundAfter: () -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    bottomNavigationDestination: HomeDestination,
    nodeGroupingMode: NodeGroupingMode,
    onBottomNavigationDestinationSelected: (HomeDestination) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackendDialog by remember { mutableStateOf(false) }
    var backendUrlDraft by remember { mutableStateOf(AppConfig.BACKEND_URL) }
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
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    val speedTestSchemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val homeClickSchemeId by viewModel.homeClickSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeLongPressSchemeId by viewModel.homeLongPressSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeSwipeUpSchemeId by viewModel.homeSwipeUpSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeSwipeDownSchemeId by viewModel.homeSwipeDownSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeSwipeLeftSchemeId by viewModel.homeSwipeLeftSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeSwipeRightSchemeId by viewModel.homeSwipeRightSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeClickSpeedTestEnabled by viewModel.homeClickSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeLongPressSpeedTestEnabled by viewModel.homeLongPressSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeSwipeUpSpeedTestEnabled by viewModel.homeSwipeUpSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeSwipeDownSpeedTestEnabled by viewModel.homeSwipeDownSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeSwipeLeftSpeedTestEnabled by viewModel.homeSwipeLeftSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeSwipeRightSpeedTestEnabled by viewModel.homeSwipeRightSpeedTestEnabled.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val testingLabel by viewModel.testingLabel.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val infoDialogMessage by viewModel.infoDialogMessage.collectAsStateWithLifecycle()
    val isUserAgreementAccepted by viewModel.isUserAgreementAccepted.collectAsStateWithLifecycle()
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
    val nodeDisplaySettings by viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    
    val clickScheme = speedTestSchemes.firstOrNull { it.id == homeClickSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.STANDARD_ID }
    val longPressScheme = speedTestSchemes.firstOrNull { it.id == homeLongPressSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEEP_ID }
    val swipeUpScheme = speedTestSchemes.firstOrNull { it.id == homeSwipeUpSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.STANDARD_ID }
    val swipeDownScheme = speedTestSchemes.firstOrNull { it.id == homeSwipeDownSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEEP_ID }
    val swipeLeftScheme = speedTestSchemes.firstOrNull { it.id == homeSwipeLeftSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.STANDARD_ID }
    val swipeRightScheme = speedTestSchemes.firstOrNull { it.id == homeSwipeRightSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEEP_ID }
    
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
                    onOpenOtherConfig = { closeDrawerThen(onOpenOtherConfig) },
                    onOpenInbound = { closeDrawerThen(onOpenInbound) },
                    onOpenOutbound = { closeDrawerThen(onOpenOutbound) },
                    onOpenInboundBefore = { closeDrawerThen(onOpenInboundBefore) },
                    onOpenOutboundAfter = { closeDrawerThen(onOpenOutboundAfter) },
                    onOpenCoreRouting = { closeDrawerThen(onOpenCoreRouting) },
                    onOpenFeatures = { closeDrawerThen(onOpenFeatures) },
                    onOpenAccountSettings = { closeDrawerThen(onOpenAccountSettings) },
                    onOpenSkin = { closeDrawerThen(onOpenSkin) },
                    onOpenBackendDialog = {
                        closeDrawerThen {
                            backendUrlDraft = AppConfig.BACKEND_URL
                            showBackendDialog = true
                        }
                    },
                    websiteUrl = backendSettings.websiteUrl,
                    feedbackEmail = backendSettings.feedbackEmail,
                    feedbackUrl = backendSettings.feedbackUrl,
                    githubUrl = backendSettings.githubUrl,
                    telegramUrl = backendSettings.telegramUrl,
                    accountInfo = accountInfo,
                    customImagePath = skinSettings.customImagePath
                )
            }
        },
        gesturesEnabled = true
    ) {
        FloatingHomeNavigationLayout(
            selectedDestination = bottomNavigationDestination,
            groupingMode = nodeGroupingMode,
            onDestinationSelected = onBottomNavigationDestinationSelected
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    drawerState.animateTo(DrawerValue.Open, drawerAnimationSpec)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "打开设置菜单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Firefly",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    actions = {
                        ProxyModeToggle(
                            isGlobalMode = proxyMode == ProxyMode.GLOBAL,
                            onModeChange = { isGlobal ->
                                viewModel.setProxyMode(
                                    if (isGlobal) ProxyMode.GLOBAL else ProxyMode.SMART
                                )
                            }
                        )
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
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 86.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Spacer(modifier = Modifier.height(64.dp))
                
                // 主页直接展示设备、加密隧道、节点服务器与互联网之间的链路。
                ConnectionRouteOverview(
                    node = currentNode,
                    vpnState = vpnState,
                    isTesting = isTesting,
                    testingLabel = testingLabel,
                    enabled = !isTesting,
                    displaySettings = nodeDisplaySettings,
                    coreSettings = protocolCoreSettings,
                    onNodeClick = onOpenNodeList
                )
                
                Spacer(modifier = Modifier.weight(0.7f))
                
                // 连接按钮
                ConnectButton(
                    vpnState = vpnState,
                    enabled = !isTesting,
                    isTesting = isTesting,
                    customDisconnectedImagePath = skinSettings.homeDisconnectedImagePath,
                    customConnectingImagePath = skinSettings.homeConnectingImagePath,
                    customConnectedImagePath = skinSettings.homeConnectedImagePath,
                    onClick = {
                        when (vpnState) {
                            VpnState.CONNECTED -> onStartVpn { viewModel.toggleVpn() }
                            VpnState.DISCONNECTED -> onStartVpn {
                                if (homeClickSpeedTestEnabled) {
                                    clickScheme?.let { viewModel.startSpeedTestScheme(it.id) }
                                } else {
                                    viewModel.startSelectedOrFirstConnection()
                                }
                            }
                            else -> Unit
                        }
                    },
                    onLongClick = if (
                        vpnState == VpnState.DISCONNECTED && homeLongPressSpeedTestEnabled
                    ) {
                        {
                            onStartVpn {
                                longPressScheme?.let { viewModel.startSpeedTestScheme(it.id) }
                            }
                        }
                    } else {
                        null
                    },
                    onSwipe = if (vpnState == VpnState.DISCONNECTED) {
                        { direction ->
                            val scheme = when (direction) {
                                ConnectButtonSwipeDirection.UP -> swipeUpScheme
                                ConnectButtonSwipeDirection.DOWN -> swipeDownScheme
                                ConnectButtonSwipeDirection.LEFT -> swipeLeftScheme
                                ConnectButtonSwipeDirection.RIGHT -> swipeRightScheme
                            }
                            val enabled = when (direction) {
                                ConnectButtonSwipeDirection.UP -> homeSwipeUpSpeedTestEnabled
                                ConnectButtonSwipeDirection.DOWN -> homeSwipeDownSpeedTestEnabled
                                ConnectButtonSwipeDirection.LEFT -> homeSwipeLeftSpeedTestEnabled
                                ConnectButtonSwipeDirection.RIGHT -> homeSwipeRightSpeedTestEnabled
                            }
                            if (enabled) {
                                onStartVpn {
                                    scheme?.let { viewModel.startSpeedTestScheme(it.id) }
                                }
                            }
                        }
                    } else {
                        null
                    },
                    // 状态提示统一由上方链路图展示，连接按钮区域只保留图标。
                    showStatusLabel = false,
                    clickActionLabel = if (homeClickSpeedTestEnabled) {
                        clickScheme?.name ?: "测速方案"
                    } else {
                        "直接连接"
                    },
                    longClickActionLabel = if (homeLongPressSpeedTestEnabled) {
                        longPressScheme?.name ?: "测速方案"
                    } else {
                        "点击连接"
                    }
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
    
    // 节点列表弹窗
    if (showBackendDialog) {
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            title = { Text("切换后台") },
            text = {
                OutlinedTextField(
                    value = backendUrlDraft,
                    onValueChange = { backendUrlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("后端地址") },
                    placeholder = { Text("https://example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!AppConfig.updateBackendUrl(context, backendUrlDraft)) {
                            Toast.makeText(
                                context,
                                "后端地址无效，请输入域名或 http(s) 地址",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            NetworkClient.reloadBackend()
                            showBackendDialog = false
                            Toast.makeText(context, "后端地址已切换，正在刷新订阅", Toast.LENGTH_SHORT).show()
                            viewModel.refreshNodes()
                        }
                    }
                ) {
                    Text("保存并切换")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackendDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

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
            onSkip = { ignoreCurrentVersion ->
                viewModel.skipUpdate(ignoreCurrentVersion)
            },
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
    
    // 下载状态监听
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    
    // 下载完成后自动安装
    LaunchedEffect(downloadState.status) {
        if (downloadState.status == xyz.a202132.app.network.DownloadStatus.COMPLETED) {
            viewModel.installApk()
        }
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
    
    // 用户协议弹窗 (仅在未同意时显示)
    if (isUserAgreementAccepted == false) {
        UserAgreementDialog(
            onAgree = { viewModel.acceptUserAgreement() },
            onDisagree = { (context as? android.app.Activity)?.finish() }
        )
    }

}
