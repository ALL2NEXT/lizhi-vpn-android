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

/** native 层调用的 Android 平台操作桥接。策略选择仍由 native 编译开关控制。 */
@Keep
object NativeSecurityActions {
    private const val TAG = "NativeSecurity"
    private const val PREFERENCES_NAME = "native_security_reports"
    private const val PENDING_CODES_KEY = "pending_finding_codes"
    private const val MAX_PENDING_CODES = 64
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()

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
    fun reportFindings(context: Context, codes: Array<String>): Boolean {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return false
        val findings = codes.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter(::isReportOnly)
            .map { it.take(160) }
            .distinct()
            .take(MAX_PENDING_CODES)
            .toList()
        if (findings.isEmpty()) return false

        val appContext = context.applicationContext
        val saved = synchronized(this) {
            val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val pending = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty().toMutableSet()
            pending.addAll(findings)
            // 同一轮还可能命中终止项，先同步落盘，避免 abort 丢失待上报信号。
            preferences.edit().putStringSet(PENDING_CODES_KEY, pending.take(MAX_PENDING_CODES).toSet()).commit()
        }
        if (saved) flushPendingFindings(appContext)
        return saved
    }

    private fun isReportOnly(code: String): Boolean =
        code.startsWith("blacklisted_package:") ||
            code == "adb_enabled" || code == "developer_options_enabled"

    @JvmStatic
    fun flushPendingFindings(context: Context) {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return
        val appContext = context.applicationContext
        reportScope.launch {
            reportMutex.withLock {
                val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                while (true) {
                    val pending = synchronized(this@NativeSecurityActions) {
                        val stored = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty()
                        val allowed = stored.filterTo(linkedSetOf(), ::isReportOnly)
                        if (allowed != stored) {
                            preferences.edit().putStringSet(PENDING_CODES_KEY, allowed).commit()
                        }
                        allowed.take(20).toSet()
                    }
                    if (pending.isEmpty()) break
                    val result = AccountManager(appContext).reportSecurityFindings(pending.toList())
                    if (result.isFailure) {
                        Log.w(TAG, "Could not upload security findings; will retry later", result.exceptionOrNull())
                        break
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
                    if (!removed) break
                }
            }
        }
    }
}
