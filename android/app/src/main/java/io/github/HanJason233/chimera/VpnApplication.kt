package io.github.HanJason233.chimera

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.util.RuleManager
import io.github.HanJason233.chimera.security.detection.NativeSecurityDetector

class VpnApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()

        AppConfig.initialize(this)
        
        // 后台扫描，并在前台或 VPN 运行期间定期复检。
        NativeSecurityDetector.start(this)

        // 首次启动从 APK assets 初始化 Xray Geo 数据库，已存在的文件保留原样。
        applicationScope.launch(Dispatchers.IO) {
            val result = RoutingAssetsRepository(this@VpnApplication).initializeBundledAssets()
            if (result.copied.isNotEmpty()) {
                Log.i("VpnApplication", "Initialized bundled Geo assets: ${result.copied.joinToString()}")
            }
            if (result.failed.isNotEmpty()) {
                Log.w("VpnApplication", "Bundled Geo asset initialization failed: ${result.failed.joinToString()}")
            }
        }
        
        // 自动更新受资源文件页面中的 sing-box 规则集开关控制。
        applicationScope.launch(Dispatchers.IO) {
            // 优先加载首页所需的数据库和网络数据；VPN 先启动时仍可使用 APK 内置规则。
            delay(15_000)
            try {
                val result = RuleManager.updateRuleSets(this@VpnApplication)
                if (result.failed.isNotEmpty()) {
                    Log.w("VpnApplication", "Rule set update failed: ${result.failed.joinToString()}")
                }
            } catch (e: Exception) {
                Log.w("VpnApplication", "Background rule set update failed", e)
            }
        }
    }
}
