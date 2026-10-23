package com.denova.novara.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionUiState {
    data object Empty : ConnectionUiState
    data class Checking(val server: ServerKey) : ConnectionUiState
    data class Unauthenticated(val server: ServerKey, val local: Boolean) : ConnectionUiState
    data class Authenticated(val server: ServerKey, val local: Boolean) : ConnectionUiState
    data class Failed(val message: String) : ConnectionUiState
}

/**
 * 连接与认证状态机：服务器输入 → status → 登录/配对 → 已认证。
 * 切换服务器即重建 [DenovaClient]，隔离会话 Cookie。
 */
class ConnectionViewModel(
    private val createClient: (ServerKey) -> DenovaClient = { DenovaClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Empty)
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private var client: DenovaClient? = null

    fun connect(input: String) {
        val key = ServerKey.parse(input)
        if (key == null) {
            _state.value = ConnectionUiState.Failed("无效的服务器地址")
            return
        }
        client = createClient(key)
        checkStatus(key)
    }

    fun login(username: String, password: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        viewModelScope.launch {
            try {
                val result = c.login(username.trim(), password)
                _actionError.value = null
                _state.value = ConnectionUiState.Authenticated(current, result.local)
            } catch (e: DenovaApiException) {
                _actionError.value = when (e.code) {
                    "api.access.invalidCredentials" -> "用户名或密码错误"
                    "api.access.lanDisabled" -> "服务器未开启局域网访问"
                    else -> e.message ?: "登录失败"
                }
            } catch (e: Exception) {
                _actionError.value = "网络错误，请重试"
            }
        }
    }

    fun pair(token: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        viewModelScope.launch {
            try {
                val result = c.pair(token.trim())
                _actionError.value = null
                _state.value = ConnectionUiState.Authenticated(current, result.local)
            } catch (e: DenovaApiException) {
                _actionError.value = when (e.code) {
                    "api.access.pairingInvalid" -> "配对链接无效或已过期"
                    "api.access.lanDisabled" -> "服务器未开启局域网访问"
                    else -> e.message ?: "配对失败"
                }
            } catch (e: Exception) {
                _actionError.value = "网络错误，请重试"
            }
        }
    }

    fun logout() {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Authenticated)?.server ?: return
        viewModelScope.launch {
            try {
                c.logout()
            } catch (_: Exception) {
                // 退出失败不阻塞本地回到未认证态；服务端会话撤销失败属已知边界。
            }
            _state.value = ConnectionUiState.Unauthenticated(current, local = false)
        }
    }

    private fun checkStatus(key: ServerKey) {
        viewModelScope.launch {
            _state.value = ConnectionUiState.Checking(key)
            try {
                val status = client!!.status()
                _state.value = if (status.authenticated) {
                    ConnectionUiState.Authenticated(key, status.local)
                } else {
                    ConnectionUiState.Unauthenticated(key, status.local)
                }
            } catch (e: DenovaApiException) {
                _state.value = ConnectionUiState.Failed(e.message ?: "无法连接服务器（HTTP ${e.status}）")
            } catch (e: Exception) {
                _state.value = ConnectionUiState.Failed("无法连接服务器")
            }
        }
    }
}
