package io.github.HanJason233.chimera.security.handling

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
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
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun showSecurityToast(context: Context, message: String) {
        val appContext = context.applicationContext
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }

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
    fun reportFindings(context: Context, codes: Array<String>) {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return
        val findings = codes.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.startsWith("blacklisted_package:") }
            .map { it.take(160) }
            .distinct()
            .take(20)
            .toList()
        if (findings.isEmpty()) return

        val appContext = context.applicationContext
        synchronized(this) {
            val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val pending = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty().toMutableSet()
            pending.addAll(findings)
            preferences.edit().putStringSet(PENDING_CODES_KEY, pending.take(20).toSet()).apply()
        }
        flushPendingFindings(appContext)
    }

    @JvmStatic
    fun flushPendingFindings(context: Context) {
        if (!BuildConfig.SECURITY_REPORT_FINDINGS) return
        val appContext = context.applicationContext
        reportScope.launch {
            reportMutex.withLock {
                val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                val storedCodes = preferences.getStringSet(PENDING_CODES_KEY, emptySet()).orEmpty()
                val pending = storedCodes.filterTo(mutableSetOf()) {
                    it.startsWith("blacklisted_package:")
                }
                if (pending.size != storedCodes.size) {
                    val editor = preferences.edit()
                    if (pending.isEmpty()) editor.remove(PENDING_CODES_KEY)
                    else editor.putStringSet(PENDING_CODES_KEY, pending)
                    editor.apply()
                }
                if (pending.isEmpty()) return@withLock

                AccountManager(appContext).reportSecurityFindings(pending.toList())
                    .onSuccess {
                        synchronized(this@NativeSecurityActions) {
                            val latest = preferences.getStringSet(PENDING_CODES_KEY, emptySet())
                                .orEmpty().toMutableSet()
                            latest.removeAll(pending)
                            val editor = preferences.edit()
                            if (latest.isEmpty()) editor.remove(PENDING_CODES_KEY)
                            else editor.putStringSet(PENDING_CODES_KEY, latest)
                            editor.apply()
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Could not upload security findings; will retry later", error)
                    }
            }
        }
    }
}
