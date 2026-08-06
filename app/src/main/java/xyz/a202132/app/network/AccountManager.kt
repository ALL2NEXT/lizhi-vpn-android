package xyz.a202132.app.network

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.withTimeout
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.data.model.AccountRegisterRequest
import xyz.a202132.app.data.model.TrafficReportRequest
import xyz.a202132.app.data.model.TrafficReportResponse
import java.security.MessageDigest
import java.util.UUID

/**
 * Registers this installation with the Worker and reports one traffic total at disconnect time.
 * Android 10+ does not expose a reliable Wi-Fi MAC to ordinary apps, so the account key is a
 * SHA-256 hash of ANDROID_ID (with a persisted UUID fallback). The raw identifier never leaves
 * the device.
 */
class AccountManager(private val context: Context) {
    @Volatile
    private var account: AccountInfo? = null

    fun currentAccount(): AccountInfo? = account

    suspend fun register(timeoutMs: Long = 25000L): Result<AccountInfo> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("未配置后台地址"))
        }

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 60000L)) {
                NetworkClient.apiService.registerAccount(
                    accountEndpoint("/api/account/register"),
                    AccountRegisterRequest(
                        deviceId = deviceKey(),
                        appVersion = BuildConfig.VERSION_NAME
                    )
                )
            }
            val registered = response.account ?: throw IllegalStateException(
                response.error ?: "后台未返回账户信息"
            )
            account = registered
            registered
        }
    }

    suspend fun reportTraffic(
        sessionId: String,
        uploadBytes: Long,
        downloadBytes: Long,
        timeoutMs: Long = 10000L
    ): Result<TrafficReportResponse> {
        val registered = account
            ?: return Result.failure(IllegalStateException("账户尚未注册"))

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.reportTraffic(
                    accountEndpoint("/api/account/report"),
                    TrafficReportRequest(
                        accountId = registered.id,
                        sessionId = sessionId,
                        uploadBytes = uploadBytes.coerceAtLeast(0L),
                        downloadBytes = downloadBytes.coerceAtLeast(0L)
                    )
                )
            }
            if (!response.ok) {
                throw IllegalStateException(response.error ?: "流量上报失败")
            }
            response
        }
    }

    private fun accountEndpoint(path: String): String =
        "${AppConfig.BACKEND_URL.trimEnd('/')}$path"

    private fun deviceKey(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val preferences = context.getSharedPreferences("account_identity", Context.MODE_PRIVATE)
        val fallback = preferences.getString("install_id", null)
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit().putString("install_id", generated).apply()
            }
        val source = androidId?.takeIf { it.isNotBlank() } ?: fallback
        return sha256("fireflyvpn-account:$source")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
