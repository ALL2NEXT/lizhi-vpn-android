package xyz.a202132.app

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
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
import xyz.a202132.app.feature.nodegrouping.ui.NodeGroupingDestination
import xyz.a202132.app.ui.components.FloatingHomeNavigationLayout
import xyz.a202132.app.ui.components.HomeDestination
import xyz.a202132.app.ui.navigation.AppRoute
import xyz.a202132.app.ui.screens.FeatureScreen
import xyz.a202132.app.ui.screens.AccountSettingsScreen
import xyz.a202132.app.ui.screens.MainScreen
import xyz.a202132.app.ui.screens.OtherConfigScreen
import xyz.a202132.app.ui.screens.ProxyScopeScreen
import xyz.a202132.app.ui.screens.QrScannerScreen
import xyz.a202132.app.ui.screens.SkinScreen
import xyz.a202132.app.ui.screens.SkinSchemeEditorScreen
import xyz.a202132.app.ui.screens.WebNavigationScreen
import xyz.a202132.app.ui.theme.FireflyTheme
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.NodeImportResult

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
            val nodeGroupingMode by viewModel.nodeGroupingMode.collectAsStateWithLifecycle()
            FireflyTheme(skinSettings = skinSettings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val navigateTo: (String) -> Unit = { route ->
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        }
                        val favoriteImportMessage: (NodeImportResult, Int) -> String = { result, importedCount ->
                            when (result) {
                                NodeImportResult.IMPORTED -> "导入${importedCount}个节点到收藏节点"
                                NodeImportResult.DUPLICATE -> "节点已存在，无需重复导入"
                                NodeImportResult.INVALID -> "未找到可导入节点或链接无效"
                                NodeImportResult.FETCH_FAILED -> "订阅链接获取失败，请检查地址和网络"
                            }
                        }
                        val importNodesToFavorites: (String, Boolean, ((Boolean) -> Unit)?) -> Unit =
                            { text, returnToNodeList, onComplete ->
                                viewModel.importNodesToFavoritesFromText(text) { result, importedCount ->
                                    val success = result == NodeImportResult.IMPORTED
                                    Toast.makeText(
                                        this@MainActivity,
                                        favoriteImportMessage(result, importedCount),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onComplete?.invoke(success)
                                    if (success && returnToNodeList) {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        NavHost(
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
                                when (selectedDestination) {
                                    HomeDestination.HOME -> MainScreen(
                                        viewModel = viewModel,
                                        onStartVpn = { action ->
                                            requestVpnPermission(action)
                                        },
                                        onOpenProxyScope = {
                                            navigateTo(AppRoute.PROXY_SCOPE)
                                        },
                                        onOpenNodeList = {
                                            viewModel.selectSubscriptionGrouping()
                                            selectedDestination = HomeDestination.NODE_GROUPING
                                        },
                                        onOpenOtherConfig = {
                                            navigateTo(AppRoute.OTHER_CONFIG)
                                        },
                                        onOpenFeatures = {
                                            navigateTo(AppRoute.FEATURES)
                                        },
                                        onOpenAccountSettings = {
                                            navigateTo(AppRoute.ACCOUNT_SETTINGS)
                                        },
                                        onOpenSkin = { navigateTo(AppRoute.SKIN) },
                                        bottomNavigationDestination =
                                            selectedDestination,
                                        nodeGroupingMode = nodeGroupingMode,
                                        onBottomNavigationDestinationSelected = {
                                            selectedDestination = it
                                        }
                                    )

                                    HomeDestination.NODE_GROUPING ->
                                        FloatingHomeNavigationLayout(
                                            selectedDestination = selectedDestination,
                                            groupingMode = nodeGroupingMode,
                                            onDestinationSelected = {
                                                selectedDestination = it
                                            }
                                        ) {
                                            NodeGroupingDestination(
                                                groupingMode = nodeGroupingMode,
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
                                                onImportFromText = {
                                                    importNodesToFavorites(it, false, null)
                                                },
                                                onScanQrCode = {
                                                    navigateTo(AppRoute.QR_SCANNER)
                                                },
                                                onBack = {
                                                    selectedDestination = HomeDestination.HOME
                                                },
                                                bottomContentPadding = 108.dp
                                            )
                                        }

                                    HomeDestination.NAVIGATION ->
                                        FloatingHomeNavigationLayout(
                                            selectedDestination = selectedDestination,
                                            groupingMode = nodeGroupingMode,
                                            onDestinationSelected = {
                                                selectedDestination = it
                                            }
                                        ) {
                                            WebNavigationScreen(
                                                onBack = {
                                                    selectedDestination = HomeDestination.HOME
                                                },
                                                bottomContentPadding = 108.dp
                                            )
                                        }
                                }
                            }

                            composable(AppRoute.PROXY_SCOPE) {
                                var isLeavingProxyScope by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxSize()) {
                                    ProxyScopeScreen(
                                        onBack = { hasChanges ->
                                            if (isLeavingProxyScope) return@ProxyScopeScreen
                                            isLeavingProxyScope = true
                                            navController.popBackStack()
                                            if (hasChanges) {
                                                viewModel.restartVpnIfNeeded()
                                            }
                                        }
                                    )

                                    if (isLeavingProxyScope) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) { }
                                        )
                                    }
                                }
                            }

                            composable(AppRoute.QR_SCANNER) {
                                QrScannerScreen(
                                    onBack = { navController.popBackStack() },
                                    onQrCodeScanned = { text, onComplete ->
                                        importNodesToFavorites(text, true, onComplete)
                                    }
                                )
                            }

                            composable(AppRoute.OTHER_CONFIG) {
                                OtherConfigScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.FEATURES) {
                                FeatureScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.ACCOUNT_SETTINGS) {
                                AccountSettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onAccountDeleted = { finishAndRemoveTask() }
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
