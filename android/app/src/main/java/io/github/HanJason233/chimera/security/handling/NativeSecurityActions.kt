package io.github.HanJason233.chimera.security.handling

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.annotation.Keep
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.network.account.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** JNI bridge for Android screenshot/proxy protection and queued risk reporting. */
@Keep
object NativeSecurityActions {
    private const val TAG = "NativeSecurity"
    private const val PREFERENCES_NAME = "native_security_reports"
    private const val PENDING_CODES_KEY = "pending_finding_codes"
    private const val MAX_PENDING_CODES = 64
    private const val BACKGROUND_REPORT_TIMEOUT_MS = 10_000L
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()
    private val reportedCodes = linkedSetOf<String>()

    @JvmStatic
    fun blockScreenshots(activity: Activity) {
        if (!BuildConfig.SECURITY_BLOCK_SCREENSHOTS) return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    @JvmStatic
    fun setHttpProxyBlocking(enabled: Boolean) {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return
        SecurityProxySelector.setCaptureProxyBlocked(enabled)
    }

    @JvmStatic
    @Synchronized
    fun reportFindings(context: Context, codes: Array<String>): Boolean {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return false
        val findings = normalizeFindings(codes).filter(::isReportable)
        if (findings.isEmpty()) return false

        val appContext = context.applicationContext
        val newFindings = findings.filterNot(reportedCodes::contains)
        if (newFindings.isEmpty()) {
            flushPendingFindings(appContext)
            return true
        }
        val saved = enqueueFindings(appContext, newFindings)
        if (saved) reportedCodes.addAll(newFindings)
        if (saved) flushPendingFindings(appContext)
        return saved
    }

    private fun normalizeFindings(codes: Array<String>): List<String> = codes.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(160) }
        .distinct()
        .take(MAX_PENDING_CODES)
        .toList()

    private fun enqueueFindings(context: Context, findings: List<String>): Boolean = synchronized(this) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val pending = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty().toMutableSet()
        val retained = pending.filterNot(findings::contains)
            .take((MAX_PENDING_CODES - findings.size).coerceAtLeast(0))
        val bounded = (retained + findings).toSet()
        preferences.edit()
            .putStringSet(PENDING_CODES_KEY, bounded)
            .commit()
    }

    private fun isReportable(code: String): Boolean =
        code != "screenshot_capture" && code != "frida_integrity_unavailable"

    @JvmStatic
    fun flushPendingFindings(context: Context) {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return
        val appContext = context.applicationContext
        reportScope.launch {
            flushPendingFindingsNow(appContext, BACKGROUND_REPORT_TIMEOUT_MS)
        }
    }

    private suspend fun flushPendingFindingsNow(
        appContext: Context,
        timeoutMs: Long,
        prioritizedCodes: Set<String> = emptySet()
    ): Boolean = reportMutex.withLock {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        while (true) {
            val pending = synchronized(this@NativeSecurityActions) {
                val stored = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty()
                val allowed = stored.filterTo(linkedSetOf(), ::isReportable)
                if (allowed != stored) {
                    val editor = preferences.edit()
                    if (allowed.isEmpty()) editor.remove(PENDING_CODES_KEY)
                    else editor.putStringSet(PENDING_CODES_KEY, allowed)
                    editor.commit()
                }
                val ordered = (prioritizedCodes.filter { it in allowed } +
                    allowed.filterNot { it in prioritizedCodes }).take(20)
                ordered
            }
            if (pending.isEmpty()) return@withLock true
            val result = AccountManager(appContext)
                .reportSecurityFindings(pending, timeoutMs)
            if (result.isFailure) {
                Log.w(TAG, "Could not upload security findings; will retry later", result.exceptionOrNull())
                return@withLock false
            }
            val removed = synchronized(this@NativeSecurityActions) {
                val latest = preferences.getStringSet(PENDING_CODES_KEY, emptySet())
                    .orEmpty().toMutableSet()
                latest.removeAll(pending)
                val editor = preferences.edit()
                if (latest.isEmpty()) editor.remove(PENDING_CODES_KEY)
                else editor.putStringSet(PENDING_CODES_KEY, latest)
                editor.commit()
            }
            if (!removed) return@withLock false
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
}
