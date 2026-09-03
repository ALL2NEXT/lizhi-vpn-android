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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.ui.components.*
import xyz.a202132.app.ui.dialogs.*
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.viewmodel.MainViewModel

private val WEB_SPEED_TESTS = listOf(
    "中科大测速" to AppConfig.USTC_SPEED_TEST_URL,
    "南大测速" to AppConfig.NJU_SPEED_TEST_URL,
    "奈飞测速" to AppConfig.NETFLIX_SPEED_TEST_URL,
    "Google测速" to AppConfig.GOOGLE_SPEED_TEST_URL
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenProxyScope: () -> Unit = {},
    onOpenNodeList: () -> Unit = {},
    onOpenOtherConfig: () -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    bottomNavigationDestination: HomeDestination,
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
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val favoriteSourceNodeIds by viewModel.favoriteSourceNodeIds.collectAsStateWithLifecycle()
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
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
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                FloatingHomeNavigationBar(
                    selectedDestination = bottomNavigationDestination,
                    onDestinationSelected = onBottomNavigationDestinationSelected
                )
            },
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
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // 主页复用节点列表卡片，并在连接期间明确标出实际节点所属订阅组。
                currentNode?.let { node ->
                    if (vpnState != VpnState.DISCONNECTED) {
                        Text(
                            text = "当前连接订阅组：${node.getConnectionGroupName()}",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    NodeListItem(
                        node = node,
                        isSelected = node.id == selectedNodeId,
                        isFavorite = node.source == NodeSource.FAVORITE ||
                            favoriteSourceNodeIds.contains(node.id),
                        enabled = !isTesting,
                        displaySettings = nodeDisplaySettings,
                        coreSettings = protocolCoreSettings,
                        onClick = onOpenNodeList,
                        onToggleFavorite = { viewModel.toggleFavoriteNode(node) }
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
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
                                viewModel.startSelectedOrFirstConnection()
                            }
                            else -> Unit
                        }
                    },
                    onLongClick = if (vpnState == VpnState.DISCONNECTED) {
                        {
                            onStartVpn {
                                viewModel.startSmartConnection()
                            }
                        }
                    } else {
                        null
                    },
                    customLabel = if (vpnState == VpnState.DISCONNECTED) {
                        if (isTesting) {
                            testingLabel ?: "智能连接中..."
                        } else {
                            "点击直接连接\n长按智能连接"
                        }
                    } else {
                        null
                    }
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Spacer(modifier = Modifier.height(32.dp))
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
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.canRequestPackageInstalls()
        ) {
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

private fun openWebSpeedTest(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
