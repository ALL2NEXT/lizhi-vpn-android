package io.github.HanJason233.chimera.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.ui.components.*
import io.github.HanJason233.chimera.ui.theme.*
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import io.github.HanJason233.chimera.platform.LauncherIdentityPresets
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenOtherConfig: () -> Unit = {},
    onOpenRoutingAssets: () -> Unit = {},
    onOpenRegionLookupSettings: () -> Unit = {},
    onOpenSplitRoutingSettings: () -> Unit = {},
    onOpenInboundOutbound: () -> Unit = {},
    onOpenSpeedTestSchemes: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    onOpenCurrentGroupDetails: (NodeGroupKey?, String) -> Unit = { _, _ -> },
    navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
    bottomNavigationDestination: HomeDestination,
    onBottomNavigationDestinationSelected: (HomeDestination) -> Unit
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
                    onOpenOtherConfig = { closeDrawerThen(onOpenOtherConfig) },
                    onOpenRoutingAssets = { closeDrawerThen(onOpenRoutingAssets) },
                    onOpenRegionLookupSettings = {
                        closeDrawerThen(onOpenRegionLookupSettings)
                    },
                    onOpenSplitRoutingSettings = { closeDrawerThen(onOpenSplitRoutingSettings) },
                    onOpenInboundOutbound = { closeDrawerThen(onOpenInboundOutbound) },
                    onOpenSpeedTestSchemes = { closeDrawerThen(onOpenSpeedTestSchemes) },
                    onOpenSkin = { closeDrawerThen(onOpenSkin) },
                    appDisplayName = LauncherIdentityPresets.find(
                        skinSettings.appIconPresetId
                    ).displayName,
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
                                skinSettings.appIconPresetId
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
                            progressFlow = viewModel.speedTestProgress,
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
                            homeIconEffect = skinSettings.homeIconEffect,
                            disconnectedParticleEffectEnabled = skinSettings.homeDisconnectedParticleEffectEnabled,
                            connectingParticleEffectEnabled = skinSettings.homeConnectingParticleEffectEnabled,
                            disconnectingParticleEffectEnabled = skinSettings.homeDisconnectingParticleEffectEnabled,
                            connectedParticleEffectEnabled = skinSettings.homeConnectedParticleEffectEnabled,
                            disconnectedIconEffect = skinSettings.homeDisconnectedIconEffect,
                            connectingIconEffect = skinSettings.homeConnectingIconEffect,
                            disconnectingIconEffect = skinSettings.homeDisconnectingIconEffect,
                            connectedIconEffect = skinSettings.homeConnectedIconEffect,
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

}
