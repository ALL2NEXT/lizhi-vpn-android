package io.github.HanJason233.chimera.security.detection

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.HanJason233.chimera.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenshotCaptureStatus { DISABLED, UNSUPPORTED, INACTIVE, ACTIVE, UNAVAILABLE }

/** Activity 可见期间注册 Android 截图回调，并将事件交给原生层。 */
object ScreenshotCaptureDetector {
    private var callback: Any? = null
    private var callbackActivity: Activity? = null
    private val mutableStatus = MutableStateFlow(
        when {
            !BuildConfig.SECURITY_DETECT_SCREENSHOT -> ScreenshotCaptureStatus.DISABLED
            Build.VERSION.SDK_INT < 34 -> ScreenshotCaptureStatus.UNSUPPORTED
            else -> ScreenshotCaptureStatus.INACTIVE
        }
    )
    val status = mutableStatus.asStateFlow()

    fun start(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34 || !BuildConfig.SECURITY_DETECT_SCREENSHOT || callback != null) {
            return
        }
        registerApi34(activity)
    }

    fun stop(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        unregisterApi34(activity)
    }

    @RequiresApi(34)
    private fun registerApi34(activity: Activity) {
        val screenCaptureCallback = Activity.ScreenCaptureCallback {
            NativeSecurityDetector.reportScreenshotCapture(activity)
        }
        try {
            activity.registerScreenCaptureCallback(activity.mainExecutor, screenCaptureCallback)
            callback = screenCaptureCallback
            callbackActivity = activity
            mutableStatus.value = ScreenshotCaptureStatus.ACTIVE
        } catch (error: RuntimeException) {
            mutableStatus.value = ScreenshotCaptureStatus.UNAVAILABLE
            Log.w("NativeSecurity", "Could not register screenshot callback", error)
        }
    }

    @RequiresApi(34)
    private fun unregisterApi34(activity: Activity) {
        if (callbackActivity !== activity) return
        val screenCaptureCallback = callback as? Activity.ScreenCaptureCallback ?: return
        callback = null
        callbackActivity = null
        mutableStatus.value = ScreenshotCaptureStatus.INACTIVE
        runCatching { activity.unregisterScreenCaptureCallback(screenCaptureCallback) }
            .onFailure { error ->
                mutableStatus.value = ScreenshotCaptureStatus.UNAVAILABLE
                Log.w("NativeSecurity", "Could not unregister screenshot callback", error)
            }
    }
}
