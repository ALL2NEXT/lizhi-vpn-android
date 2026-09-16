package xyz.a202132.app.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.viewmodel.AppInfo
import xyz.a202132.app.viewmodel.PerAppProxyViewModel

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val APP_ICON_CACHE_SIZE_KB = 4 * 1024
private val appIconCache = object : LruCache<String, Bitmap>(APP_ICON_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}
private val appIconLoadSemaphore = Semaphore(permits = 2)

/**
 * 分应用代理设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScopeScreen(
    onBack: (Boolean) -> Unit,
    viewModel: PerAppProxyViewModel = viewModel()
) {
    // 监听系统返回键 (包括手势返回)
    // 返回是否发生了更改，以便主界面决定是否重启 VPN
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    BackHandler { onBack(hasChanges) }

    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val selectedPackages by viewModel.selectedPackages.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isAutoDetecting by viewModel.isAutoDetecting.collectAsStateWithLifecycle()
    val selectedCount by viewModel.selectedCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showSystemApps by viewModel.showSystemApps.collectAsStateWithLifecycle()
    // 监听生命周期，在 onResume 时刷新应用列表 (解决用户授权后返回不刷新问题)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var hasHandledInitialResume by remember(lifecycleOwner) { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (hasHandledInitialResume) {
                    viewModel.refreshApps()
                } else {
                    // ViewModel 初始化时已经加载，跳过观察者注册产生的首次 ON_RESUME。
                    hasHandledInitialResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 退出时清空搜索框
            viewModel.searchQuery.value = ""
        }
    }
    
    // 权限拒绝状态 (针对定制 ROM)
    val isPermissionDenied by viewModel.isPermissionDenied.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.autoDetectResult.collect { newlySelectedCount ->
            val message = if (newlySelectedCount > 0) {
                "自动识别完成，新增 $newlySelectedCount 个应用"
            } else {
                "未发现新的代理应用"
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分应用代理")
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onBack(viewModel.hasChanges.value) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 权限提示卡片 (仅当检测到权限受限时显示)
            if (isPermissionDenied) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "无法获取应用列表",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "您的系统可能限制了读取应用列表权限。请前往设置授予权限，或手动搜索应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // ignore
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("前往授权")
                        }
                    }
                }
            }

            // 过滤选项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 显示系统应用
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.showSystemApps.value = !showSystemApps }
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { viewModel.showSystemApps.value = it },
                        enabled = isEnabled,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = "显示系统应用",
                        fontSize = 14.sp,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 已选择数量
                Text(
                    text = "已选择 $selectedCount 个应用",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { viewModel.autoSelectProxyApps() },
                    enabled = !isLoading && !isAutoDetecting
                ) {
                    Text(if (isAutoDetecting) "识别中..." else "自动识别")
                }
            }
            
            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索应用...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                enabled = isEnabled,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            
            // 滚动状态
            val listState = rememberLazyListState()
            var loadNewIcons by remember { mutableStateOf(true) }

            // 快速滚动时优先保证手势帧；停稳后再补齐新进入视口的图标。
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress }
                    .collectLatest { isScrolling ->
                        if (isScrolling) {
                            loadNewIcons = false
                        } else {
                            delay(300)
                            loadNewIcons = true
                        }
                    }
            }
            
            // 监听滚动到顶部事件
            LaunchedEffect(Unit) {
                viewModel.scrollToTopEvent.collect {
                    listState.animateScrollToItem(0)
                }
            }
            
            // 应用列表
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { it.packageName }
                    ) { app ->
                        AppListItem(
                            app = app,
                            isSelected = app.packageName in selectedPackages,
                            enabled = isEnabled,
                            loadIcon = loadNewIcons,
                            onToggle = { viewModel.togglePackage(app.packageName) }
                        )
                    }
                }
            }
            
        }

    }
}

/**
 * 应用列表项
 */
@Composable
private fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    enabled: Boolean,
    loadIcon: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onToggle() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected && enabled) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (isSelected && enabled) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            AppIcon(
                packageName = app.packageName,
                appName = app.appName,
                loadEnabled = loadIcon
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 应用信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.appName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface 
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // 选中状态
            if (isSelected && enabled) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

/**
 * 只为 LazyColumn 当前可见的条目加载图标，并使用有上限的位图缓存。
 */
@Composable
private fun AppIcon(
    packageName: String,
    appName: String,
    loadEnabled: Boolean
) {
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val cacheKey = "$packageName@$iconSizePx"
    val bitmap by produceState<Bitmap?>(
        initialValue = appIconCache.get(cacheKey),
        key1 = cacheKey,
        key2 = loadEnabled
    ) {
        if (value == null && loadEnabled) {
            value = withContext(Dispatchers.IO) {
                appIconLoadSemaphore.withPermit {
                    appIconCache.get(cacheKey) ?: runCatching {
                        context.packageManager
                            .getApplicationIcon(packageName)
                            .toBitmap(
                                width = iconSizePx,
                                height = iconSizePx,
                                config = Bitmap.Config.ARGB_8888
                            )
                    }.getOrNull()?.also { appIconCache.put(cacheKey, it) }
                }
            }
        }
    }
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = appName,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
