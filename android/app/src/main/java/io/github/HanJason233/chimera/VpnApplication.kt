package io.github.HanJason233.chimera

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.HanJason233.chimera.util.RuleManager
import io.github.HanJason233.chimera.util.SignatureVerifier

class VpnApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()

        AppConfig.initialize(this)
        
        // 签名校验 - 如果 APK 被篡改，APP 会立即 Crash
        SignatureVerifier.verifySignature(this)
        
        // 后台更新规则集（不阻塞启动，失败不影响使用）
        applicationScope.launch(Dispatchers.IO) {
            // Give homepage database/network preloading priority. Bundled
            // rules remain available if VPN starts before maintenance runs.
            delay(15_000)
            try {
                RuleManager.updateRuleSets(this@VpnApplication)
            } catch (e: Exception) {
                Log.w("VpnApplication", "Background rule set update failed", e)
            }
        }
    }
}
