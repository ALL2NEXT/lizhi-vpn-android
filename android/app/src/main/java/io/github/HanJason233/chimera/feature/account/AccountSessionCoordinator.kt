package io.github.HanJason233.chimera.feature.account

import android.util.Log
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.model.AccountInfo
import io.github.HanJason233.chimera.data.model.ClientNotification
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.network.account.AccountManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns account actions, startup authentication state, and notification state. */
internal class AccountSessionCoordinator(
    private val scope: CoroutineScope,
    private val accountManager: AccountManager,
    private val serviceRequestTimeoutMs: StateFlow<Long>,
    private val vpnState: StateFlow<VpnState>,
    private val onError: (String) -> Unit,
    private val refreshSubscriptions: suspend (Boolean) -> Boolean,
    private val loadStartupData: suspend () -> Unit,
    private val clearLocalData: suspend () -> Unit
) {
    private val tag = "AccountSessionCoordinator"

    val accountInfo = accountManager.accountInfo
    private val _clientNotifications = MutableStateFlow<List<ClientNotification>>(emptyList())
    val clientNotifications = _clientNotifications.asStateFlow()
    private val _notificationUnreadCount = MutableStateFlow(0)
    val notificationUnreadCount = _notificationUnreadCount.asStateFlow()
    private val _isLoadingNotifications = MutableStateFlow(false)
    val isLoadingNotifications = _isLoadingNotifications.asStateFlow()
    private val _notificationError = MutableStateFlow<String?>(null)
    val notificationError = _notificationError.asStateFlow()
    private var notificationLoadJob: kotlinx.coroutines.Job? = null
    private var notificationScopeKey = ""
    private var lastNotificationLoadAt = 0L
    private var lastOverviewSyncUid = ""
    private var lastOverviewSyncAt = 0L
    private val _offlineModeEnabled = MutableStateFlow(accountManager.isOfflineMode())
    val offlineModeEnabled = _offlineModeEnabled.asStateFlow()
    private val _isActivatingVip = MutableStateFlow(false)
    val isActivatingVip = _isActivatingVip.asStateFlow()
    private val _isUpdatingNickname = MutableStateFlow(false)
    val isUpdatingNickname = _isUpdatingNickname.asStateFlow()
    private val _isSyncingAccount = MutableStateFlow(false)
    val isSyncingAccount = _isSyncingAccount.asStateFlow()
    private val _isStartupAccountActionInProgress = MutableStateFlow(false)
    val isStartupAccountActionInProgress = _isStartupAccountActionInProgress.asStateFlow()
    private val _startupAccountAuthenticated = MutableStateFlow(
        accountManager.currentAccount() != null || accountManager.isOfflineMode()
    )
    val startupAccountAuthenticated = _startupAccountAuthenticated.asStateFlow()
    private val _isBindingAccount = MutableStateFlow(false)
    val isBindingAccount = _isBindingAccount.asStateFlow()
    private val _isSwitchingAccount = MutableStateFlow(false)
    val isSwitchingAccount = _isSwitchingAccount.asStateFlow()
    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount = _isDeletingAccount.asStateFlow()
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage = _accountMessage.asStateFlow()

    fun updateNickname(nickname: String) {
        if (_isUpdatingNickname.value || _isActivatingVip.value) return
        scope.launch {
            _isUpdatingNickname.value = true
            _accountMessage.value = null
            try {
                accountManager.updateNickname(
                    nickname = nickname,
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onFailure { error ->
                    Log.w(tag, "Failed to update nickname", error)
                    val message = error.message ?: "昵称修改失败"
                    onError(message)
                    _accountMessage.value = message
                }.onSuccess {
                    _accountMessage.value = "昵称已更新"
                }
            } finally {
                _isUpdatingNickname.value = false
            }
        }
    }

    fun activateVipCard(code: String) {
        if (
            _isActivatingVip.value || _isSyncingAccount.value || _isUpdatingNickname.value ||
            _isBindingAccount.value || _isSwitchingAccount.value || _isDeletingAccount.value
        ) return
        scope.launch {
            _isActivatingVip.value = true
            _accountMessage.value = null
            try {
                accountManager.activateVipCard(
                    code = code,
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onSuccess {
                    _accountMessage.value = "VIP 激活成功，有效期已更新"
                    refreshSubscriptions(true)
                }.onFailure { error ->
                    Log.w(tag, "Failed to activate VIP card", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
            } finally {
                _isActivatingVip.value = false
            }
        }
    }

    fun syncCurrentAccount() {
        if (
            _isSyncingAccount.value ||
            _isUpdatingNickname.value ||
            _isBindingAccount.value ||
            _isSwitchingAccount.value ||
            _isDeletingAccount.value ||
            _isActivatingVip.value
        ) return
        scope.launch {
            _isSyncingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.syncCurrentAccount(
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onSuccess {
                    _accountMessage.value = "账户信息已同步"
                    refreshSubscriptions(true)
                }.onFailure { error ->
                    Log.w(tag, "Failed to sync account information", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
            } finally {
                _isSyncingAccount.value = false
            }
        }
    }

    /** 移除顶栏刷新图标后，在打开“我的”页时低频同步账户状态。 */
    fun syncAccountOnOverviewEntry() {
        val current = accountManager.currentAccount() ?: return
        if (accountManager.isOfflineMode() || _isSyncingAccount.value ||
            _isUpdatingNickname.value || _isBindingAccount.value ||
            _isSwitchingAccount.value || _isDeletingAccount.value || _isActivatingVip.value
        ) return

        val now = System.currentTimeMillis()
        if (lastOverviewSyncUid != current.uid) {
            lastOverviewSyncUid = current.uid
            lastOverviewSyncAt = 0L
        }
        if (now - lastOverviewSyncAt < ACCOUNT_OVERVIEW_SYNC_COOLDOWN_MS) return
        lastOverviewSyncAt = now

        scope.launch {
            val previous = accountManager.currentAccount()
            _isSyncingAccount.value = true
            try {
                accountManager.syncCurrentAccount(
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onSuccess { synced ->
                    val accessChanged = previous == null ||
                        previous.accountType != synced.accountType ||
                        previous.deviceBlocked != synced.deviceBlocked ||
                        previous.vipActive != synced.vipActive ||
                        previous.vipExpiresAt != synced.vipExpiresAt
                    if (accessChanged) {
                        refreshSubscriptions(true)
                    }
                }.onFailure { error ->
                    Log.w(tag, "Failed to sync account when opening My page", error)
                }
            } finally {
                _isSyncingAccount.value = false
            }
        }
    }

    fun refreshClientNotifications(force: Boolean = false) {
        if (accountManager.isOfflineMode()) {
            clearClientNotifications()
            return
        }
        val account = accountManager.currentAccount() ?: run {
            clearClientNotifications()
            return
        }
        val accountScope = "${AppConfig.BACKEND_URL.trimEnd('/')}|${account.uid}"
        if (accountScope != notificationScopeKey) {
            notificationLoadJob?.cancel()
            notificationScopeKey = accountScope
            lastNotificationLoadAt = 0L
            _clientNotifications.value = emptyList()
            _notificationUnreadCount.value = 0
            _notificationError.value = null
        }
        if (notificationLoadJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationLoadAt < NOTIFICATION_REFRESH_COOLDOWN_MS) return
        lastNotificationLoadAt = now
        _isLoadingNotifications.value = true
        _notificationError.value = null
        notificationLoadJob = scope.launch {
            try {
                accountManager.getClientNotifications().onSuccess { feed ->
                    if (accountScope == currentNotificationScope()) {
                        _clientNotifications.value = feed.items
                        _notificationUnreadCount.value = feed.unreadCount.coerceAtLeast(0)
                    }
                }.onFailure { error ->
                    if (accountScope == currentNotificationScope()) {
                        Log.w(tag, "Failed to load client notifications", error)
                        _notificationError.value = "通知加载失败，请检查网络后重试。"
                    }
                }
            } finally {
                if (accountScope == notificationScopeKey) _isLoadingNotifications.value = false
            }
        }
    }

    fun clearClientNotifications() {
        notificationLoadJob?.cancel()
        notificationLoadJob = null
        notificationScopeKey = ""
        lastNotificationLoadAt = 0L
        _clientNotifications.value = emptyList()
        _notificationUnreadCount.value = 0
        _isLoadingNotifications.value = false
        _notificationError.value = null
    }

    fun markClientNotificationRead(notificationId: Long) {
        val notification = _clientNotifications.value.firstOrNull { it.id == notificationId } ?: return
        if (notification.isRead) return
        _clientNotifications.value = _clientNotifications.value.map { item ->
            if (item.id == notificationId) item.copy(isRead = true) else item
        }
        _notificationUnreadCount.value = (_notificationUnreadCount.value - 1).coerceAtLeast(0)
        scope.launch {
            accountManager.markClientNotificationRead(notificationId).onFailure { error ->
                Log.w(tag, "Failed to mark notification as read", error)
                refreshClientNotifications(force = true)
            }
        }
    }

    fun markAllClientNotificationsRead() {
        if (_notificationUnreadCount.value <= 0) return
        _clientNotifications.value = _clientNotifications.value.map { it.copy(isRead = true) }
        _notificationUnreadCount.value = 0
        scope.launch {
            accountManager.markAllClientNotificationsRead().onFailure { error ->
                Log.w(tag, "Failed to mark all notifications as read", error)
                refreshClientNotifications(force = true)
            }
        }
    }

    private fun currentNotificationScope(): String =
        "${AppConfig.BACKEND_URL.trimEnd('/')}|${accountManager.currentAccount()?.uid.orEmpty()}"

    fun bindEmailPassword(
        email: String,
        password: String,
        registrationCode: String = "",
    ) {
        if (_isBindingAccount.value || _isActivatingVip.value) return
        scope.launch {
            _isBindingAccount.value = true
            _accountMessage.value = null
            try {
                accountManager.bindEmailPassword(
                    email = email,
                    password = password,
                    registrationCode = registrationCode,
                    timeoutMs = serviceRequestTimeoutMs.value
                ).onSuccess {
                    _accountMessage.value = "绑定成功，已获得绑定经验"
                    refreshSubscriptions(true)
                }.onFailure { error ->
                    Log.w(tag, "Failed to bind account credentials", error)
                    _accountMessage.value = accountBindingErrorMessage(error)
                }
            } finally {
                _isBindingAccount.value = false
            }
        }
    }

    fun loginAtStartup(email: String, password: String) {
        runStartupAccountAction {
            accountManager.loginAccount(
                email = email,
                password = password,
                timeoutMs = serviceRequestTimeoutMs.value
            ).getOrThrow()
        }
    }

    fun registerAtStartup(
        email: String,
        password: String,
        registrationCode: String
    ) {
        runStartupAccountAction {
            if (accountManager.currentAccount() == null) {
                accountManager.register(serviceRequestTimeoutMs.value).getOrThrow()
            }
            accountManager.bindEmailPassword(
                email = email,
                password = password,
                registrationCode = registrationCode,
                timeoutMs = serviceRequestTimeoutMs.value
            ).getOrThrow()
        }
    }

    fun continueAsGuestAtStartup() {
        runStartupAccountAction {
            accountManager.register(serviceRequestTimeoutMs.value).getOrThrow()
        }
    }

    fun continueOfflineAtStartup() {
        if (_isStartupAccountActionInProgress.value || _startupAccountAuthenticated.value) return
        // 仅保存本地状态并进入主界面，不调用账户或订阅后台端点。
        accountManager.setOfflineMode(true)
        _offlineModeEnabled.value = true
        _accountMessage.value = null
        _startupAccountAuthenticated.value = true
    }

    fun exitOfflineMode() {
        if (!accountManager.isOfflineMode()) return
        accountManager.setOfflineMode(false)
        _offlineModeEnabled.value = false
        _startupAccountAuthenticated.value = false
    }

    private fun runStartupAccountAction(action: suspend () -> AccountInfo) {
        if (_isStartupAccountActionInProgress.value || _startupAccountAuthenticated.value) return
        scope.launch {
            _isStartupAccountActionInProgress.value = true
            _accountMessage.value = null
            try {
                action()
                _offlineModeEnabled.value = accountManager.isOfflineMode()
                _startupAccountAuthenticated.value = true
                scope.launch { loadStartupData() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "Startup account action failed", error)
                _accountMessage.value = startupAccountErrorMessage(error)
            } finally {
                _isStartupAccountActionInProgress.value = false
            }
        }
    }

    fun clearAccountMessage() {
        _accountMessage.value = null
    }

    fun switchAccount(email: String, password: String) {
        if (_isSwitchingAccount.value || _isDeletingAccount.value || _isActivatingVip.value) return
        if (vpnState.value != VpnState.DISCONNECTED) {
            _accountMessage.value = "请先断开 VPN，再切换账户"
            return
        }
        scope.launch {
            _isSwitchingAccount.value = true
            _accountMessage.value = null
            try {
                val result = accountManager.loginAccount(
                    email = email,
                    password = password,
                    timeoutMs = serviceRequestTimeoutMs.value
                )
                result.onFailure { error ->
                    Log.w(tag, "Failed to switch account", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
                if (result.isSuccess) {

                    val refreshed = refreshSubscriptions(true)
                    _accountMessage.value = if (refreshed) {
                        "账户切换成功"
                    } else {
                        "账户切换成功，但订阅线路刷新失败"
                    }
                }
            } finally {
                _isSwitchingAccount.value = false
            }
        }
    }

    fun deleteAccount(password: String, onDeleted: () -> Unit = {}) {
        if (_isDeletingAccount.value || _isSwitchingAccount.value || _isActivatingVip.value) return
        if (vpnState.value != VpnState.DISCONNECTED) {
            _accountMessage.value = "请先断开 VPN，再注销账户"
            return
        }
        scope.launch {
            _isDeletingAccount.value = true
            _accountMessage.value = null
            try {
                val result = accountManager.deleteAccount(
                    password = password,
                    timeoutMs = serviceRequestTimeoutMs.value
                )
                result.onFailure { error ->
                    Log.w(tag, "Failed to delete account", error)
                    _accountMessage.value = accountActionErrorMessage(error)
                }
                if (result.isSuccess) {
                    runCatching { clearLocalData() }
                        .onFailure { error ->
                            Log.e(tag, "Failed to clear local application data after account deletion", error)
                        }
                    onDeleted()
                }
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    /** 注销成功后清除本地数据，避免退出前再次自动注册游客账户。 */
    private fun startupAccountErrorMessage(error: Throwable): String = when (error.message) {
        "invalid_email" -> "邮箱格式无效"
        "invalid_password" -> "密码长度需在 8～128 个字符之间"
        "device_banned" -> "当前设备已被封禁"
        "registration_disabled",
        "registration_code_required",
        "invalid_registration_code",
        "formal_registration_closed",
        "invalid_bind_credentials",
        "account_already_bound",
        "email_already_bound" -> accountBindingErrorMessage(error)
        else -> accountActionErrorMessage(error)
    }

    private fun accountBindingErrorMessage(error: Throwable): String {
        return when (error.message) {
            "registration_disabled" -> "用户注册暂未开放"
            "registration_code_required" -> "必须使用管理员发放的注册码注册"
            "invalid_registration_code" -> "注册码无效或已使用"
            "formal_registration_closed" -> "正式用户注册暂未开放"
            "invalid_bind_credentials" -> "邮箱或密码格式无效"
            "account_banned" -> "账户已被封禁，无法绑定"
            "account_already_bound" -> "账户已经绑定邮箱"
            "email_already_bound" -> "该邮箱已经绑定其他账户"
            "account_not_found" -> "账户不存在，请重新同步账户"
            else -> error.message ?: "账户绑定失败"
        }
    }

    private fun accountActionErrorMessage(error: Throwable): String {
        return when (error.message) {
            "offline_mode_enabled" -> "离线模式下无法访问后台服务"
            "backend_url_missing" -> "未配置服务地址"
            "invalid_login_credentials" -> "邮箱或密码错误"
            "invalid_account_password" -> "当前账户密码错误"
            "account_banned" -> "账户已被封禁，无法切换"
            "account_not_found" -> "账户不存在，请重新同步账户"
            "invalid_vip_activation_request" -> "VIP 卡密格式无效"
            "invalid_or_used_vip_card" -> "VIP 卡密无效或已兑换"
            "vip_activation_failed" -> "VIP 激活失败，请稍后重试"
            else -> error.message ?: "账户操作失败"
        }
    }

    private companion object {
        const val ACCOUNT_OVERVIEW_SYNC_COOLDOWN_MS = 60_000L
        const val NOTIFICATION_REFRESH_COOLDOWN_MS = 30_000L
    }
}
