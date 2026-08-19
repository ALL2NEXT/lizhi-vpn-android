package xyz.a202132.app.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.model.AccountActionResponse
import xyz.a202132.app.data.model.AccountBindingRequest
import xyz.a202132.app.data.model.AccountDeleteRequest
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.data.model.AccountLoginRequest
import xyz.a202132.app.data.model.AccountProfileUpdateRequest
import xyz.a202132.app.data.model.AccountRegisterRequest
import xyz.a202132.app.data.model.TrafficReportRequest
import xyz.a202132.app.data.model.TrafficReportResponse
import java.security.MessageDigest
import java.util.UUID

/** Registers this installation and keeps the current banned/guest/formal account identity. */
class AccountManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(
        "account_identity",
        Context.MODE_PRIVATE
    )

    private val _accountInfo = MutableStateFlow(loadPersistedAccount())
    val accountInfo = _accountInfo.asStateFlow()
    fun currentAccount(): AccountInfo? = _accountInfo.value
    fun accountId(): String = deviceKey()

    suspend fun register(timeoutMs: Long = 25000L): Result<AccountInfo> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("backend_url_missing"))
        }
        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 60000L)) {
                NetworkClient.apiService.registerAccount(
                    accountEndpoint("/api/account/register"),
                    AccountRegisterRequest(
                        deviceId = accountId(),
                        appVersion = BuildConfig.VERSION_NAME,
                        brand = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidVersion = Build.VERSION.RELEASE.orEmpty(),
                        androidSdk = Build.VERSION.SDK_INT
                    )
                )
            }
            val account = response.account ?: throw IllegalStateException(
                response.error ?: "account_response_missing"
            )
            persistAccount(account)
            account
        }
    }

    suspend fun syncCurrentAccount(timeoutMs: Long = 25000L): Result<AccountInfo> =
        register(timeoutMs)

    suspend fun updateNickname(nickname: String, timeoutMs: Long = 10000L): Result<AccountInfo> {
        if (currentAccount() == null) {
            return Result.failure(IllegalStateException("account_not_synced"))
        }
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank() || normalizedNickname.length > 24) {
            return Result.failure(IllegalArgumentException("invalid_nickname"))
        }
        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.updateAccountProfile(
                    accountEndpoint("/api/account/profile"),
                    AccountProfileUpdateRequest(accountId(), normalizedNickname)
                )
            }
            val account = response.account ?: throw IllegalStateException(
                response.error ?: "account_response_missing"
            )
            persistAccount(account)
            account
        }
    }

    suspend fun loginAccount(
        email: String,
        password: String,
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            return Result.failure(IllegalArgumentException("invalid_email"))
        }
        if (password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("invalid_password"))
        }
        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.loginAccount(
                    accountEndpoint("/api/account/login"),
                    AccountLoginRequest(
                        deviceId = accountId(),
                        email = normalizedEmail,
                        password = password,
                        appVersion = BuildConfig.VERSION_NAME,
                        brand = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidVersion = Build.VERSION.RELEASE.orEmpty(),
                        androidSdk = Build.VERSION.SDK_INT
                    )
                )
            }
            val account = response.account ?: throw IllegalStateException(
                response.error ?: "account_login_failed"
            )
            persistAccount(account)
            account
        }
    }

    suspend fun bindEmailPassword(
        email: String,
        password: String,
        registrationCode: String = "",
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("account_not_synced"))
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            return Result.failure(IllegalArgumentException("invalid_email"))
        }
        if (password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("invalid_password"))
        }
        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.bindAccount(
                    accountEndpoint("/api/account/bind"),
                    AccountBindingRequest(
                        deviceId = accountId(),
                        uid = registered.uid,
                        email = normalizedEmail,
                        password = password,
                        registrationCode = registrationCode.trim().uppercase()
                            .replace("-", "").replace(" ", "")
                    )
                )
            }
            val account = response.account ?: throw IllegalStateException(
                response.error ?: "account_binding_failed"
            )
            persistAccount(account)
            account
        }
    }

    suspend fun reportTraffic(
        sessionId: String,
        subscriptionGroupName: String,
        startedAt: Long,
        endedAt: Long,
        uploadBytes: Long,
        downloadBytes: Long,
        timeoutMs: Long = 10000L
    ): Result<TrafficReportResponse> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("account_not_registered"))
        if (registered.accountType == "banned") {
            return Result.failure(IllegalStateException("account_banned"))
        }
        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.reportTraffic(
                    accountEndpoint("/api/account/report"),
                    TrafficReportRequest(
                        deviceId = accountId(),
                        sessionId = sessionId,
                        subscriptionGroupName = subscriptionGroupName.trim(),
                        startedAt = startedAt,
                        endedAt = endedAt,
                        uploadBytes = uploadBytes.coerceAtLeast(0L),
                        downloadBytes = downloadBytes.coerceAtLeast(0L)
                    )
                )
            }
            if (!response.ok) throw IllegalStateException(response.error ?: "traffic_report_failed")
            response
        }
    }

    suspend fun deleteAccount(password: String, timeoutMs: Long = 10000L): Result<Unit> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("account_not_synced"))
        if (registered.email.isNotBlank() && password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("invalid_account_password"))
        }
        return runCatching {
            val response: AccountActionResponse = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.deleteAccount(
                    accountEndpoint("/api/account/delete"),
                    AccountDeleteRequest(accountId(), registered.uid, password)
                )
            }
            if (!response.ok) throw IllegalStateException(response.error ?: "account_delete_failed")
            clearLocalAccount()
        }
    }

    private fun accountEndpoint(path: String): String =
        AppConfig.BACKEND_URL.trimEnd('/') + path

    private fun deviceKey(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val fallback = preferences.getString("install_id", null)
            ?: UUID.randomUUID().toString().also { preferences.edit().putString("install_id", it).apply() }
        return sha256("fireflyvpn-account:${androidId?.takeIf { it.isNotBlank() } ?: fallback}")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun loadPersistedAccount(): AccountInfo? {
        val uid = preferences.getString(ACCOUNT_ID_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val email = preferences.getString(ACCOUNT_EMAIL_KEY, "").orEmpty()
        val storedType = preferences.getString(ACCOUNT_TYPE_KEY, "").orEmpty()
        val accountType = storedType.takeIf { it in setOf("banned", "guest", "formal") }
            ?: if (email.isBlank()) "guest" else "formal"
        return AccountInfo(
            uid = uid,
            nickname = preferences.getString(ACCOUNT_NICKNAME_KEY, null)
                ?.takeIf { it.isNotBlank() } ?: "流萤用户",
            accountType = accountType,
            email = email,
            brand = preferences.getString(ACCOUNT_BRAND_KEY, "").orEmpty(),
            model = preferences.getString(ACCOUNT_MODEL_KEY, "").orEmpty(),
            androidVersion = preferences.getString(ACCOUNT_ANDROID_VERSION_KEY, "").orEmpty(),
            androidSdk = preferences.getInt(ACCOUNT_ANDROID_SDK_KEY, 0),
            appVersion = preferences.getString(ACCOUNT_APP_VERSION_KEY, "").orEmpty(),
            deviceBlocked = preferences.getBoolean(ACCOUNT_DEVICE_BLOCKED_KEY, false)
        )
    }

    private fun persistAccount(account: AccountInfo) {
        val normalized = account.copy(
            accountType = if (account.deviceBlocked) "banned" else account.accountType
        )
        _accountInfo.value = normalized
        preferences.edit()
            .putString(ACCOUNT_ID_KEY, normalized.uid)
            .putString(ACCOUNT_NICKNAME_KEY, normalized.nickname)
            .putString(ACCOUNT_TYPE_KEY, normalized.accountType)
            .putString(ACCOUNT_EMAIL_KEY, normalized.email)
            .putString(ACCOUNT_BRAND_KEY, normalized.brand)
            .putString(ACCOUNT_MODEL_KEY, normalized.model)
            .putString(ACCOUNT_ANDROID_VERSION_KEY, normalized.androidVersion)
            .putInt(ACCOUNT_ANDROID_SDK_KEY, normalized.androidSdk)
            .putString(ACCOUNT_APP_VERSION_KEY, normalized.appVersion)
            .putBoolean(ACCOUNT_DEVICE_BLOCKED_KEY, normalized.deviceBlocked)
            .apply()
    }

    private fun clearLocalAccount() {
        preferences.edit().clear().apply()
        _accountInfo.value = null
    }

    private companion object {
        const val ACCOUNT_ID_KEY = "account_id"
        const val ACCOUNT_NICKNAME_KEY = "account_nickname"
        const val ACCOUNT_TYPE_KEY = "account_type"
        const val ACCOUNT_EMAIL_KEY = "account_email"
        const val ACCOUNT_BRAND_KEY = "account_brand"
        const val ACCOUNT_MODEL_KEY = "account_model"
        const val ACCOUNT_ANDROID_VERSION_KEY = "account_android_version"
        const val ACCOUNT_ANDROID_SDK_KEY = "account_android_sdk"
        const val ACCOUNT_APP_VERSION_KEY = "account_app_version"
        const val ACCOUNT_DEVICE_BLOCKED_KEY = "account_device_blocked"
    }
}
