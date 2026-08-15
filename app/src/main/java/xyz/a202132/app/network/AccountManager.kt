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
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.data.model.AccountBindingRequest
import xyz.a202132.app.data.model.AccountDeleteRequest
import xyz.a202132.app.data.model.AccountInviteRequest
import xyz.a202132.app.data.model.AccountLoginRequest
import xyz.a202132.app.data.model.AccountProfileUpdateRequest
import xyz.a202132.app.data.model.AccountRegisterRequest
import xyz.a202132.app.data.model.SpeedShareNodeResult
import xyz.a202132.app.data.model.SpeedShareRequest
import xyz.a202132.app.data.model.SpeedShareResponse
import xyz.a202132.app.data.model.SpeedShareResultsResponse
import xyz.a202132.app.data.model.SpeedShareStatus
import xyz.a202132.app.data.model.TelegramTaskResponse
import xyz.a202132.app.data.model.TelegramTaskStatus
import xyz.a202132.app.data.model.TelegramTaskSubmitRequest
import xyz.a202132.app.data.model.TaskCenterStatus
import xyz.a202132.app.data.model.TrafficReportRequest
import xyz.a202132.app.data.model.TrafficReportResponse
import java.security.MessageDigest
import java.util.UUID

/**
 * Registers this installation with the Worker and uploads acknowledged daily traffic batches.
 * Android 10+ does not expose a reliable Wi-Fi MAC to ordinary apps, so the account key is a
 * SHA-256 hash of ANDROID_ID (with a persisted UUID fallback). The raw identifier never leaves
 * the device.
 */
class AccountManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(
        "account_identity",
        Context.MODE_PRIVATE
    )

    private val _accountInfo = MutableStateFlow(loadPersistedAccount())
    val accountInfo = _accountInfo.asStateFlow()
    fun currentAccount(): AccountInfo? = _accountInfo.value

    fun accountId(): String = deviceKey()

    suspend fun register(
        timeoutMs: Long = 25000L
    ): Result<AccountInfo> {
        if (AppConfig.BACKEND_URL.isBlank()) {
            return Result.failure(IllegalStateException("未配置后台地址"))
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
            val registered = response.account ?: throw IllegalStateException(
                response.error ?: "后台未返回账户信息"
            )
            persistAccount(registered)
            registered
        }
    }

    suspend fun syncCurrentAccount(timeoutMs: Long = 25000L): Result<AccountInfo> {
        return register(timeoutMs = timeoutMs)
    }

    suspend fun updateNickname(
        nickname: String,
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        if (currentAccount() == null) {
            return Result.failure(IllegalStateException("账户尚未同步"))
        }
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank() || normalizedNickname.length > 24) {
            return Result.failure(IllegalArgumentException("昵称长度需要在 1～24 个字符之间"))
        }

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.updateAccountProfile(
                    accountEndpoint("/api/account/profile"),
                    AccountProfileUpdateRequest(
                        deviceId = accountId(),
                        nickname = normalizedNickname
                    )
                )
            }
            val updated = response.account ?: throw IllegalStateException(
                response.error ?: "后台未返回账户信息"
            )
            persistAccount(updated)
            updated
        }
    }

    suspend fun getTaskCenterStatus(
        timeoutMs: Long = 10000L
    ): Result<TaskCenterStatus> {
        if (currentAccount() == null) {
            return Result.failure(IllegalStateException("账户尚未同步"))
        }

        return runCatching {
            val response: TelegramTaskResponse = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.getTelegramTask(
                    accountEndpoint("/api/account/tasks?deviceId=${accountId()}")
                )
            }
            response.account?.let(::persistAccount)
            TaskCenterStatus(
                speedShare = response.speedShare ?: SpeedShareStatus(),
                telegram = response.telegram ?: throw IllegalStateException(
                    response.error ?: "后台未返回 TG 任务状态"
                )
            )
        }
    }

    suspend fun reportSpeedResults(
        results: List<SpeedShareNodeResult>,
        timeoutMs: Long = 15000L
    ): Result<SpeedShareResponse> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("璐︽埛灏氭湭鍚屾"))
        if (registered.level == 0) {
            return Result.failure(IllegalStateException("account_banned"))
        }
        if (results.isEmpty()) {
            return Result.failure(IllegalArgumentException("speed_share_results_empty"))
        }

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 60000L)) {
                NetworkClient.apiService.shareSpeedResults(
                    accountEndpoint("/api/account/tasks/speed-share"),
                    SpeedShareRequest(
                        deviceId = accountId(),
                        results = results.take(1)
                    )
                )
            }
            if (!response.ok) {
                val error = response.error ?: "speed_share_submission_failed"
                throw IllegalStateException(error)
            }
            response.account?.let(::persistAccount)
            response
        }
    }

    suspend fun getSpeedShareResults(
        timeoutMs: Long = 10000L
    ): Result<SpeedShareResultsResponse> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("account_not_synced"))
        if (registered.level == 0) {
            return Result.failure(IllegalStateException("account_banned"))
        }

        return runCatching {
            val response: SpeedShareResultsResponse = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.getSpeedShareResults(
                    accountEndpoint(
                        "/api/account/tasks/speed-share-results?deviceId=${accountId()}"
                    )
                )
            }
            if (!response.ok) {
                throw IllegalStateException(response.error ?: "speed_share_results_load_failed")
            }
            response
        }
    }

    suspend fun submitTelegramTask(
        telegramCode: String,
        timeoutMs: Long = 10000L
    ): Result<TelegramTaskStatus> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("账户尚未同步"))
        val normalizedCode = telegramCode.trim()
        if (normalizedCode.isBlank() || normalizedCode.length > 128) {
            return Result.failure(IllegalArgumentException("invalid_telegram_task_code"))
        }
        if (registered.level == 0) {
            return Result.failure(IllegalStateException("account_banned"))
        }

        return runCatching {
            val response: TelegramTaskResponse = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.submitTelegramTask(
                    accountEndpoint("/api/account/tasks/telegram"),
                    TelegramTaskSubmitRequest(
                        deviceId = accountId(),
                        code = normalizedCode
                    )
                )
            }
            response.account?.let(::persistAccount)
            response.telegram ?: throw IllegalStateException(
                response.error ?: "TG 任务提交失败"
            )
        }
    }

    suspend fun loginAccount(
        email: String,
        password: String,
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            return Result.failure(IllegalArgumentException("邮箱格式无效"))
        }
        if (password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("密码长度需要在 8～128 个字符之间"))
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
            val loggedIn = response.account ?: throw IllegalStateException(
                response.error ?: "账户登录失败"
            )
            persistAccount(loggedIn)
            loggedIn
        }
    }

    suspend fun bindEmailPassword(
        email: String,
        password: String,
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("账户尚未同步"))
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            return Result.failure(IllegalArgumentException("邮箱格式无效"))
        }
        if (password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("密码长度需要在 8～128 个字符之间"))
        }

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.bindAccount(
                    accountEndpoint("/api/account/bind"),
                    AccountBindingRequest(
                        deviceId = accountId(),
                        uid = registered.uid,
                        email = normalizedEmail,
                        password = password
                    )
                )
            }
            val updated = response.account ?: throw IllegalStateException(
                response.error ?: "账户绑定失败"
            )
            persistAccount(updated)
            updated
        }
    }

    suspend fun bindInviteCode(
        code: String,
        timeoutMs: Long = 10000L
    ): Result<AccountInfo> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("账户尚未同步"))
        val normalizedCode = code.trim().uppercase().replace("-", "").replace(" ", "")
        if (!Regex("^[A-HJ-NP-Z2-9]{8}$").matches(normalizedCode)) {
            return Result.failure(IllegalArgumentException("邀请码格式无效"))
        }
        if (registered.level == 0) {
            return Result.failure(IllegalStateException("account_banned"))
        }

        return runCatching {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.bindInviteCode(
                    accountEndpoint("/api/account/invite"),
                    AccountInviteRequest(
                        deviceId = accountId(),
                        code = normalizedCode
                    )
                )
            }
            val updated = response.account ?: throw IllegalStateException(
                response.error ?: "邀请码绑定失败"
            )
            persistAccount(updated)
            updated
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
            ?: return Result.failure(IllegalStateException("账户尚未注册"))

        if (registered.level == 0) {
            return Result.failure(IllegalStateException("账户已被封禁"))
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
            if (!response.ok) {
                throw IllegalStateException(response.error ?: "流量上报失败")
            }
            response
        }
    }

    suspend fun deleteAccount(
        password: String,
        timeoutMs: Long = 10000L
    ): Result<Unit> {
        val registered = currentAccount()
            ?: return Result.failure(IllegalStateException("账户尚未同步"))
        if (registered.email.isNotBlank() && password.length !in 8..128) {
            return Result.failure(IllegalArgumentException("请输入当前账户密码"))
        }

        return runCatching {
            val response: AccountActionResponse = withTimeout(timeoutMs.coerceIn(5000L, 30000L)) {
                NetworkClient.apiService.deleteAccount(
                    accountEndpoint("/api/account/delete"),
                    AccountDeleteRequest(
                        deviceId = accountId(),
                        uid = registered.uid,
                        password = password
                    )
                )
            }
            if (!response.ok) {
                throw IllegalStateException(response.error ?: "账户注销失败")
            }
            clearLocalAccount()
        }
    }

    private fun accountEndpoint(path: String): String =
        "${AppConfig.BACKEND_URL.trimEnd('/')}$path"

    private fun deviceKey(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
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

    private fun loadPersistedAccount(): AccountInfo? {
        val uid = preferences.getString(ACCOUNT_ID_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return AccountInfo(
            uid = uid,
            nickname = preferences.getString(ACCOUNT_NICKNAME_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?: "流萤用户",
            level = preferences.getInt(ACCOUNT_LEVEL_KEY, 1).coerceIn(0, 6),
            experience = preferences.getLong(ACCOUNT_EXPERIENCE_KEY, 0L).coerceAtLeast(0L),
            email = preferences.getString(ACCOUNT_EMAIL_KEY, "").orEmpty(),
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
            level = if (account.deviceBlocked) 0 else account.level.coerceIn(0, 6),
            experience = account.experience.coerceAtLeast(0L)
        )
        _accountInfo.value = normalized
        preferences.edit()
            .putString(ACCOUNT_ID_KEY, normalized.uid)
            .putString(ACCOUNT_NICKNAME_KEY, normalized.nickname)
            .putInt(ACCOUNT_LEVEL_KEY, normalized.level)
            .putLong(ACCOUNT_EXPERIENCE_KEY, normalized.experience)
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
        preferences.edit()
            .clear()
            .apply()
        _accountInfo.value = null
    }

    private companion object {
        const val ACCOUNT_ID_KEY = "account_id"
        const val ACCOUNT_NICKNAME_KEY = "account_nickname"
        const val ACCOUNT_LEVEL_KEY = "account_level"
        const val ACCOUNT_EXPERIENCE_KEY = "account_experience"
        const val ACCOUNT_EMAIL_KEY = "account_email"
        const val ACCOUNT_BRAND_KEY = "account_brand"
        const val ACCOUNT_MODEL_KEY = "account_model"
        const val ACCOUNT_ANDROID_VERSION_KEY = "account_android_version"
        const val ACCOUNT_ANDROID_SDK_KEY = "account_android_sdk"
        const val ACCOUNT_APP_VERSION_KEY = "account_app_version"
        const val ACCOUNT_DEVICE_BLOCKED_KEY = "account_device_blocked"
    }
}
