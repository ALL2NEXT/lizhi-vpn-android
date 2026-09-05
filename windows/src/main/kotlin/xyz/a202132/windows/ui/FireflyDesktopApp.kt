package xyz.a202132.windows.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import org.jetbrains.skia.Image as SkiaImage
import xyz.a202132.windows.core.VpnController
import xyz.a202132.windows.model.AppScreen
import xyz.a202132.windows.model.BandwidthTestTargets
import xyz.a202132.windows.model.CoreStatus
import xyz.a202132.windows.model.DesktopAppState
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.Ipv6RoutingMode
import xyz.a202132.windows.model.NodeCategory
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.ProtocolCoreSettings
import xyz.a202132.windows.model.ProxyCore
import xyz.a202132.windows.model.TunStack
import xyz.a202132.windows.model.UrlTestTargets
import xyz.a202132.windows.model.VpnState
import xyz.a202132.windows.qr.DesktopQrCode
import xyz.a202132.windows.qr.DesktopQrScanner

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
    var showTunAdminDialog by remember { mutableStateOf(false) }

    fun runWithTunPermission(action: () -> Unit) {
        if (state.tunEnabled && !isWindowsElevated()) {
            showTunAdminDialog = true
        } else {
            action()
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Firefly",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.tunEnabled) "TUN" else "系统代理",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = state.tunEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    controller.setTunEnabled(false)
                                } else if (isWindowsElevated()) {
                                    controller.setTunEnabled(true)
                                } else {
                                    showTunAdminDialog = true
                                }
                            },
                            enabled = !state.isTesting &&
                                state.vpnState != VpnState.CONNECTING &&
                                state.vpnState != VpnState.DISCONNECTING,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            // 主页直接复用节点列表卡片，避免额外显示当前节点、节点名和测速提示文字。
            state.currentNode?.let { node ->
                NodeListItem(
                    node = node,
                    selected = node.id == state.selectedNodeId,
                    enabled = !state.isTesting,
                    onSelect = { controller.navigate(AppScreen.NODE_LIST) },
                    onFavorite = { controller.toggleFavorite(node.id) }
                )
            }
            Spacer(Modifier.weight(1f))
            ConnectButton(
                vpnState = state.vpnState,
                enabled = !state.isTesting,
                isTesting = state.isTesting,
                customLabel = if (state.vpnState == VpnState.DISCONNECTED) {
                    state.testingLabel ?: "点击直接连接\n长按智能连接"
                } else {
                    null
                },
                onClick = { runWithTunPermission(controller::startSelectedOrFirstConnection) },
                onLongClick = if (state.vpnState == VpnState.DISCONNECTED) {
                    { runWithTunPermission(controller::startSmartConnection) }
                } else {
                    null
                }
            )
            Spacer(Modifier.weight(1f))
            NodeListEntry(onClick = { controller.navigate(AppScreen.NODE_LIST) })
            Spacer(Modifier.height(14.dp))
        }
    }

    if (showTunAdminDialog) {
        AlertDialog(
            onDismissRequest = { showTunAdminDialog = false },
            title = { Text("TUN 模式需要管理员权限") },
            text = {
                Text(
                    "TUN 模式会接管整机网络，需要以管理员身份运行 Firefly。请关闭程序后右键选择“以管理员身份运行”，再重新连接。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showTunAdminDialog = false }) {
                    Text("知道了")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTunAdminDialog = false
                    controller.setTunEnabled(false)
                }) {
                    Text("切换系统代理")
                }
            }
        )
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
    isTesting: Boolean = false,
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
    val longPressEnabled = onLongClick != null && enabled && !transitioning
    val longPressProgress by animateFloatAsState(
        targetValue = if (pressed && longPressEnabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (pressed) {
                LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
            } else {
                120
            },
            easing = if (pressed) LinearEasing else FastOutSlowInEasing
        ),
        label = "smartConnectionLongPressProgress"
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
                    Box(
                        modifier = Modifier.size(buttonSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = connectionLabel(vpnState),
                            modifier = Modifier
                                .fillMaxSize()
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
                        if (isTesting) {
                            // 智能连接开始后继续显示旋转进度，避免长按进度圈停在满格。
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 5.dp
                            )
                        } else if (longPressEnabled && longPressProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { longPressProgress },
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 5.dp
                            )
                        }
                    }
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
private fun NodeListEntry(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = Primary.copy(alpha = 0.12f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.List,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "节点列表",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "点击选择节点",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "打开节点列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeListScreen(state: DesktopAppState, controller: VpnController) {
    var showQrImport by remember { mutableStateOf(false) }

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
                    ) { showQrImport = true }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state.visibleNodes.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.isLoadingNodes) "正在获取节点..." else "暂无可用节点",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val listScrollScope = rememberCoroutineScope()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    listScrollScope.launch {
                                        listState.scrollBy(-dragAmount)
                                    }
                                }
                            )
                        },
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

    if (showQrImport) {
        DesktopQrImportDialog(
            onDismiss = { showQrImport = false },
            onQrCodeScanned = { text ->
                controller.importNodes(text)
                showQrImport = false
            }
        )
    }
}

private enum class QrImportMode {
    OPTIONS,
    CAMERA,
    SCREENSHOT,
    IMAGE
}

@Composable
private fun DesktopQrImportDialog(
    onDismiss: () -> Unit,
    onQrCodeScanned: (String) -> Unit
) {
    var mode by remember { mutableStateOf(QrImportMode.OPTIONS) }
    when (mode) {
        QrImportMode.OPTIONS -> QrImportOptionsDialog(
            onDismiss = onDismiss,
            onCamera = { mode = QrImportMode.CAMERA },
            onScreenshot = { mode = QrImportMode.SCREENSHOT },
            onImage = { mode = QrImportMode.IMAGE }
        )
        QrImportMode.CAMERA -> DesktopQrScannerDialog(
            onDismiss = onDismiss,
            onQrCodeScanned = onQrCodeScanned
        )
        QrImportMode.SCREENSHOT -> DesktopQrCaptureDialog(
            title = "屏幕截图",
            captureScreen = true,
            onDismiss = { mode = QrImportMode.OPTIONS },
            onQrCodeScanned = onQrCodeScanned
        )
        QrImportMode.IMAGE -> DesktopQrCaptureDialog(
            title = "选择图片",
            captureScreen = false,
            onDismiss = { mode = QrImportMode.OPTIONS },
            onQrCodeScanned = onQrCodeScanned
        )
    }
}

@Composable
private fun QrImportOptionsDialog(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onScreenshot: () -> Unit,
    onImage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("二维码导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("请选择二维码来源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
                    Text("摄像头")
                }
                Button(onClick = onScreenshot, modifier = Modifier.fillMaxWidth()) {
                    Text("屏幕截图")
                }
                Button(onClick = onImage, modifier = Modifier.fillMaxWidth()) {
                    Text("选择图片")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun DesktopQrCaptureDialog(
    title: String,
    captureScreen: Boolean,
    onDismiss: () -> Unit,
    onQrCodeScanned: (String) -> Unit
) {
    var status by remember { mutableStateOf("正在准备…") }
    var busy by remember { mutableStateOf(true) }
    var resultReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            if (captureScreen) {
                DesktopQrCode.captureScreenAndDecode()
            } else {
                DesktopQrCode.chooseImageAndDecode()
            }
        }
        busy = false
        resultReady = true
        status = when {
            !result.selected -> "未选择来源"
            result.text.isNullOrBlank() -> "未识别到二维码，请重试"
            else -> {
                onQrCodeScanned(result.text)
                "识别成功，正在导入…"
            }
        }
    }

    // Hide every Compose dialog while taking a screen capture so the import
    // UI itself cannot cover the QR code the user wants to scan.
    if (!captureScreen || resultReady) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (busy) CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun DesktopQrScannerDialog(
    onDismiss: () -> Unit,
    onQrCodeScanned: (String) -> Unit
) {
    val scanner = remember { DesktopQrScanner() }
    val frames = remember { Channel<BufferedImage>(capacity = Channel.CONFLATED) }
    val uiScope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("正在启动摄像头…") }

    LaunchedEffect(Unit) {
        scanner.start(
            onFrame = { frame -> frames.trySend(frame) },
            onCode = { code -> uiScope.launch { onQrCodeScanned(code) } },
            onError = { error -> uiScope.launch { status = error } }
        )
        try {
            while (isActive) {
                val frame = frames.receive()
                val bitmap = withContext(Dispatchers.Default) { frame.toImageBitmap() }
                if (bitmap != null) {
                    preview = bitmap
                    status = "请将二维码对准摄像头"
                }
            }
        } finally {
            scanner.close()
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("二维码导入", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    preview?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "摄像头画面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } ?: CircularProgressIndicator(color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val groups = state.subscriptionGroups.ifEmpty {
                listOf("" to "订阅节点")
            }
            val segmentCount = groups.size + if (state.settings.hideFavoriteNodeGroup) 0 else 1
            val segmentGap = 4.dp
            val availableWidth = maxWidth - 8.dp - segmentGap * (segmentCount - 1)
            val segmentWidth = if (segmentCount <= 4) {
                (availableWidth / segmentCount).coerceIn(96.dp, 136.dp)
            } else {
                112.dp
            }
            Row(
                Modifier.padding(4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(segmentGap)
            ) {
                groups.forEach { (sourceId, name) ->
                    CategoryOption(
                        name,
                        state.nodeCategory == NodeCategory.SUBSCRIPTION &&
                            (sourceId.isEmpty() || sourceId == state.selectedSubscriptionSourceId),
                        Modifier.width(segmentWidth)
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
                        Modifier.width(segmentWidth)
                    ) { controller.setNodeCategory(NodeCategory.FAVORITES) }
                }
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
private fun NodeFlag(node: DesktopNode, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 30.dp, height = 20.dp)) {
        drawCountryFlag(node.resolvedCountryCode())
    }
}

private fun DrawScope.drawCountryFlag(countryCode: String?) {
    val width = size.width
    val height = size.height
    val code = countryCode?.uppercase()
    val red = Color(0xFFD32F2F)
    val darkRed = Color(0xFFBC002D)
    val blue = Color(0xFF1E4D8F)
    val darkBlue = Color(0xFF1B3F8B)
    val yellow = Color(0xFFFFD54F)
    val white = Color.White

    when (code) {
        "JP" -> {
            drawRect(white)
            drawCircle(darkRed, min(width, height) * 0.30f, Offset(width / 2f, height / 2f))
        }
        "CN", "VN" -> {
            drawRect(red)
            drawStar(Offset(width * 0.28f, height * 0.36f), height * 0.24f, yellow)
        }
        "US" -> {
            drawHorizontalStripes(listOf(red, white, red, white, red, white, red, white, red))
            drawRect(darkBlue, size = Size(width * 0.43f, height * 0.54f))
            repeat(3) { row ->
                repeat(4) { column ->
                    drawCircle(
                        white,
                        height * 0.025f,
                        Offset(width * (0.08f + column * 0.08f), height * (0.10f + row * 0.14f))
                    )
                }
            }
        }
        "DE" -> drawHorizontalStripes(listOf(Color.Black, red, yellow))
        "RU" -> drawHorizontalStripes(listOf(white, blue, red))
        "NL" -> drawHorizontalStripes(listOf(red, white, blue))
        "PL" -> drawHorizontalStripes(listOf(white, red))
        "UA" -> drawHorizontalStripes(listOf(Color(0xFF1976D2), yellow))
        "ES" -> drawHorizontalStripes(listOf(red, yellow, red), listOf(0.25f, 0.5f, 0.25f))
        "FR" -> drawVerticalStripes(listOf(Color(0xFF0055A4), white, red))
        "IT" -> drawVerticalStripes(listOf(Color(0xFF009246), white, Color(0xFFCE2B37)))
        "IE" -> drawVerticalStripes(listOf(Color(0xFF169B62), white, Color(0xFFFF883E)))
        "BE" -> drawVerticalStripes(listOf(Color.Black, yellow, red))
        "RO" -> drawVerticalStripes(listOf(Color(0xFF002B7F), yellow, Color(0xFFCE1126)))
        "CH" -> {
            drawRect(red)
            drawRect(white, topLeft = Offset(width * 0.40f, height * 0.18f), size = Size(width * 0.20f, height * 0.64f))
            drawRect(white, topLeft = Offset(width * 0.23f, height * 0.40f), size = Size(width * 0.54f, height * 0.20f))
        }
        "SE" -> drawScandinavianCross(Color(0xFF006AA7), yellow, vertical = width * 0.36f, horizontal = height * 0.48f)
        "DK" -> drawScandinavianCross(red, white, vertical = width * 0.36f, horizontal = height * 0.48f)
        "NO" -> {
            drawRect(red)
            drawRect(white, topLeft = Offset(width * 0.32f, 0f), size = Size(width * 0.18f, height))
            drawRect(white, topLeft = Offset(0f, height * 0.39f), size = Size(width, height * 0.22f))
            drawRect(blue, topLeft = Offset(width * 0.36f, 0f), size = Size(width * 0.10f, height))
            drawRect(blue, topLeft = Offset(0f, height * 0.44f), size = Size(width, height * 0.12f))
        }
        "FI" -> drawScandinavianCross(white, Color(0xFF003580), vertical = width * 0.36f, horizontal = height * 0.48f)
        "GB" -> drawUnitedKingdomFlag()
        "CA" -> {
            drawVerticalStripes(listOf(red, white, red), listOf(0.25f, 0.50f, 0.25f))
            drawStar(Offset(width / 2f, height / 2f), height * 0.27f, red)
        }
        "IN" -> {
            drawHorizontalStripes(listOf(Color(0xFFFF9933), white, Color(0xFF138808)))
            drawCircle(Color(0xFF000080), height * 0.18f, Offset(width / 2f, height / 2f), style = Stroke(1f))
            drawLine(Color(0xFF000080), Offset(width / 2f, height * 0.30f), Offset(width / 2f, height * 0.70f), 1f)
            drawLine(Color(0xFF000080), Offset(width * 0.40f, height / 2f), Offset(width * 0.60f, height / 2f), 1f)
        }
        "SG" -> {
            drawHorizontalStripes(listOf(red, white))
            drawCircle(white, height * 0.26f, Offset(width * 0.23f, height * 0.25f))
            drawCircle(red, height * 0.22f, Offset(width * 0.29f, height * 0.25f))
        }
        "HK" -> {
            drawRect(red)
            drawCircle(white, height * 0.25f, Offset(width / 2f, height / 2f), style = Stroke(2f))
            drawCircle(white, height * 0.07f, Offset(width / 2f, height / 2f))
        }
        "TW" -> {
            drawRect(red)
            drawRect(darkBlue, size = Size(width * 0.50f, height * 0.58f))
            drawCircle(white, height * 0.17f, Offset(width * 0.25f, height * 0.29f))
        }
        "KR" -> {
            drawRect(white)
            drawCircle(Color(0xFFCD2E3A), height * 0.25f, Offset(width / 2f, height * 0.43f))
            drawCircle(Color(0xFF0047A0), height * 0.25f, Offset(width / 2f, height * 0.57f))
        }
        "TR" -> {
            drawRect(red)
            drawCircle(white, height * 0.27f, Offset(width * 0.43f, height / 2f))
            drawCircle(red, height * 0.22f, Offset(width * 0.49f, height / 2f))
            drawStar(Offset(width * 0.66f, height / 2f), height * 0.14f, white)
        }
        "BR" -> {
            drawRect(Color(0xFF009B3A))
            drawDiamond(yellow)
            drawCircle(darkBlue, height * 0.25f, Offset(width / 2f, height / 2f))
        }
        "MX" -> drawVerticalStripes(listOf(Color(0xFF006847), white, Color(0xFFCE1126)))
        "AR" -> drawHorizontalStripes(listOf(Color(0xFF74ACDF), white, Color(0xFF74ACDF)))
        "CL" -> {
            drawHorizontalStripes(listOf(white, red), listOf(0.50f, 0.50f))
            drawRect(darkBlue, size = Size(width * 0.42f, height * 0.50f))
            drawStar(Offset(width * 0.21f, height * 0.25f), height * 0.13f, white)
        }
        "CO" -> drawHorizontalStripes(listOf(yellow, Color(0xFF003893), red), listOf(0.50f, 0.25f, 0.25f))
        "ID", "MC" -> drawHorizontalStripes(listOf(red, white))
        "AU", "NZ" -> {
            drawRect(darkBlue)
            drawStar(Offset(width * 0.72f, height * 0.30f), height * 0.12f, white)
            drawStar(Offset(width * 0.78f, height * 0.68f), height * 0.08f, white)
            drawStar(Offset(width * 0.58f, height * 0.73f), height * 0.07f, white)
        }
        else -> drawFallbackFlag()
    }
    drawRect(Color.Black.copy(alpha = 0.18f), style = Stroke(width = 1f))
}

private fun DrawScope.drawHorizontalStripes(colors: List<Color>, proportions: List<Float>? = null) {
    val stripeProportions = proportions ?: List(colors.size) { 1f / colors.size }
    var top = 0f
    colors.forEachIndexed { index, color ->
        val stripeHeight = size.height * stripeProportions[index]
        drawRect(color, topLeft = Offset(0f, top), size = Size(size.width, stripeHeight + 1f))
        top += stripeHeight
    }
}

private fun DrawScope.drawVerticalStripes(colors: List<Color>, proportions: List<Float>? = null) {
    val stripeProportions = proportions ?: List(colors.size) { 1f / colors.size }
    var left = 0f
    colors.forEachIndexed { index, color ->
        val stripeWidth = size.width * stripeProportions[index]
        drawRect(color, topLeft = Offset(left, 0f), size = Size(stripeWidth + 1f, size.height))
        left += stripeWidth
    }
}

private fun DrawScope.drawScandinavianCross(background: Color, cross: Color, vertical: Float, horizontal: Float) {
    drawRect(background)
    drawRect(cross, topLeft = Offset(vertical - size.width * 0.07f, 0f), size = Size(size.width * 0.14f, size.height))
    drawRect(cross, topLeft = Offset(0f, horizontal - size.height * 0.12f), size = Size(size.width, size.height * 0.24f))
}

private fun DrawScope.drawUnitedKingdomFlag() {
    drawRect(Color(0xFF012169))
    drawRect(Color.White, topLeft = Offset(size.width * 0.42f, 0f), size = Size(size.width * 0.16f, size.height))
    drawRect(Color.White, topLeft = Offset(0f, size.height * 0.38f), size = Size(size.width, size.height * 0.24f))
    drawRect(Color(0xFFC8102E), topLeft = Offset(size.width * 0.46f, 0f), size = Size(size.width * 0.08f, size.height))
    drawRect(Color(0xFFC8102E), topLeft = Offset(0f, size.height * 0.44f), size = Size(size.width, size.height * 0.12f))
}

private fun DrawScope.drawDiamond(color: Color) {
    val path = Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height / 2f)
        lineTo(size.width / 2f, size.height)
        lineTo(0f, size.height / 2f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStar(center: Offset, outerRadius: Float, color: Color) {
    val path = Path()
    repeat(10) { index ->
        val angle = -PI / 2.0 + index * PI / 5.0
        val radius = if (index % 2 == 0) outerRadius else outerRadius * 0.42f
        val point = Offset(
            center.x + cos(angle).toFloat() * radius,
            center.y + sin(angle).toFloat() * radius
        )
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawFallbackFlag() {
    val center = Offset(size.width / 2f, size.height / 2f)
    drawRect(Color(0xFFE2E8F0))
    drawCircle(Color(0xFF64748B), size.height * 0.34f, center, style = Stroke(width = 1.2f))
    drawLine(Color(0xFF64748B), Offset(center.x, center.y - size.height * 0.34f), Offset(center.x, center.y + size.height * 0.34f), 1f)
    drawLine(Color(0xFF64748B), Offset(center.x - size.width * 0.34f, center.y), Offset(center.x + size.width * 0.34f, center.y), 1f)
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
                NodeFlag(node, Modifier.padding(end = 12.dp))
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
                DrawerItem(Icons.Outlined.Star, "特色功能") {
                    controller.navigate(AppScreen.FEATURES)
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
    var showBackendDialog by remember { mutableStateOf(false) }
    var backendUrlDraft by remember(state.backendUrl) { mutableStateOf(state.backendUrl) }
    var iconClickCount by remember { mutableStateOf(0) }
    var lastIconClickAt by remember { mutableStateOf(0L) }
    val appIcon = remember { loadBitmapResource("firefly.png") }

    LaunchedEffect(screen) {
        if (screen != AppScreen.ABOUT) showBackendDialog = false
    }

    fun handleAppIconClick() {
        val now = System.currentTimeMillis()
        iconClickCount = if (lastIconClickAt > 0L && now - lastIconClickAt <= 1_500L) {
            iconClickCount + 1
        } else {
            1
        }
        lastIconClickAt = now
        if (iconClickCount >= 5) {
            iconClickCount = 0
            backendUrlDraft = state.backendUrl
            showBackendDialog = true
        }
    }

    val title = when (screen) {
        AppScreen.ACCOUNT_SETTINGS -> "账户设置"
        AppScreen.BASIC_SETTINGS -> "基础设定"
        AppScreen.FEATURES -> "特色功能"
        AppScreen.ABOUT -> "关于应用"
        else -> "Firefly"
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
                AppScreen.FEATURES -> {
                    FeatureSettingsContent(
                        settings = state.settings,
                        protocolCoreSettings = state.protocolCoreSettings,
                        xrayCoreStatus = state.xrayCoreStatus,
                        isAdapting = state.isHysteriaBandwidthAdapting,
                        onAdapt = controller::adaptHysteriaBandwidth,
                        onProtocolCoreChange = controller::setProtocolCore,
                        onSettingsChange = controller::updateSettings
                    )
                }
                AppScreen.ABOUT -> {
                    appIcon?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Firefly 应用图标",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(88.dp)
                                .align(Alignment.CenterHorizontally)
                                .clickable(onClick = ::handleAppIconClick)
                        )
                    }
                    Text("Firefly", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Primary)
                    Text("Windows 原生客户端 3.0.4", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val updateUrl = state.backendSettings.githubUrl.ifBlank {
                        state.backendSettings.websiteUrl
                    }
                    val hasFeedbackAction = state.backendSettings.feedbackEmail.isNotBlank() ||
                        state.backendSettings.feedbackUrl.isNotBlank()
                    if (
                        updateUrl.isNotBlank() ||
                        state.backendSettings.websiteUrl.isNotBlank() ||
                        hasFeedbackAction ||
                        state.backendSettings.githubUrl.isNotBlank() ||
                        state.backendSettings.telegramUrl.isNotBlank()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (updateUrl.isNotBlank()) {
                                AboutIconAction(Icons.Outlined.SystemUpdate, "检查更新") {
                                    // Windows 暂无独立更新包，先打开项目发布页检查新版本。
                                    browse(updateUrl)
                                }
                            }
                            if (state.backendSettings.websiteUrl.isNotBlank()) {
                                AboutIconAction(Icons.Default.Language, "官网") {
                                    browse(state.backendSettings.websiteUrl)
                                }
                            }
                            if (hasFeedbackAction) {
                                AboutIconAction(Icons.Outlined.Email, "邮箱") {
                                    if (state.backendSettings.feedbackEmail.isNotBlank()) {
                                        writeClipboard(state.backendSettings.feedbackEmail)
                                    }
                                    if (state.backendSettings.feedbackUrl.isNotBlank()) {
                                        browse(state.backendSettings.feedbackUrl)
                                    }
                                }
                            }
                            if (state.backendSettings.githubUrl.isNotBlank()) {
                                AboutIconAction(Icons.Outlined.Code, "GitHub") {
                                    browse(state.backendSettings.githubUrl)
                                }
                            }
                            if (state.backendSettings.telegramUrl.isNotBlank()) {
                                AboutIconAction(Icons.AutoMirrored.Outlined.Send, "Telegram") {
                                    browse(state.backendSettings.telegramUrl)
                                }
                            }
                        }
                    }
                    InfoCard("sing-box", coreLabel(state.coreStatus))
                    InfoCard("Xray", coreLabel(state.xrayCoreStatus, "Xray"))
                    InfoCard(
                        "后台",
                        if (state.backendConnected) {
                            "已连接，共 ${state.subscriptionSourceCount} 条可用订阅"
                        } else {
                            "未连接"
                        }
                    )
                }
                else -> Unit
            }
        }
    }

    if (screen == AppScreen.ABOUT && showBackendDialog) {
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
private fun AboutIconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        AccountInfoRow("客户端版本", account.appVersion.ifBlank { "3.0.4" })
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
    UrlTestUrlSetting(settings, onSettingsChange)
    SettingSection("测试超时") {
        NumberSettingField("TCPing 超时", settings.tcpingTimeoutMs) {
            onSettingsChange(settings.copy(tcpingTimeoutMs = it.coerceAtLeast(500)))
        }
        NumberSettingField("URL Test 超时", settings.urlTestTimeoutMs) {
            onSettingsChange(settings.copy(urlTestTimeoutMs = it.coerceAtLeast(500)))
        }
        NumberSettingField("带宽测试时长（毫秒）", settings.speedTestDurationMs) {
            onSettingsChange(settings.copy(speedTestDurationMs = it.coerceAtLeast(1_000)))
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
private fun UrlTestUrlSetting(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit
) {
    var input by remember(settings.urlTestUrl) { mutableStateOf(settings.urlTestUrl) }
    var showPresets by remember { mutableStateOf(false) }
    var showBandwidthPresets by remember { mutableStateOf(false) }
    val normalizedUrl = UrlTestTargets.normalize(input)

    SettingSection("204 测速地址") {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义 URL") },
            singleLine = true,
            isError = normalizedUrl == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        if (normalizedUrl == null) {
            Text(
                "请输入有效的 HTTP 或 HTTPS 地址",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { showPresets = true }) { Text("选择预设") }
                DropdownMenu(
                    expanded = showPresets,
                    onDismissRequest = { showPresets = false }
                ) {
                    UrlTestTargets.PRESETS.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.name)
                                    Text(
                                        preset.url,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                input = preset.url
                                onSettingsChange(settings.copy(urlTestUrl = preset.url))
                                showPresets = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    normalizedUrl?.let { onSettingsChange(settings.copy(urlTestUrl = it)) }
                },
                enabled = normalizedUrl != null && normalizedUrl != settings.urlTestUrl
            ) {
                Text("保存")
            }
        }
        Text(
            "当前使用：${settings.urlTestUrl}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val bandwidthUrlInputState = remember(settings.speedTestDownloadUrl) {
            mutableStateOf(settings.speedTestDownloadUrl)
        }
        val bandwidthUrlInput = bandwidthUrlInputState.value
        val normalizedBandwidthUrl = BandwidthTestTargets.normalize(bandwidthUrlInput)
        Text(
            "带宽测速地址",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedTextField(
            value = bandwidthUrlInput,
            onValueChange = { bandwidthUrlInputState.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义 URL") },
            singleLine = true,
            isError = normalizedBandwidthUrl == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        if (normalizedBandwidthUrl == null) {
            Text(
                "请输入有效的 HTTP 或 HTTPS 地址",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { showBandwidthPresets = true }) { Text("选择预设") }
                DropdownMenu(
                    expanded = showBandwidthPresets,
                    onDismissRequest = { showBandwidthPresets = false }
                ) {
                    BandwidthTestTargets.PRESETS.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.name)
                                    Text(
                                        preset.url,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                bandwidthUrlInputState.value = preset.url
                                onSettingsChange(settings.copy(speedTestDownloadUrl = preset.url))
                                showBandwidthPresets = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    normalizedBandwidthUrl?.let {
                        onSettingsChange(settings.copy(speedTestDownloadUrl = it))
                    }
                },
                enabled = normalizedBandwidthUrl != null && normalizedBandwidthUrl != settings.speedTestDownloadUrl
            ) {
                Text("保存")
            }
        }
        Text(
            "当前使用：${settings.speedTestDownloadUrl}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "预设来自不同服务商，实际可用性和速度可能因地区而异，也可以手动填写完整 URL。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureSettingsContent(
    settings: DesktopSettings,
    protocolCoreSettings: ProtocolCoreSettings,
    xrayCoreStatus: CoreStatus,
    isAdapting: Boolean,
    onAdapt: () -> Unit,
    onProtocolCoreChange: (NodeType, ProxyCore) -> Unit,
    onSettingsChange: (DesktopSettings) -> Unit
) {
    SettingSection("协议核心") {
        Text(
            "全部协议均在下方展示；当前有 8 种可选 Xray。灰色 Xray 表示核心不支持；SOCKS 的 Xray 选项仅适用于 SOCKS5。修改后会自动重连。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
            ProtocolCoreRow(
                label = nodeType.displayName(),
                selectedCore = protocolCoreSettings.coreFor(nodeType),
                xrayAvailable = nodeType in ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS,
                onSelected = { core -> onProtocolCoreChange(nodeType, core) }
            )
        }
        Text(
            coreLabel(xrayCoreStatus, "Xray"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
    SettingSection("智能连接") {
        NumberSettingField("TCPing 节点比例 (%)", settings.smartConnectTcpingPercent) {
            onSettingsChange(settings.copy(smartConnectTcpingPercent = it.coerceIn(1, 100)))
        }
        NumberSettingField("URL Test 候选比例 (%)", settings.smartConnectUrlTestPercent) {
            onSettingsChange(settings.copy(smartConnectUrlTestPercent = it.coerceIn(1, 100)))
        }
        NumberSettingField("带宽测速候选比例 (%)", settings.smartConnectSpeedTestPercent) {
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
private fun ProtocolCoreRow(
    label: String,
    selectedCore: ProxyCore,
    xrayAvailable: Boolean,
    onSelected: (ProxyCore) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        FilterChip(
            selected = selectedCore == ProxyCore.SING_BOX,
            onClick = { onSelected(ProxyCore.SING_BOX) },
            label = { Text("sing-box") }
        )
        FilterChip(
            selected = selectedCore == ProxyCore.XRAY,
            onClick = { onSelected(ProxyCore.XRAY) },
            enabled = xrayAvailable,
            label = { Text("Xray") }
        )
    }
}

private fun NodeType.displayName(): String = when (this) {
    NodeType.VLESS -> "VLESS"
    NodeType.VMESS -> "VMess"
    NodeType.TROJAN -> "Trojan"
    NodeType.HYSTERIA2 -> "Hysteria2"
    NodeType.ANYTLS -> "AnyTLS"
    NodeType.TUIC -> "TUIC"
    NodeType.NAIVE -> "Naive"
    NodeType.WIREGUARD -> "WireGuard"
    NodeType.SHADOWSOCKS -> "Shadowsocks"
    NodeType.SOCKS -> "SOCKS"
    NodeType.HTTP -> "HTTP"
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

private fun coreLabel(status: CoreStatus, coreName: String = "sing-box"): String = when (status) {
    CoreStatus.Checking -> "正在检查 $coreName"
    CoreStatus.Missing -> "$coreName 未就绪"
    is CoreStatus.Available -> "$coreName ${status.version}"
    is CoreStatus.Failed -> "$coreName 异常"
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

private fun BufferedImage.toImageBitmap(): ImageBitmap? = runCatching {
    ByteArrayOutputStream().use { output ->
        ImageIO.write(this, "png", output)
        SkiaImage.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
    }
}.getOrNull()

private fun writeClipboard(value: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
}

/** Windows 的 fltmc 需要管理员权限，适合在启动 TUN 前做轻量权限检查。 */
private fun isWindowsElevated(): Boolean {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return true
    val process = runCatching {
        ProcessBuilder("fltmc.exe").redirectErrorStream(true).start()
    }.getOrNull() ?: return false
    return runCatching {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.inputStream.close()
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}

private fun loadBitmapResource(name: String): ImageBitmap? = runCatching {
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(name)) {
        "Missing resource: $name"
    }.use { input ->
        SkiaImage.makeFromEncoded(input.readBytes()).toComposeImageBitmap()
    }
}.getOrNull()
