package io.github.HanJason233.chimera.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.VpnState

/**
 * VPN服务管理器 - 单例
 */
object ServiceManager {
    
    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()
    
    private val _currentNode = MutableStateFlow<Node?>(null)
    val currentNode: StateFlow<Node?> = _currentNode.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // 移动网络提醒
    private val _cellularWarning = MutableStateFlow<String?>(null)
    val cellularWarning: StateFlow<String?> = _cellularWarning.asStateFlow()
    
    /**
     * 启动VPN连接
     */
    fun startVpn(context: Context, node: Node, proxyMode: ProxyMode) {
        _vpnState.value = VpnState.CONNECTING
        _currentNode.value = node
        _errorMessage.value = null
        
        val intent = Intent(context, BoxVpnService::class.java).apply {
            action = BoxVpnService.ACTION_START
            putExtra(BoxVpnService.EXTRA_NODE_RAW_LINK, node.getRawLinkPlain())
            putExtra(BoxVpnService.EXTRA_NODE_NAME, node.name)
            putExtra(BoxVpnService.EXTRA_NODE_GROUP_NAME, node.getConnectionGroupName())
            putExtra(BoxVpnService.EXTRA_PROXY_MODE, proxyMode.name)
        }
        
        // 使用 startForegroundService 启动前台服务
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    /**
     * 停止VPN连接
     */
    fun stopVpn(context: Context) {
        _vpnState.value = VpnState.DISCONNECTING
        
        val intent = Intent(context, BoxVpnService::class.java).apply {
            action = BoxVpnService.ACTION_STOP
        }
        
        context.startService(intent)
    }
    
    /**
     * 通知状态变化（由BoxVpnService调用）
     */
    fun notifyStateChange() {
        if (BoxVpnService.isRunning) {
            _vpnState.value = VpnState.CONNECTED
        } else {
            if (_vpnState.value == VpnState.CONNECTING && _currentNode.value != null) {
                return
            }
            _vpnState.value = VpnState.DISCONNECTED
            _currentNode.value = null
        }
    }

    /** Keep the session node while the service tears down for an internal reload. */
    fun notifyRestarting() {
        if (_currentNode.value != null) {
            _vpnState.value = VpnState.CONNECTING
        }
    }

    fun notifyConnected(node: Node) {
        _currentNode.value = node
        _vpnState.value = VpnState.CONNECTED
    }

    fun notifyStopped() {
        _vpnState.value = VpnState.DISCONNECTED
        _currentNode.value = null
    }
    
    /**
     * 通知错误（由BoxVpnService调用）
     */
    fun notifyError(message: String) {
        _errorMessage.value = message
        _vpnState.value = VpnState.DISCONNECTED
        _currentNode.value = null
    }
    
    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * 通知切换到移动网络（由 VPN 服务调用）
     */
    fun notifyCellularNetwork() {
        if (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING) {
            _cellularWarning.value = "使用移动网络中，请注意流量消耗"
        }
    }
    
    /**
     * 清除移动网络提醒
     */
    fun clearCellularWarning() {
        _cellularWarning.value = null
    }
    
}
