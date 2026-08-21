package xyz.a202132.windows.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import org.jetbrains.skia.Image as SkiaImage
import xyz.a202132.windows.core.VpnController
import xyz.a202132.windows.model.AppScreen
import xyz.a202132.windows.model.CoreStatus
import xyz.a202132.windows.model.DesktopAppState
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.Ipv6RoutingMode
import xyz.a202132.windows.model.NodeCategory
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.TunStack
import xyz.a202132.windows.model.VpnState

private val WEB_SPEED_TESTS = listOf(
    "中科大测速" to "https://test.ustc.edu.cn/",
    "南大测速" to "https://test.nju.edu.cn/",
    "Netflix 测速" to "https://fast.com/",
    "Google 测速" to "https://fiber.google.com/speedtest/"
)

@Composable
fun FireflyDesktopApp(controller: VpnController) {
    val state by controller.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(controller) {
        controller.refreshCore()
        controller.refreshNodes()
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            controller.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(
            targetState = state.screen,
            animationSpec = tween(180),
            label = "screen"
        ) { screen ->
            when (screen) {
                AppScreen.MAIN -> MainScreen(state, controller)
                AppScreen.NODE_LIST -> NodeListScreen(state, controller)
                else -> SecondaryScreen(screen, state, controller)
            }
        }

        AnimatedVisibility(
            visible = state.drawerOpen,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(120))
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = controller::closeDrawer
                        )
                )
                AnimatedVisibility(
                    visible = state.drawerOpen,
                    enter = slideInHorizontally(tween(230, easing = FastOutSlowInEasing)) { -it },
                    exit = slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { -it }
                ) {
                    AppDrawer(state, controller)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: DesktopAppState, controller: VpnController) {
    var showToolsMenu by remember { mutableStateOf(false) }
    var showBackendDialog by remember { mutableStateOf(false) }
    var backendUrlDraft by remember(state.backendUrl) { mutableStateOf(state.backendUrl) }
    var titleClickCount by remember { mutableStateOf(0) }
    var lastTitleClickAt by remember { mutableStateOf(0L) }
    var swipeDistance by remember { mutableFloatStateOf(0f) }
    val swipeProgress = (swipeDistance / 72f).coerceIn(0f, 1f)

    fun handleBackendTitleClick() {
        val now = System.currentTimeMillis()
        titleClickCount = if (lastTitleClickAt > 0L && now - lastTitleClickAt <= 1_500L) {
            titleClickCount + 1
        } else {
            1
        }
        lastTitleClickAt = now
        if (titleClickCount >= 5) {
            titleClickCount = 0
            backendUrlDraft = state.backendUrl
            showBackendDialog = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = controller::openDrawer) {
                        Icon(Icons.Default.Menu, "打开设置菜单")
                    }
                },
                title = {
                    Text(
                        "FireflyVPN",
                        modifier = Modifier.clickable(onClick = ::handleBackendTitleClick),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    ProxyModeToggle(
                        mode = state.proxyMode,
                        onModeSelected = controller::setProxyMode
                    )
                    Box {
                        IconButton(onClick = { showToolsMenu = true }) {
                            Icon(Icons.Default.MoreVert, "工具")
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
                                        browse(url)
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            if (dragAmount < 0f) {
                                swipeDistance = (swipeDistance - dragAmount).coerceAtMost(90f)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (swipeDistance >= 72f) controller.navigate(AppScreen.NODE_LIST)
                            swipeDistance = 0f
                        },
                        onDragCancel = { swipeDistance = 0f }
                    )
                }
                .padding(horizontal = 24.dp)
                .graphicsLayer { translationY = -14f * swipeProgress },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            CurrentNode(node = state.currentNode, isLoading = state.isLoadingNodes)
            Spacer(Modifier.weight(1f))
            ConnectButton(
                vpnState = state.vpnState,
                enabled = !state.isTesting,
                customLabel = if (state.vpnState == VpnState.DISCONNECTED) {
                    state.testingLabel ?: "点击直接连接\n长按智能连接"
                } else {
                    null
                },
                onClick = controller::startSelectedOrFirstConnection,
                onLongClick = if (state.vpnState == VpnState.DISCONNECTED) {
                    controller::startSmartConnection
                } else {
                    null
                }
            )
            Spacer(Modifier.weight(1f))
            NodeSwipeHint(
                progress = swipeProgress,
                onClick = { controller.navigate(AppScreen.NODE_LIST) }
            )
            Spacer(Modifier.height(14.dp))
        }
    }

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
                    placeholder = { Text("https://example.com") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.updateBackendUrl(backendUrlDraft)) showBackendDialog = false
                }) {
                    Text("保存并刷新")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackendDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CurrentNode(node: DesktopNode?, isLoading: Boolean) {
    if (node == null) {
        if (isLoading) {
            Text(
                "正在获取节点...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("当前节点", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.flagEmoji(), fontSize = 24.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                node.displayName(),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (node.tcpingLatency > 0) {
            Spacer(Modifier.height(6.dp))
            LatencyBadge(node.tcpingLatency)
        }
        if (node.downloadMbps > 0f) {
            Spacer(Modifier.height(4.dp))
            Text(
                "带宽: %.1f Mbps".format(node.downloadMbps),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProxyModeToggle(mode: ProxyMode, onModeSelected: (ProxyMode) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(2.dp)) {
            ProxyModeOption("全局", mode == ProxyMode.GLOBAL) { onModeSelected(ProxyMode.GLOBAL) }
            ProxyModeOption("智能", mode == ProxyMode.SMART) { onModeSelected(ProxyMode.SMART) }
        }
    }
}

@Composable
private fun ProxyModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        color = if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectButton(
    vpnState: VpnState,
    enabled: Boolean,
    customLabel: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val transitioning = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING
    val imageName = when (vpnState) {
        VpnState.DISCONNECTED -> "btn_disconnected.png"
        VpnState.CONNECTING, VpnState.DISCONNECTING -> "btn_connecting.png"
        VpnState.CONNECTED -> "btn_connected.png"
    }
    val image = remember(imageName) { loadBitmapResource(imageName) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed && enabled && !transitioning) 0.97f else 1f,
        tween(if (pressed) 90 else 140),
        label = "press"
    )
    val pulseScale = if (transitioning) {
        val transition = rememberInfiniteTransition(label = "connectionPulse")
        val scale by transition.animateFloat(
            0.99f,
            1.035f,
            infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse"
        )
        scale
    } else 1f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val buttonSize = (maxWidth * 0.65f).coerceIn(180.dp, 250.dp)
            Crossfade(
                targetState = image,
                animationSpec = tween(220),
                label = "buttonImage"
            ) { bitmap ->
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = connectionLabel(vpnState),
                        modifier = Modifier
                            .size(buttonSize)
                            .graphicsLayer {
                                scaleX = pulseScale * pressScale
                                scaleY = pulseScale * pressScale
                            }
                            .combinedClickable(
                                enabled = enabled && !transitioning,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            customLabel ?: connectionLabel(vpnState),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (customLabel != null) 18.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = if (customLabel != null) 26.sp else 20.sp
        )
    }
}

private fun connectionLabel(state: VpnState): String = when (state) {
    VpnState.CONNECTED -> "已连接"
    VpnState.CONNECTING -> "连接中..."
    VpnState.DISCONNECTING -> "断开中..."
    VpnState.DISCONNECTED -> "点击连接"
}

@Composable
private fun NodeSwipeHint(progress: Float, onClick: () -> Unit) {
    val tint = Color(
        red = TextSecondaryLight.red + (Primary.red - TextSecondaryLight.red) * progress,
        green = TextSecondaryLight.green + (Primary.green - TextSecondaryLight.green) * progress,
        blue = TextSecondaryLight.blue + (Primary.blue - TextSecondaryLight.blue) * progress
    )
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.KeyboardArrowUp, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            if (progress >= 1f) "松开打开节点列表" else "向上滑动选择节点",
            color = tint,
            fontSize = 14.sp,
            fontWeight = if (progress >= 1f) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.width(96.dp).height(3.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Primary))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeListScreen(state: DesktopAppState, controller: VpnController) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = controller::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Column {
                        Text("节点列表", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        state.testingLabel?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = controller::testTcping, enabled = !state.isTesting) {
                        Icon(Icons.Default.NetworkCheck, "TCPing")
                    }
                    IconButton(onClick = controller::testUrl, enabled = !state.isTesting) {
                        Icon(Icons.Default.Language, "URL Test")
                    }
                    IconButton(onClick = controller::refreshNodes, enabled = !state.isTesting) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (state.isTesting || state.isLoadingNodes) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary)
                Spacer(Modifier.height(10.dp))
            }
            NodeCategorySwitch(state, controller)
            Spacer(Modifier.height(12.dp))
            if (state.nodeCategory == NodeCategory.FAVORITES) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NodeImportAction(
                        Icons.Default.ContentPaste,
                        "剪贴板导入",
                        Modifier.weight(1f),
                        enabled = !state.isTesting
                    ) { controller.importNodes(readClipboard()) }
                    NodeImportAction(
                        Icons.Default.QrCodeScanner,
                        "二维码导入",
                        Modifier.weight(1f),
                        enabled = !state.isTesting
                    ) { controller.importNodes("") }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state.visibleNodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.isLoadingNodes) "正在获取节点..." else "暂无可用节点",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.visibleNodes, key = DesktopNode::id) { node ->
                        NodeListItem(
                            node = node,
                            selected = node.id == state.selectedNodeId,
                            enabled = !state.isTesting,
                            onSelect = { controller.selectAndConnectNode(node.id) },
                            onFavorite = { controller.toggleFavorite(node.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeCategorySwitch(state: DesktopAppState, controller: VpnController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.padding(4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val groups = state.subscriptionGroups.ifEmpty {
                listOf("" to "订阅节点")
            }
            groups.forEach { (sourceId, name) ->
                CategoryOption(
                    name,
                    state.nodeCategory == NodeCategory.SUBSCRIPTION &&
                        (sourceId.isEmpty() || sourceId == state.selectedSubscriptionSourceId),
                    Modifier.width(150.dp)
                ) {
                    if (sourceId.isEmpty()) {
                        controller.setNodeCategory(NodeCategory.SUBSCRIPTION)
                    } else {
                        controller.setSubscriptionSource(sourceId)
                    }
                }
            }
            if (!state.settings.hideFavoriteNodeGroup) {
                CategoryOption(
                    "收藏节点",
                    state.nodeCategory == NodeCategory.FAVORITES,
                    Modifier.width(150.dp)
                ) { controller.setNodeCategory(NodeCategory.FAVORITES) }
            }
        }
    }
}

@Composable
private fun CategoryOption(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        color = if (selected) Primary else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 9.dp),
            textAlign = TextAlign.Center,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun NodeImportAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun NodeListItem(
    node: DesktopNode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled, onClick = onSelect),
        color = if (selected) Primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) BorderStroke(2.dp, Primary) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.flagEmoji(), fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                Text(
                    node.displayName(),
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onFavorite, enabled = enabled, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (node.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        "收藏节点",
                        tint = if (node.isFavorite) Primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NodeMetric("TCPing", latencyText(node.tcpingLatency), latencyColor(node.tcpingLatency), Modifier.weight(1f))
                NodeMetric("204 URLTest", latencyText(node.urlTestLatency), latencyColor(node.urlTestLatency), Modifier.weight(1f))
                NodeMetric(
                    "带宽",
                    if (node.downloadMbps > 0f) "%.1f Mbps".format(node.downloadMbps) else "未测试",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NodeMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
    }
}

@Composable
private fun LatencyBadge(latency: Int) {
    val color = latencyColor(latency)
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            "$latency ms",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun latencyText(latency: Int) = when {
    latency > 0 -> "${latency}ms"
    latency == DesktopNode.FAILED -> "失败"
    else -> "未测试"
}

@Composable
private fun latencyColor(latency: Int) = when {
    latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    latency <= 200 -> Color(0xFF22C55E)
    latency <= 400 -> Color(0xFFFACC15)
    else -> ErrorRed
}

@Composable
private fun AppDrawer(state: DesktopAppState, controller: VpnController) {
    val wallpaper = remember { loadBitmapResource("drawer_wallpaper.png") }
    Surface(
        modifier = Modifier.fillMaxHeight().width(280.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            wallpaper?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)
            ) {
                DrawerItem(Icons.Outlined.Tune, "基础设定") {
                    controller.navigate(AppScreen.BASIC_SETTINGS)
                }
                DrawerItem(Icons.Outlined.Apps, "代理作用域") {
                    controller.navigate(AppScreen.PROXY_SCOPE)
                }
                DrawerItem(Icons.Outlined.Star, "特色功能") {
                    controller.navigate(AppScreen.FEATURES)
                }
                if (state.backendSettings.websiteUrl.isNotBlank()) {
                    DrawerItem(Icons.Default.Language, "官方网站") {
                        browse(state.backendSettings.websiteUrl)
                        controller.closeDrawer()
                    }
                }
                if (state.backendSettings.feedbackEmail.isNotBlank() ||
                    state.backendSettings.feedbackUrl.isNotBlank()
                ) {
                    DrawerItem(Icons.Outlined.Info, "问题反馈") {
                        if (state.backendSettings.feedbackEmail.isNotBlank()) {
                            writeClipboard(state.backendSettings.feedbackEmail)
                        }
                        if (state.backendSettings.feedbackUrl.isNotBlank()) {
                            browse(state.backendSettings.feedbackUrl)
                        }
                        controller.closeDrawer()
                    }
                }
                DrawerItem(Icons.Outlined.Info, "关于应用") {
                    controller.navigate(AppScreen.ABOUT)
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { controller.navigate(AppScreen.ACCOUNT_SETTINGS) },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = Primary, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            state.account?.nickname ?: if (state.isLoadingNodes) "正在同步账户" else "Windows 用户",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            listOfNotNull(
                                state.account?.accountType?.let(::accountTypeLabel),
                                coreLabel(state.coreStatus)
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = Primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondaryScreen(screen: AppScreen, state: DesktopAppState, controller: VpnController) {
    val title = when (screen) {
        AppScreen.ACCOUNT_SETTINGS -> "账户设置"
        AppScreen.BASIC_SETTINGS -> "基础设定"
        AppScreen.PROXY_SCOPE -> "代理作用域"
        AppScreen.FEATURES -> "特色功能"
        AppScreen.ABOUT -> "关于应用"
        else -> "FireflyVPN"
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = controller::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (screen) {
                AppScreen.ACCOUNT_SETTINGS -> AccountSettingsContent(state, controller)
                AppScreen.BASIC_SETTINGS -> {
                    BasicSettingsContent(state.settings, controller::updateSettings)
                }
                AppScreen.PROXY_SCOPE -> {
                    InfoCard("当前范围", "整机 TUN")
                    InfoCard("按应用代理", "Windows Filtering Platform 适配待接入")
                }
                AppScreen.FEATURES -> {
                    FeatureSettingsContent(
                        settings = state.settings,
                        isAdapting = state.isHysteriaBandwidthAdapting,
                        onAdapt = controller::adaptHysteriaBandwidth,
                        onSettingsChange = controller::updateSettings
                    )
                }
                AppScreen.ABOUT -> {
                    Text("FireflyVPN", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Primary)
                    Text("Windows 原生客户端 3.0.2", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoCard("sing-box", coreLabel(state.coreStatus))
                    InfoCard(
                        "后台",
                        if (state.backendConnected) {
                            "已连接，共 ${state.subscriptionSourceCount} 条可用订阅"
                        } else {
                            "未连接"
                        }
                    )
                    TextButton(onClick = {
                        browse(
                            state.backendSettings.githubUrl.ifBlank {
                                "https://github.com/SagerNet/sing-box"
                            }
                        )
                    }) {
                        Text("查看项目主页")
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun AccountSettingsContent(state: DesktopAppState, controller: VpnController) {
    val account = state.account
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showBindDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var nickname by remember(account?.nickname) { mutableStateOf(account?.nickname.orEmpty()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf("") }
    var deletePassword by remember { mutableStateOf("") }

    if (account == null) {
        Text(
            if (state.isLoadingNodes) "账户信息同步中…" else "账户信息暂不可用",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = controller::refreshNodes, enabled = !state.isLoadingNodes) {
            Text("重新同步")
        }
        return
    }

    AccountSection("账户信息") {
        AccountInfoRow("昵称", account.nickname) {
            TextButton(
                onClick = {
                    nickname = account.nickname
                    showNicknameDialog = true
                },
                enabled = !state.isAccountBusy && account.accountType != "banned"
            ) { Text("修改") }
        }
        AccountInfoRow("账户身份", accountTypeLabel(account.accountType))
        AccountInfoRow("UID", account.uid)
        AccountInfoRow("邮箱", account.email.ifBlank { "未登记" }) {
            if (account.email.isBlank()) {
                TextButton(
                    onClick = { showBindDialog = true },
                    enabled = state.backendSettings.registrationEnabled && !state.isAccountBusy
                ) {
                    Text(if (state.backendSettings.registrationEnabled) "注册正式用户" else "正式注册未开放")
                }
            }
        }
    }

    AccountSection("客户端信息") {
        AccountInfoRow("设备厂商", account.brand.ifBlank { "Microsoft" })
        AccountInfoRow("设备型号", account.model.ifBlank { System.getProperty("os.name", "Windows") })
        AccountInfoRow("系统版本", account.osVersion.ifBlank { System.getProperty("os.version", "未知") })
        AccountInfoRow("客户端版本", account.appVersion.ifBlank { "3.0.2" })
    }

    AccountSection("账户操作") {
        Text(
            "切换账户或注销账户前请先断开 VPN。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                email = ""
                password = ""
                showLoginDialog = true
            },
            enabled = !state.isAccountBusy && state.vpnState == VpnState.DISCONNECTED
        ) { Text(if (state.isAccountBusy) "处理中…" else "切换账户") }
        Text(
            "注销账户会永久删除账户、邮箱绑定和流量记录，此操作不可恢复。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error
        )
        Button(
            onClick = { showDeleteDialog = true },
            enabled = !state.isAccountBusy && state.vpnState == VpnState.DISCONNECTED &&
                account.accountType != "banned" && !account.deviceBlocked,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("注销账户并清除数据") }
    }

    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isAccountBusy) showNicknameDialog = false },
            title = { Text("修改昵称") },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 24) nickname = it },
                    label = { Text("昵称") },
                    supportingText = { Text("${nickname.length}/24") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNicknameDialog = false
                        controller.updateNickname(nickname)
                    },
                    enabled = nickname.trim().isNotEmpty() && !state.isAccountBusy
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showBindDialog) {
        AccountCredentialsDialog(
            title = "注册正式用户",
            email = email,
            onEmailChange = { email = it },
            password = password,
            onPasswordChange = { if (it.length <= 128) password = it },
            confirmPassword = confirmPassword,
            onConfirmPasswordChange = { if (it.length <= 128) confirmPassword = it },
            registrationCode = registrationCode,
            onRegistrationCodeChange = { if (it.length <= 32) registrationCode = it },
            requireRegistrationCode = state.backendSettings.requireAdminRegistrationCode,
            enabled = !state.isAccountBusy,
            onConfirm = {
                showBindDialog = false
                controller.bindAccount(email, password, registrationCode)
            },
            onDismiss = { showBindDialog = false }
        )
    }

    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isAccountBusy) showLoginDialog = false },
            title = { Text("切换账户") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入已绑定账户的邮箱和密码。")
                    CredentialField("账户邮箱", email, { email = it }, false)
                    CredentialField("账户密码", password, { if (it.length <= 128) password = it }, true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLoginDialog = false
                        controller.loginAccount(email, password)
                    },
                    enabled = email.trim().isNotEmpty() && password.length in 8..128 &&
                        !state.isAccountBusy
                ) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isAccountBusy) showDeleteDialog = false },
            title = { Text("注销账户并清除数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("账户数据、流量记录和本地节点将永久删除，此操作不可恢复。")
                    if (account.email.isNotBlank()) {
                        CredentialField(
                            "当前账户密码",
                            deletePassword,
                            { if (it.length <= 128) deletePassword = it },
                            true
                        )
                    } else {
                        Text("当前为游客账户，无需输入密码。")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        controller.deleteAccount(deletePassword)
                    },
                    enabled = !state.isAccountBusy &&
                        (account.email.isBlank() || deletePassword.length in 8..128)
                ) { Text("确认注销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountCredentialsDialog(
    title: String,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    registrationCode: String,
    onRegistrationCodeChange: (String) -> Unit,
    requireRegistrationCode: Boolean,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = registrationCode,
                    onValueChange = onRegistrationCodeChange,
                    label = { Text("管理员一次性注册码（${if (requireRegistrationCode) "必填" else "可选"}）") },
                    enabled = enabled,
                    singleLine = true
                )
                CredentialField("邮箱", email, onEmailChange, false, enabled)
                CredentialField("密码", password, onPasswordChange, true, enabled)
                CredentialField("确认密码", confirmPassword, onConfirmPasswordChange, true, enabled)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled && email.trim().isNotEmpty() && password.length in 8..128 &&
                    password == confirmPassword && (!requireRegistrationCode || registrationCode.isNotBlank())
            ) { Text("确认注册") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") } }
    )
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Email
        )
    )
}

@Composable
private fun AccountSection(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(0.34f), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.66f), fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

@Composable
private fun BasicSettingsContent(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit
) {
    SettingSection("网络配置") {
        Text("IPV6路由", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        MultiPositionSwitch(
            options = listOf("仅", "优先", "启用", "禁用"),
            selectedIndex = when (settings.ipv6RoutingMode) {
                Ipv6RoutingMode.ONLY -> 0
                Ipv6RoutingMode.PREFER -> 1
                Ipv6RoutingMode.ENABLED -> 2
                Ipv6RoutingMode.DISABLED -> 3
            }
        ) { index ->
            onSettingsChange(settings.copy(ipv6RoutingMode = when (index) {
                0 -> Ipv6RoutingMode.ONLY
                1 -> Ipv6RoutingMode.PREFER
                2 -> Ipv6RoutingMode.ENABLED
                else -> Ipv6RoutingMode.DISABLED
            }))
        }
    }
    SettingSection("TUN实现") {
        MultiPositionSwitch(
            options = TunStack.entries.map(TunStack::configValue),
            selectedIndex = TunStack.entries.indexOf(settings.tunStack)
        ) { index -> onSettingsChange(settings.copy(tunStack = TunStack.entries[index])) }
    }
    SettingsToggleRow("记住上次选择的节点", settings.rememberLastSelectedNode) {
        onSettingsChange(settings.copy(rememberLastSelectedNode = it))
    }
    SettingsToggleRow("自动删除超时节点", settings.autoDeleteTimeoutNodes) {
        onSettingsChange(settings.copy(autoDeleteTimeoutNodes = it))
    }
    SettingsToggleRow("简化测速数据", settings.simplifiedNodeTestDisplay) {
        onSettingsChange(settings.copy(simplifiedNodeTestDisplay = it))
    }
    SettingsToggleRow("隐藏节点列表中的“收藏节点”订阅组", settings.hideFavoriteNodeGroup) {
        onSettingsChange(settings.copy(hideFavoriteNodeGroup = it))
    }
    SettingSection("测试超时") {
        NumberSettingField("TCPing 超时", settings.tcpingTimeoutMs) {
            onSettingsChange(settings.copy(tcpingTimeoutMs = it.coerceAtLeast(500)))
        }
        NumberSettingField("URL Test 超时", settings.urlTestTimeoutMs) {
            onSettingsChange(settings.copy(urlTestTimeoutMs = it.coerceAtLeast(500)))
        }
        NumberSettingField("单次下载测速超时", settings.speedTestTimeoutMs) {
            onSettingsChange(settings.copy(speedTestTimeoutMs = it.coerceAtLeast(0)))
        }
    }
    SettingSection("并发与网络") {
        NumberSettingField("TCPing 并发数", settings.tcpingConcurrency) {
            onSettingsChange(settings.copy(tcpingConcurrency = it.coerceIn(1, 128)))
        }
        NumberSettingField("URL Test 并发数", settings.urlTestConcurrency) {
            onSettingsChange(settings.copy(urlTestConcurrency = it.coerceIn(1, 128)))
        }
        NumberSettingField("带宽测试并发数", settings.bandwidthTestConcurrency) {
            onSettingsChange(settings.copy(bandwidthTestConcurrency = it.coerceIn(1, 3)))
        }
        NumberSettingField("VPN MTU", settings.vpnMtu) {
            onSettingsChange(settings.copy(vpnMtu = it.coerceIn(576, 9000)))
        }
    }
}

@Composable
private fun FeatureSettingsContent(
    settings: DesktopSettings,
    isAdapting: Boolean,
    onAdapt: () -> Unit,
    onSettingsChange: (DesktopSettings) -> Unit
) {
    SettingSection("智能连接") {
        NumberSettingField("TCPing 节点比例 (%)", settings.smartConnectTcpingPercent) {
            onSettingsChange(settings.copy(smartConnectTcpingPercent = it.coerceIn(1, 100)))
        }
        NumberSettingField("URL Test 候选比例 (%)", settings.smartConnectUrlTestPercent) {
            onSettingsChange(settings.copy(smartConnectUrlTestPercent = it.coerceIn(1, 100)))
        }
        NumberSettingField("5MB 测速候选比例 (%)", settings.smartConnectSpeedTestPercent) {
            onSettingsChange(settings.copy(smartConnectSpeedTestPercent = it.coerceIn(1, 100)))
        }
    }
    SettingsToggleRow("断线恢复", settings.disconnectRecoveryEnabled) {
        onSettingsChange(settings.copy(disconnectRecoveryEnabled = it))
    }
    SettingSection("Hy2 加速") {
        SettingsToggleRow(
            title = "启用 Hy2 带宽参数",
            checked = settings.hysteriaBandwidthEnabled,
            compact = true
        ) { onSettingsChange(settings.copy(hysteriaBandwidthEnabled = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberSettingField(
                "Up",
                settings.hysteriaUpMbps,
                Modifier.weight(1f),
                settings.hysteriaBandwidthEnabled
            ) { onSettingsChange(settings.copy(hysteriaUpMbps = it.coerceIn(0, 100_000))) }
            NumberSettingField(
                "Dw",
                settings.hysteriaDownMbps,
                Modifier.weight(1f),
                settings.hysteriaBandwidthEnabled
            ) { onSettingsChange(settings.copy(hysteriaDownMbps = it.coerceIn(0, 100_000))) }
            TextButton(
                onClick = onAdapt,
                enabled = settings.hysteriaBandwidthEnabled && !isAdapting
            ) {
                Text(if (isAdapting) "测试中..." else "自适应")
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MultiPositionSwitch(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(4.dp)) {
            options.forEachIndexed { index, option ->
                Surface(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .clickable { onOptionSelected(index) },
                    color = if (index == selectedIndex) Primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        option,
                        modifier = Modifier.padding(vertical = 9.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = if (index == selectedIndex) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    compact: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(
            if (compact) Modifier else Modifier.padding(vertical = 2.dp)
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary
            )
        )
    }
}

@Composable
private fun NumberSettingField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) {
    var input by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = input,
        onValueChange = { changed ->
            input = changed.filter(Char::isDigit)
            input.toIntOrNull()?.let(onValueChange)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun InfoCard(title: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun coreLabel(status: CoreStatus): String = when (status) {
    CoreStatus.Checking -> "正在检查核心"
    CoreStatus.Missing -> "核心未就绪"
    is CoreStatus.Available -> "sing-box ${status.version}"
    is CoreStatus.Failed -> "核心异常"
}

private fun accountTypeLabel(type: String): String = when (type) {
    "formal" -> "正式账户"
    "guest" -> "游客账户"
    "banned" -> "账户已封禁"
    else -> type
}

private fun browse(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}

private fun readClipboard(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull().orEmpty()

private fun writeClipboard(value: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
}

private fun loadBitmapResource(name: String): ImageBitmap? = runCatching {
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(name)) {
        "Missing resource: $name"
    }.use { input ->
        SkiaImage.makeFromEncoded(input.readBytes()).toComposeImageBitmap()
    }
}.getOrNull()
