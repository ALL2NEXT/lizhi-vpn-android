package io.github.HanJason233.chimera.security.detection

import android.content.Context
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Proxy
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.security.handling.NativeSecurityActions
import io.github.HanJason233.chimera.service.BoxVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SecurityFinding(val code: String)

/** Native scans run serially on IO; findings are queued for reporting while transport safeguards remain active. */
@Keep
object NativeSecurityDetector {
    private const val TAG = "NativeSecurity"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeLock = Any()
    private val stateLock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var foreground = false
    @Volatile private var scanCodes: Set<String> = emptySet()
    private val eventCodes = linkedSetOf<String>()
    private val captureCodes = setOf(
        "http_proxy_configured", "http_proxy_pac_configured", "capture_detection_unavailable"
    )
    private val proxyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Proxy.PROXY_CHANGE_ACTION) refreshCapturePolicy(context)
        }
    }

    private val nativeLibraryLoaded = runCatching {
        System.loadLibrary("native-lib")
    }.onFailure { error ->
        Log.e(TAG, "Could not load native security library", error)
    }.isSuccess

    private val mutableFindings = MutableStateFlow<List<SecurityFinding>>(emptyList())
    val findings: StateFlow<List<SecurityFinding>> = mutableFindings.asStateFlow()

    @JvmStatic
    private external fun nativeScan(context: Context): Array<String>

    @JvmStatic
    private external fun nativeApplyActivitySecurity(activity: Activity): Array<String>

    @JvmStatic
    private external fun nativeRefreshCapturePolicy(context: Context): Array<String>

    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        val application = context.applicationContext
        appContext = application
        if (BuildConfig.SECURITY_DETECT_CAPTURE || BuildConfig.SECURITY_BLOCK_HTTP_PROXY) {
            runCatching {
                ContextCompat.registerReceiver(
                    application, proxyReceiver, IntentFilter(Proxy.PROXY_CHANGE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure { Log.w(TAG, "Could not observe proxy changes; requests still recheck", it) }
        }
        scope.launch {
            scanNow(application)
            while (isActive) {
                delay(30_000L)
                if (foreground || BoxVpnService.isRunning) scanNow(application)
            }
        }
    }

    fun setForeground(context: Context, visible: Boolean) {
        foreground = visible
        if (visible) scan(context)
    }

    fun scan(context: Context) = scope.launch { scanNow(context) }

    suspend fun scanNow(context: Context) = withContext(Dispatchers.IO) {
        synchronized(nativeLock) {
            performScan(context)
        }
    }

    private fun performScan(context: Context) {
        val codes = if (!nativeLibraryLoaded) {
            setOf("native_library_unavailable")
        } else {
            runCatching { nativeScan(context.applicationContext).toSet() }
                .onFailure { Log.e(TAG, "Native security scan failed", it) }
                .getOrElse { setOf("native_scan_error") }
        }
        replaceScanCodes(codes)
        reportFindings(context.applicationContext, codes)
    }

    /** Keep proxy policy current while allowing requests to continue after a risk finding. */
    fun checkNetworkRequest() {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return
        val context = appContext ?: return
        if (!nativeLibraryLoaded) {
            reportFindings(context, setOf("native_library_unavailable"))
            return
        }
        synchronized(nativeLock) {
            refreshCapturePolicyNow(context)
        }
    }

    fun applyActivitySecurity(activity: Activity) {
        if (!nativeLibraryLoaded) {
            NativeSecurityActions.blockScreenshots(activity)
            publish(SecurityFinding("native_library_unavailable"))
            reportFindings(activity.applicationContext, setOf("native_library_unavailable"))
            return
        }
        val codes = runCatching { nativeApplyActivitySecurity(activity).toSet() }
            .onFailure { error -> Log.e(TAG, "Could not apply native activity security", error) }
            .getOrElse { setOf("screenshot_protection_unavailable") }
        codes.forEach { publish(SecurityFinding(it)) }
        reportFindings(activity.applicationContext, codes)
    }

    fun refreshCapturePolicy(context: Context) {
        if (!BuildConfig.SECURITY_DETECT_CAPTURE && !BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return
        scope.launch {
            synchronized(nativeLock) { refreshCapturePolicyNow(context.applicationContext) }
        }
    }

    private fun refreshCapturePolicyNow(context: Context) {
        val codes = if (!nativeLibraryLoaded) {
            setOf("capture_detection_unavailable")
        } else {
            runCatching { nativeRefreshCapturePolicy(context).toSet() }
                .onFailure { Log.e(TAG, "Could not refresh capture detection", it) }
                .getOrElse { setOf("capture_detection_unavailable") }
        }
        replaceScanCodes((scanCodes - captureCodes) + codes)
        reportFindings(context.applicationContext, codes)
    }

    fun reportNetworkFinding(code: String) {
        if (code !in setOf(
                "tls_validation_failed", "cleartext_traffic_blocked",
                "http_proxy_route_blocked", "https_downgrade_blocked"
            )) return
        publish(SecurityFinding(code))
        appContext?.let { reportFindings(it, setOf(code)) }
    }

    private fun reportFindings(context: Context, codes: Set<String>) {
        if (codes.isNotEmpty()) {
            NativeSecurityActions.reportFindings(context.applicationContext, codes.toTypedArray())
        }
    }

    private fun replaceScanCodes(codes: Set<String>) {
        synchronized(stateLock) {
            (codes - scanCodes).forEach { Log.w(TAG, "Security detection: " + it) }
            scanCodes = codes
            mutableFindings.value = (scanCodes + eventCodes).map(::SecurityFinding)
        }
    }

    private fun publish(finding: SecurityFinding) {
        synchronized(stateLock) {
            if (!eventCodes.add(finding.code)) return
            mutableFindings.value = (scanCodes + eventCodes).map(::SecurityFinding)
            Log.w(TAG, "Security detection: " + finding.code)
        }
    }
}
