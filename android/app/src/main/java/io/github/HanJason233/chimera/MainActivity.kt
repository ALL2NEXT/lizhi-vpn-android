package io.github.HanJason233.chimera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.PersonalSourceUserAgentMode
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.feature.nodegrouping.ui.NodeGroupingDestination
import io.github.HanJason233.chimera.feature.nodegrouping.ui.NodeGroupingDetailRequest
import io.github.HanJason233.chimera.feature.perappinbound.ui.PerAppInboundScreen
import io.github.HanJason233.chimera.feature.perappinbound.ui.PerAppInboundViewModel
import io.github.HanJason233.chimera.ui.components.HomeNavigationLayout
import io.github.HanJason233.chimera.ui.components.HomeDestination
import io.github.HanJason233.chimera.ui.navigation.AppRoute
import io.github.HanJason233.chimera.ui.screens.AccountSettingsScreen
import io.github.HanJason233.chimera.ui.screens.AccountSettingsPage
import io.github.HanJason233.chimera.ui.screens.MainScreen
import io.github.HanJason233.chimera.ui.screens.OtherConfigScreen
import io.github.HanJason233.chimera.ui.screens.InboundScreen
import io.github.HanJason233.chimera.ui.screens.OutboundScreen
import io.github.HanJason233.chimera.ui.screens.CoreRoutingScreen
import io.github.HanJason233.chimera.ui.screens.SplitRoutingSettingsScreen
import io.github.HanJason233.chimera.ui.screens.RoutingAssetsScreen
import io.github.HanJason233.chimera.ui.screens.QrScannerScreen
import io.github.HanJason233.chimera.ui.screens.SkinScreen
import io.github.HanJason233.chimera.ui.screens.SkinSchemeEditorScreen
import io.github.HanJason233.chimera.ui.screens.StartupAccountScreen
import io.github.HanJason233.chimera.ui.screens.SpeedTestSchemeEditorScreen
import io.github.HanJason233.chimera.ui.screens.SpeedTestSchemesScreen
import io.github.HanJason233.chimera.ui.theme.ChimeraTheme
import io.github.HanJason233.chimera.platform.LauncherIdentityManager
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import io.github.HanJason233.chimera.viewmodel.RoutingAssetsViewModel

class MainActivity : ComponentActivity() {

    private var pendingVpnAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnAction?.invoke()
        } else {
            Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        pendingVpnAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> continueVpnPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.initialize(this)
        configureEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = viewModel()
            val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
            val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
            val startupAccountAuthenticated by
                viewModel.startupAccountAuthenticated.collectAsStateWithLifecycle()
            val isStartupAccountActionInProgress by
                viewModel.isStartupAccountActionInProgress.collectAsStateWithLifecycle()
            val accountMessage by viewModel.accountMessage.collectAsStateWithLifecycle()
            LaunchedEffect(skinSettings.activeScheme.appIconPresetId) {
                LauncherIdentityManager.apply(
                    this@MainActivity,
                    skinSettings.activeScheme.appIconPresetId
                )
            }
            ChimeraTheme(skinSettings = skinSettings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var scannedSubscriptionText by rememberSaveable { mutableStateOf<String?>(null) }
                    var scannedRoutingRulesText by rememberSaveable { mutableStateOf<String?>(null) }
                    var scannedRoutingAssetUrl by rememberSaveable { mutableStateOf<String?>(null) }
                    var routingQrScanTarget by rememberSaveable { mutableStateOf(false) }
                    var routingAssetQrScanTarget by rememberSaveable { mutableStateOf(false) }
                    val navigateTo: (String) -> Unit = { route ->
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        }
                        val createSubscriptionGroup: (String?, (Boolean) -> Unit) -> Unit =
                            { input, onComplete ->
                                viewModel.createSubscriptionGroup(input) { result, importedCount, createdGroupCount, failedGroupCount ->
                                    val message = when (result) {
                                        MainViewModel.SubscriptionGroupCreateResult.CREATED ->
                                            if (importedCount > 0) {
                                                "已添加订阅组，导入${importedCount}个节点"
                                            } else {
                                                "订阅组已创建"
                                            }
                                        MainViewModel.SubscriptionGroupCreateResult.MULTIPLE_CREATED ->
                                            "已分别创建${createdGroupCount}个私人源，共导入${importedCount}个节点"
                                        MainViewModel.SubscriptionGroupCreateResult.PARTIAL_CREATED ->
                                            "成功创建${createdGroupCount}个私人源，${failedGroupCount}个来源未能导入，共导入${importedCount}个节点"
                                        MainViewModel.SubscriptionGroupCreateResult.INVALID ->
                                            "未找到可导入节点或链接无效"
                                        MainViewModel.SubscriptionGroupCreateResult.FETCH_FAILED ->
                                            "订阅链接获取失败，请检查地址和网络"
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                    onComplete(
                                        result == MainViewModel.SubscriptionGroupCreateResult.CREATED ||
                                            result == MainViewModel.SubscriptionGroupCreateResult.MULTIPLE_CREATED ||
                                            result == MainViewModel.SubscriptionGroupCreateResult.PARTIAL_CREATED
                                    )
                                }
                            }
                        val updateSubscriptionGroup: (
                            String,
                            String,
                            String?,
                            PersonalSourceUserAgentMode,
                            String?,
                            (Boolean) -> Unit
                        ) -> Unit =
                            { groupId, name, input, userAgentMode, customUserAgent, onComplete ->
                                viewModel.updateSubscriptionGroup(
                                    groupId,
                                    name,
                                    input,
                                    userAgentMode,
                                    customUserAgent
                                ) { result, importedCount ->
                                    val message = when (result) {
                                        MainViewModel.SubscriptionGroupEditResult.UPDATED ->
                                            if (importedCount > 0) {
                                                "订阅组已更新，导入${importedCount}个节点"
                                            } else {
                                                "订阅组已更新"
                                            }
                                        MainViewModel.SubscriptionGroupEditResult.INVALID ->
                                            "未找到可导入节点或链接无效"
                                        MainViewModel.SubscriptionGroupEditResult.FETCH_FAILED ->
                                            "订阅链接获取失败，请检查地址和网络"
                                        MainViewModel.SubscriptionGroupEditResult.NOT_FOUND ->
                                            "订阅组不存在"
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                    onComplete(
                                        result == MainViewModel.SubscriptionGroupEditResult.UPDATED
                                    )
                                }
                            }
                        val deleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit =
                            { groupId, onComplete ->
                                viewModel.deleteSubscriptionGroup(groupId) { success ->
                                    if (!success) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "订阅组不存在",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "订阅组已删除",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    onComplete(success)
                                }
                            }
                        // 完成启动认证后再创建主导航，未登录时单独显示账号界面。
                        if (startupAccountAuthenticated) NavHost(
                            navController = navController,
                            startDestination = AppRoute.MAIN,
                            enterTransition = {
                                fadeIn(
                                    animationSpec = tween(durationMillis = 160, delayMillis = 30)
                                ) + slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 240,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetX = { width -> width / 12 }
                                )
                            },
                            exitTransition = {
                                fadeOut(animationSpec = tween(durationMillis = 140))
                            },
                            popEnterTransition = {
                                fadeIn(
                                    animationSpec = tween(durationMillis = 160, delayMillis = 30)
                                ) + slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetX = { width -> -width / 12 }
                                )
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(durationMillis = 140)) +
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            durationMillis = 220,
                                            easing = FastOutSlowInEasing
                                        ),
                                        targetOffsetX = { width -> width / 12 }
                                    )
                            }
                        ) {
                            composable(
                                route = AppRoute.MAIN,
                                popEnterTransition = {
                                    fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 220,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                }
                            ) {
                                var selectedDestination by rememberSaveable {
                                    mutableStateOf(HomeDestination.HOME)
                                }
                                val destinationStateHolder = rememberSaveableStateHolder()
                                var selectedGroupDetailRequest by remember {
                                    mutableStateOf<NodeGroupingDetailRequest?>(null)
                                }
                                val onBottomNavigationDestinationSelected:
                                    (HomeDestination) -> Unit = { destination ->
                                        // 浏览分组页面不改写用户实际选中的节点组。
                                        selectedDestination = destination
                                    }
                                destinationStateHolder.SaveableStateProvider(
                                    key = selectedDestination.name
                                ) {
                                when (selectedDestination) {
                                    HomeDestination.HOME -> MainScreen(
                                        viewModel = viewModel,
                                        onStartVpn = { action ->
                                            requestVpnPermission(action)
                                        },
                                        onOpenOtherConfig = {
                                            navigateTo(AppRoute.OTHER_CONFIG)
                                        },
                                        onOpenSplitRoutingSettings = {
                                            navigateTo(AppRoute.SPLIT_ROUTING_SETTINGS)
                                        },
                                        onOpenInbound = {
                                            navigateTo(AppRoute.INBOUND)
                                        },
                                        onOpenOutbound = {
                                            navigateTo(AppRoute.OUTBOUND)
                                        },
                                        onOpenSpeedTestSchemes = {
                                            navigateTo(AppRoute.SPEED_TEST_SCHEMES)
                                        },
                                        onOpenSkin = { navigateTo(AppRoute.SKIN) },
                                        onOpenCurrentGroupDetails = { groupKey, groupName ->
                                            if (groupKey == null) {
                                                selectedGroupDetailRequest = null
                                                selectedDestination = HomeDestination.SUBSCRIPTION_GROUPING
                                            } else {
                                                selectedGroupDetailRequest = NodeGroupingDetailRequest(
                                                    groupKey = groupKey,
                                                    groupName = groupName
                                                )
                                                selectedDestination = when (groupKey) {
                                                    is NodeGroupKey.Country -> HomeDestination.NODE_GROUPING
                                                    is NodeGroupKey.Subscription,
                                                    is NodeGroupKey.Imported -> HomeDestination.SUBSCRIPTION_GROUPING
                                                }
                                            }
                                        },
                                        navigationStyle = skinSettings.activeScheme.navigationStyle,
                                        bottomNavigationDestination =
                                            selectedDestination,
                                        onBottomNavigationDestinationSelected =
                                            onBottomNavigationDestinationSelected
                                    )

                                    HomeDestination.NODE_GROUPING,
                                    HomeDestination.SUBSCRIPTION_GROUPING -> {
                                        val groupingMode =
                                            if (selectedDestination == HomeDestination.NODE_GROUPING) {
                                                NodeGroupingMode.REGION
                                            } else {
                                                NodeGroupingMode.WAREHOUSE
                                            }
                                        HomeNavigationLayout(
                                            selectedDestination = selectedDestination,
                                            onDestinationSelected =
                                                onBottomNavigationDestinationSelected,
                                            navigationStyle = skinSettings.activeScheme.navigationStyle
                                        ) {
                                            NodeGroupingDestination(
                                                groupingMode = groupingMode,
                                                viewModel = viewModel,
                                                onCountrySelected = { countryCode ->
                                                    viewModel.selectCountry(countryCode)
                                                },
                                                onNodeSelected = { node ->
                                                    selectedDestination = HomeDestination.HOME
                                                    requestVpnPermission {
                                                        viewModel.selectAndConnectNode(node)
                                                    }
                                                },
                                                onCreateSubscriptionGroup = { input, onComplete ->
                                                    createSubscriptionGroup(input, onComplete)
                                                },
                                                onUpdateSubscriptionGroup = {
                                                        groupId,
                                                        name,
                                                        input,
                                                        userAgentMode,
                                                        customUserAgent,
                                                        onComplete ->
                                                    updateSubscriptionGroup(
                                                        groupId,
                                                        name,
                                                        input,
                                                        userAgentMode,
                                                        customUserAgent,
                                                        onComplete
                                                    )
                                                },
                                                onDeleteSubscriptionGroup = { groupId, onComplete ->
                                                    deleteSubscriptionGroup(groupId, onComplete)
                                                },
                                                onScanQrCode = {
                                                    routingQrScanTarget = false
                                                    navigateTo(AppRoute.QR_SCANNER)
                                                },
                                                scannedSubscriptionText = scannedSubscriptionText,
                                                onScannedSubscriptionTextConsumed = {
                                                    scannedSubscriptionText = null
                                                },
                                                onBack = {
                                                    selectedDestination = HomeDestination.HOME
                                                },
                                                bottomContentPadding = 16.dp,
                                                detailRequest = selectedGroupDetailRequest,
                                                onDetailRequestConsumed = { request ->
                                                    if (selectedGroupDetailRequest == request) {
                                                        selectedGroupDetailRequest = null
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    HomeDestination.NAVIGATION -> {
                                        // Old saved destination IDs are redirected to Home.
                                        selectedDestination = HomeDestination.HOME
                                    }

                                    HomeDestination.MY ->
                                        HomeNavigationLayout(
                                            selectedDestination = selectedDestination,
                                            onDestinationSelected =
                                                onBottomNavigationDestinationSelected,
                                            navigationStyle = skinSettings.activeScheme.navigationStyle
                                        ) {
                                            // “我的”总览直接作为底部标签页内容展示。
                                            AccountSettingsScreen(
                                                viewModel = viewModel,
                                                onBack = {
                                                    selectedDestination = HomeDestination.HOME
                                                },
                                                onAccountDeleted = { finishAndRemoveTask() },
                                                page = AccountSettingsPage.OVERVIEW,
                                                onOpenPage = { page ->
                                                    navigateTo(AppRoute.accountSettingsDetail(page.routeKey))
                                                }
                                            )
                                        }
                                }
                                }
                            }

                            composable(AppRoute.QR_SCANNER) {
                                QrScannerScreen(
                                    onBack = {
                                        routingQrScanTarget = false
                                        routingAssetQrScanTarget = false
                                        navController.popBackStack()
                                    },
                                    onQrCodeScanned = { text, onComplete ->
                                        if (routingQrScanTarget) {
                                            scannedRoutingRulesText = text
                                        } else if (routingAssetQrScanTarget) {
                                            scannedRoutingAssetUrl = text
                                        } else {
                                            scannedSubscriptionText = text
                                        }
                                        routingQrScanTarget = false
                                        routingAssetQrScanTarget = false
                                        onComplete(true)
                                        navController.popBackStack()
                                    }
                                )
                            }

                            composable(AppRoute.OTHER_CONFIG) {
                                OtherConfigScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onOpenRoutingAssets = { navigateTo(AppRoute.ROUTING_ASSETS) }
                                )
                            }

                            composable(AppRoute.SPLIT_ROUTING_SETTINGS) {
                                SplitRoutingSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    onOpenPerAppInbound = { mode ->
                                        navigateTo(AppRoute.perAppInbound(mode.name))
                                    },
                                    onOpenRoutingRules = { mode ->
                                        navigateTo(AppRoute.coreRouting(mode.name))
                                    }
                                )
                            }

                            composable(AppRoute.INBOUND) {
                                InboundScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.CORE_ROUTING) { backStackEntry ->
                                val mode = backStackEntry.arguments?.getString("mode")
                                    ?.let { runCatching { ProxyMode.valueOf(it) }.getOrNull() }
                                    ?: ProxyMode.SMART
                                CoreRoutingScreen(
                                    viewModel = viewModel,
                                    mode = mode,
                                    onBack = { navController.popBackStack() },
                                    onScanQrCode = {
                                        routingQrScanTarget = true
                                        navigateTo(AppRoute.QR_SCANNER)
                                    },
                                    scannedRulesText = scannedRoutingRulesText,
                                    onScannedRulesTextConsumed = {
                                        scannedRoutingRulesText = null
                                    }
                                )
                            }

                            composable(AppRoute.ROUTING_ASSETS) {
                                RoutingAssetsScreen(
                                    viewModel = viewModel<RoutingAssetsViewModel>(),
                                    onBack = { navController.popBackStack() },
                                    onScanQrCode = {
                                        routingAssetQrScanTarget = true
                                        navigateTo(AppRoute.QR_SCANNER)
                                    },
                                    scannedAssetUrl = scannedRoutingAssetUrl,
                                    onScannedAssetUrlConsumed = {
                                        scannedRoutingAssetUrl = null
                                    }
                                )
                            }

                            composable(AppRoute.OUTBOUND) {
                                OutboundScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.PER_APP_INBOUND) { backStackEntry ->
                                val mode = backStackEntry.arguments?.getString("mode")
                                    ?.let { runCatching { ProxyMode.valueOf(it) }.getOrNull() }
                                    ?: ProxyMode.SMART
                                PerAppInboundScreen(
                                    viewModel = viewModel<PerAppInboundViewModel>(),
                                    mode = mode,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.SPEED_TEST_SCHEMES) {
                                SpeedTestSchemesScreen(
                                    viewModel = viewModel,
                                    onOpenEditor = { schemeId ->
                                        navigateTo(AppRoute.speedTestSchemeEditor(schemeId))
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.SPEED_TEST_SCHEME_EDITOR) { backStackEntry ->
                                SpeedTestSchemeEditorScreen(
                                    viewModel = viewModel,
                                    schemeId = backStackEntry.arguments?.getString("schemeId").orEmpty(),
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.ACCOUNT_SETTINGS_DETAIL) { backStackEntry ->
                                val pageKey = backStackEntry.arguments?.getString("page")
                                val page = AccountSettingsPage.values()
                                    .firstOrNull { it.routeKey == pageKey }
                                    ?: AccountSettingsPage.ACCOUNT_INFO
                                AccountSettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onAccountDeleted = { finishAndRemoveTask() },
                                    page = page
                                )
                            }

                            composable(AppRoute.SKIN) {
                                SkinScreen(
                                    viewModel = viewModel,
                                    onOpenEditor = { schemeId ->
                                        navigateTo(AppRoute.skinEditor(schemeId))
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.SKIN_EDITOR) { backStackEntry ->
                                SkinSchemeEditorScreen(
                                    viewModel = viewModel,
                                    schemeId = backStackEntry.arguments?.getString("schemeId").orEmpty(),
                                    onBack = { navController.popBackStack() }
                                )
                            }

                        } else {
                            StartupAccountScreen(
                                backendSettings = backendSettings,
                                isBusy = isStartupAccountActionInProgress,
                                message = accountMessage,
                                onLogin = viewModel::loginAtStartup,
                                onRegister = viewModel::registerAtStartup,
                                onGuestLogin = viewModel::continueAsGuestAtStartup,
                                onOfflineUse = viewModel::continueOfflineAtStartup
                            )
                        }
                }
            }
        }
    }

    private fun requestVpnPermission(action: () -> Unit) {
        pendingVpnAction = action
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueVpnPermission()
    }

    private fun continueVpnPermission() {
        val action = pendingVpnAction ?: return
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            action()
            pendingVpnAction = null
        }
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}
