package io.github.HanJason233.chimera.security.detection

import android.content.Context
import android.app.Activity
import android.util.Log
import androidx.annotation.Keep
import io.github.HanJason233.chimera.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SecurityFinding(val code: String)

/** 原生检测的 Kotlin 入口，负责调用 JNI 并保存只读检测结果。 */
@Keep
object NativeSecurityDetector {
    private const val TAG = "NativeSecurity"

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
    private external fun nativeReportScreenshotCapture(context: Context): String?

    @JvmStatic
    private external fun nativeApplyActivitySecurity(activity: Activity)

    @JvmStatic
    private external fun nativeRefreshCapturePolicy(context: Context)

    fun scan(context: Context) {
        if (!nativeLibraryLoaded) {
            publish(SecurityFinding("native_library_unavailable"))
            return
        }

        val codes = runCatching { nativeScan(context.applicationContext) }
            .onFailure { error -> Log.e(TAG, "Native security scan failed", error) }
            .getOrElse { arrayOf("native_scan_error") }
        codes.forEach { code -> publish(SecurityFinding(code)) }
        Log.i(TAG, "Initial native security scan completed; findings=${codes.size}")
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
        if (!nativeLibraryLoaded) return
        runCatching { nativeApplyActivitySecurity(activity) }
            .onFailure { error -> Log.e(TAG, "Could not apply native activity security", error) }
    }

    fun refreshCapturePolicy(context: Context) {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY || !nativeLibraryLoaded) return
        runCatching { nativeRefreshCapturePolicy(context.applicationContext) }
            .onFailure { error -> Log.w(TAG, "Could not refresh HTTP proxy blocking", error) }
    }

    private fun publish(finding: SecurityFinding) {
        if (mutableFindings.value.any { it.code == finding.code }) return
        mutableFindings.value = mutableFindings.value + finding
        Log.w(TAG, "Security detection: ${finding.code}")
    }
}
