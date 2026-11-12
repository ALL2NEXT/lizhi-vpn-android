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
import kotlin.system.exitProcess

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
    private val reportOnlyCodes = setOf("adb_enabled", "developer_options_enabled")
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
    private external fun nativeTerminate(finding: String)

    @JvmStatic
    private external fun nativeReportNetworkFinding(finding: String)

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
        if (!nativeLibraryLoaded) terminateOnFinding("native_library_unavailable")
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
            collectInspection(context)
            Unit
        }
    }

    suspend fun inspectEnvironment(context: Context): SecurityInspection = withContext(Dispatchers.IO) {
        synchronized(nativeLock) { collectInspection(context) }
    }

    private fun collectInspection(context: Context): SecurityInspection {
        val startedAt = SystemClock.elapsedRealtime()
        val codes = if (!nativeLibraryLoaded) {
            setOf("native_library_unavailable")
        } else {
            runCatching { nativeScan(context.applicationContext).toSet() }
                .onFailure { Log.e(TAG, "Native security scan failed", it) }
                .getOrElse { setOf("native_scan_error") }
        }
        replaceScanCodes(codes)
        firstScan.countDown()
        firstFatalFinding(codes)?.let(::terminateOnFinding)
        return SecurityInspection(
            codes = codes,
            inspectedAtMillis = System.currentTimeMillis(),
            durationMillis = SystemClock.elapsedRealtime() - startedAt
        )
    }

    /** 仅在后台网络/服务线程调用，避免首次请求抢在安全扫描之前发送。 */
    fun checkNetworkRequest() {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return
        val context = appContext ?: rejectRuntime("native_scan_error", "网络安全检测尚未初始化")
        if (!nativeLibraryLoaded) rejectRuntime("native_library_unavailable", "原生安全组件不可用")
        try {
            if (!firstScan.await(10, TimeUnit.SECONDS)) rejectRuntime("native_scan_error", "网络安全检测超时")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("网络安全检测已取消").apply { initCause(error) }
        }
        synchronized(nativeLock) {
            refreshCapturePolicyNow(context)
            val blocked = firstFatalFinding(scanCodes)
            if (blocked != null) rejectRuntime(blocked, "安全检测未通过: " + blocked)
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
            NativeSecurityActions.blockScreenshots(activity)
            publish(SecurityFinding("native_library_unavailable"))
            terminateOnFinding("native_library_unavailable")
            return
        }
        runCatching { nativeApplyActivitySecurity(activity) }
            .onFailure { error ->
                Log.e(TAG, "Could not apply native activity security", error)
                NativeSecurityActions.blockScreenshots(activity)
                terminateOnFinding("screenshot_protection_unavailable")
            }
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
        firstFatalFinding(codes)?.let(::terminateOnFinding)
    }

    fun reportNetworkFinding(code: String) {
        if (code !in setOf(
                "tls_validation_failed", "cleartext_traffic_blocked",
                "http_proxy_route_blocked", "https_downgrade_blocked"
            )) return
        publish(SecurityFinding(code))
        if (nativeLibraryLoaded) {
            runCatching { nativeReportNetworkFinding(code) }
                .onFailure { Log.e(TAG, "Native network protection failed", it) }
        }
        terminateOnFinding(code)
    }

    private fun firstFatalFinding(codes: Set<String>): String? = codes.firstOrNull {
        it !in reportOnlyCodes && !it.startsWith("blacklisted_package:") && it != "screenshot_capture"
    }

    private fun rejectRuntime(code: String, message: String): Nothing {
        terminateOnFinding(code)
        throw IOException(message)
    }

    private fun terminateOnFinding(code: String) {
        if (!BuildConfig.SECURITY_CRASH_ON_FINDING) return
        Log.e(TAG, "Security termination: " + code)
        if (nativeLibraryLoaded) runCatching { nativeTerminate(code) }
        // 原生库加载失败或原生终止入口异常时同样结束进程。
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
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
