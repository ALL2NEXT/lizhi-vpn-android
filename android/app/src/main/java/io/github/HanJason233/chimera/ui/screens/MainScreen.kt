package io.github.HanJason233.chimera.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.ui.components.*
import io.github.HanJason233.chimera.ui.dialogs.*
import io.github.HanJason233.chimera.ui.theme.*
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import io.github.HanJason233.chimera.platform.LauncherIdentityPresets
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenOtherConfig: () -> Unit = {},
    onOpenPerAppInbound: () -> Unit = {},
    onOpenInbound: () -> Unit = {},
    onOpenCoreRouting: () -> Unit = {},
    onOpenOutbound: () -> Unit = {},
    onOpenSpeedTestSchemes: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    onOpenCurrentGroupDetails: (NodeGroupKey?, String) -> Unit = { _, _ -> },
    navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
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
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    val lanInbound by viewModel.lanInboundSettings.collectAsStateWithLifecycle()
    val speedTestSchemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val homeClickSchemeId by viewModel.homeClickSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeLongPressSchemeId by viewModel.homeLongPressSpeedTestSchemeId.collectAsStateWithLifecycle()
    val homeClickSpeedTestEnabled by viewModel.homeClickSpeedTestEnabled.collectAsStateWithLifecycle()
    val homeLongPressSpeedTestEnabled by viewModel.homeLongPressSpeedTestEnabled.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val unlockTestState by viewModel.unlockTestState.collectAsStateWithLifecycle()
    val linkTypeTestResult by viewModel.linkTypeTestResult.collectAsStateWithLifecycle()
    val currentNode by viewModel.currentNode.collectAsStateWithLifecycle()
    val homeTestNode by viewModel.homeTestNode.collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    val userSubscriptionGroups by viewModel.userSubscriptionGroups.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsStateWithLifecycle()
    val testProgress by viewModel.speedTestProgress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val infoDialogMessage by viewModel.infoDialogMessage.collectAsStateWithLifecycle()
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()

    val selectedGroup = NodeGroupKey.parse(selectedNodeGroupId)
    val selectedGroupName = when (selectedGroup) {
        is NodeGroupKey.Subscription -> subscriptionGroups
            .firstOrNull { it.sourceId == selectedGroup.sourceId }
            ?.name ?: "未知官方源"
        is NodeGroupKey.Country -> countryOptions
            .firstOrNull { it.code.equals(selectedGroup.countryCode, ignoreCase = true) }
            ?.name ?: selectedGroup.countryCode
        is NodeGroupKey.Imported -> userSubscriptionGroups
            .firstOrNull { it.id == NodeGroupKey.Imported(selectedGroup.groupId).value }
            ?.name ?: "未知仓库源"
        null -> "未选择"
    }
    val selectedGroupType = when (selectedGroup) {
        is NodeGroupKey.Subscription -> "官方源"
        is NodeGroupKey.Imported -> "私人源"
        is NodeGroupKey.Country -> "地区组"
        null -> "选择分组"
    }
    val clickScheme = speedTestSchemes.firstOrNull { it.id == homeClickSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }
    val longPressScheme = speedTestSchemes.firstOrNull { it.id == homeLongPressSchemeId }
        ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }

    // 显示错误Toast
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    // 移动网络提醒
    val cellularWarning by io.github.HanJason233.chimera.service.ServiceManager.cellularWarning.collectAsStateWithLifecycle()
    LaunchedEffect(cellularWarning) {
        cellularWarning?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            io.github.HanJason233.chimera.service.ServiceManager.clearCellularWarning()
        }
    }

    // Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                DrawerContent(
                    onCheckUpdate = { closeDrawerThen { viewModel.checkUpdate() } },
                    onOpenOtherConfig = { closeDrawerThen(onOpenOtherConfig) },
                    onOpenPerAppInbound = { closeDrawerThen(onOpenPerAppInbound) },
                    onOpenInbound = { closeDrawerThen(onOpenInbound) },
                    onOpenOutbound = { closeDrawerThen(onOpenOutbound) },
                    onOpenSpeedTestSchemes = { closeDrawerThen(onOpenSpeedTestSchemes) },
                    onOpenCoreRouting = { closeDrawerThen(onOpenCoreRouting) },
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
                    appDisplayName = LauncherIdentityPresets.find(
                        skinSettings.activeScheme.appIconPresetId
                    ).displayName,
                    appIconResourceId = LauncherIdentityPresets.find(
                        skinSettings.activeScheme.appIconPresetId
                    ).previewIconResource
                )
            }
        },
        gesturesEnabled = true
    ) {
        HomeNavigationLayout(
            selectedDestination = bottomNavigationDestination,
            onDestinationSelected = onBottomNavigationDestinationSelected,
            navigationStyle = navigationStyle
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
                            text = LauncherIdentityPresets.find(
                                skinSettings.activeScheme.appIconPresetId
                            ).displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (lanInbound.enabled) {
                                    Text(
                                        text = "本机:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                ProxyModeToggle(
                                    isGlobalMode = proxyMode == ProxyMode.GLOBAL,
                                    onModeChange = { isGlobal ->
                                        val mode = if (isGlobal) ProxyMode.GLOBAL else ProxyMode.SMART
                                        if (mode != proxyMode) viewModel.setProxyMode(mode)
                                    },
                                    compact = lanInbound.enabled
                                )
                            }
                            if (lanInbound.enabled) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "局域网:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    ProxyModeToggle(
                                        isGlobalMode = lanInbound.proxyMode == ProxyMode.GLOBAL,
                                        onModeChange = { isGlobal ->
                                            val mode = if (isGlobal) ProxyMode.GLOBAL else ProxyMode.SMART
                                            if (mode != lanInbound.proxyMode) viewModel.setLanInboundProxyMode(mode)
                                        },
                                        compact = true
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
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().padding(paddingValues)
                ) {
                    var testsCardHeightPx by remember { mutableIntStateOf(0) }
                    var selectionCardHeightPx by remember { mutableIntStateOf(0) }
                    val measuredCardsHeight = with(LocalDensity.current) {
                        testsCardHeightPx.toDp() + selectionCardHeightPx.toDp()
                    }
                    val connectButtonSize = ((maxHeight - 48.dp - measuredCardsHeight) / 1.42f)
                        .coerceIn(84.dp, 215.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeTestsCard(
                            node = homeTestNode,
                            isTesting = isTesting,
                            isHysteriaBandwidthAdapting = isHysteriaBandwidthAdapting,
                            progress = testProgress,
                            ipTestState = unlockTestState,
                            linkTypeResult = linkTypeTestResult,
                            onInterruptTests = viewModel::forceCancelAllTests,
                            modifier = Modifier.onSizeChanged { testsCardHeightPx = it.height }
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        ConnectButton(
                            vpnState = vpnState,
                            maxButtonSize = connectButtonSize,
                            enabled = !isTesting,
                            isTesting = isTesting,
                            customDisconnectedImagePath = skinSettings.homeDisconnectedImagePath,
                            customConnectingImagePath = skinSettings.homeConnectingImagePath,
                            customDisconnectingImagePath = skinSettings.homeDisconnectingImagePath,
                            customConnectedImagePath = skinSettings.homeConnectedImagePath,
                            homeIconEffect = skinSettings.activeScheme.homeIconEffect,
                            disconnectedParticleEffectEnabled = skinSettings.activeScheme.homeDisconnectedParticleEffectEnabled,
                            connectingParticleEffectEnabled = skinSettings.activeScheme.homeConnectingParticleEffectEnabled,
                            disconnectingParticleEffectEnabled = skinSettings.activeScheme.homeDisconnectingParticleEffectEnabled,
                            connectedParticleEffectEnabled = skinSettings.activeScheme.homeConnectedParticleEffectEnabled,
                            disconnectedIconEffect = skinSettings.activeScheme.homeDisconnectedIconEffect,
                            connectingIconEffect = skinSettings.activeScheme.homeConnectingIconEffect,
                            disconnectingIconEffect = skinSettings.activeScheme.homeDisconnectingIconEffect,
                            connectedIconEffect = skinSettings.activeScheme.homeConnectedIconEffect,
                            onClick = {
                                if (!isTesting) {
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
                                }
                            },
                            onLongClick = if (
                                vpnState == VpnState.DISCONNECTED &&
                                    homeLongPressSpeedTestEnabled && !isTesting
                            ) {
                                {
                                    onStartVpn {
                                        longPressScheme?.let { viewModel.startSpeedTestScheme(it.id) }
                                    }
                                }
                            } else {
                                null
                            },
                            // 主页连接区域只保留图标。
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

                        HomeSelectionCard(
                            name = selectedGroupName,
                            groupType = selectedGroupType,
                            node = currentNode,
                            vpnState = vpnState,
                            onGroupClick = {
                                onBottomNavigationDestinationSelected(
                                    if (selectedGroup is NodeGroupKey.Country) {
                                        HomeDestination.NODE_GROUPING
                                    } else {
                                        HomeDestination.SUBSCRIPTION_GROUPING
                                    }
                                )
                            },
                            onNodeClick = { onOpenCurrentGroupDetails(selectedGroup, selectedGroupName) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { selectionCardHeightPx = it.height }
                        )
                    }
                }
            }
        }
    }

    // 节点列表弹窗
    if (showBackendDialog) {
        AppAlertDialog(
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
        AppAlertDialog(
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
        if (downloadState.status == io.github.HanJason233.chimera.network.download.DownloadStatus.COMPLETED) {
            viewModel.installApk()
        }
    }

    // 下载弹窗
    if (downloadState.status == io.github.HanJason233.chimera.network.download.DownloadStatus.DOWNLOADING ||
        downloadState.status == io.github.HanJason233.chimera.network.download.DownloadStatus.PAUSED ||
        downloadState.status == io.github.HanJason233.chimera.network.download.DownloadStatus.ERROR) {
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

}
