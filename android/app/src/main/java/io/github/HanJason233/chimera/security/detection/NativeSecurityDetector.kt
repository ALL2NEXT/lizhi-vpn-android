package io.github.HanJason233.chimera.security.detection

import android.content.Context
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Proxy
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.security.handling.NativeSecurityActions
import io.github.HanJason233.chimera.service.BoxVpnService
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** 原生扫描在 IO 线程串行执行；网络线程在发送业务请求前检查防护是否就绪。 */
@Keep
object NativeSecurityDetector {
    private const val TAG = "NativeSecurity"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeLock = Any()
    private val stateLock = Any()
    private val firstScan = CountDownLatch(1)
    @Volatile private var appContext: Context? = null
    @Volatile private var foreground = false
    @Volatile private var scanCodes: Set<String> = emptySet()
    private val eventCodes = linkedSetOf<String>()
    private val captureCodes = setOf(
        "http_proxy_configured", "http_proxy_pac_configured", "capture_detection_unavailable"
    )
    private val blockingCodes = setOf(
        "native_library_unavailable", "native_scan_error", "capture_detection_unavailable",
        "signature_unavailable", "signature_mismatch", "frida_maps", "frida_thread",
        "instrumentation_tracer", "process_maps_unavailable", "frida_threads_unavailable",
        "tracer_status_unavailable"
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
    private external fun nativeInspect(context: Context): Array<String>

    @JvmStatic
    private external fun nativeReportScreenshotCapture(context: Context): String?

    @JvmStatic
    private external fun nativeApplyActivitySecurity(activity: Activity)

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
            collectInspection(context, applyPolicy = true)
            Unit
        }
    }

    suspend fun inspectEnvironment(context: Context): SecurityInspection = withContext(Dispatchers.IO) {
        synchronized(nativeLock) { collectInspection(context, applyPolicy = false) }
    }

    private fun collectInspection(context: Context, applyPolicy: Boolean): SecurityInspection {
        val startedAt = SystemClock.elapsedRealtime()
        val codes = if (!nativeLibraryLoaded) {
            setOf("native_library_unavailable")
        } else {
            runCatching {
                if (applyPolicy) nativeScan(context.applicationContext).toSet()
                else nativeInspect(context.applicationContext).toSet()
            }.onFailure { Log.e(TAG, "Native security scan failed", it) }
                .getOrElse { setOf("native_scan_error") }
        }
        replaceScanCodes(codes)
        firstScan.countDown()
        return SecurityInspection(
            codes = codes,
            inspectedAtMillis = System.currentTimeMillis(),
            durationMillis = SystemClock.elapsedRealtime() - startedAt
        )
    }

    /** 仅在后台网络/服务线程调用，避免首次请求抢在安全扫描之前发送。 */
    fun checkNetworkRequest() {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return
        val context = appContext ?: throw IOException("网络安全检测尚未初始化")
        if (!nativeLibraryLoaded) throw IOException("原生安全组件不可用，已停止网络请求")
        try {
            if (!firstScan.await(10, TimeUnit.SECONDS)) throw IOException("网络安全检测超时，已停止网络请求")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("网络安全检测已取消").apply { initCause(error) }
        }
        synchronized(nativeLock) {
            refreshCapturePolicyNow(context)
            val blocked = scanCodes.firstOrNull { it in blockingCodes }
            if (blocked != null) throw IOException("安全检测未通过，已停止网络请求: " + blocked)
        }
    }

    fun reportScreenshotCapture(context: Context): SecurityFinding? {
        if (!BuildConfig.SECURITY_DETECT_SCREENSHOT || !nativeLibraryLoaded) return null
        val code = runCatching { nativeReportScreenshotCapture(context.applicationContext) }
            .onFailure { error -> Log.e(TAG, "Could not report screenshot event", error) }
            .getOrNull()
            ?: return null
        return SecurityFinding(code).also(::publish)
    }

    fun applyActivitySecurity(activity: Activity) {
        if (!nativeLibraryLoaded) {
            if (BuildConfig.SECURITY_DETECT_SCREENSHOT) NativeSecurityActions.blockScreenshots(activity)
            publish(SecurityFinding("native_library_unavailable"))
            return
        }
        runCatching { nativeApplyActivitySecurity(activity) }
            .onFailure { error -> Log.e(TAG, "Could not apply native activity security", error) }
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
    }

    fun reportNetworkFinding(code: String) {
        if (code !in setOf("tls_validation_failed", "cleartext_traffic_blocked", "http_proxy_route_blocked")) return
        publish(SecurityFinding(code))
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
