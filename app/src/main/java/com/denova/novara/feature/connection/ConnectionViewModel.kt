package com.denova.novara.feature.connection

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.persistence.ConnectionStore
import com.denova.novara.core.persistence.CookieStore
import com.denova.novara.core.persistence.PersistentCookieJar
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionUiState {
    data object Empty : ConnectionUiState
    data class Checking(val server: ServerKey) : ConnectionUiState
    data class Unauthenticated(val server: ServerKey, val local: Boolean) : ConnectionUiState
    data class Authenticated(val server: ServerKey, val local: Boolean) : ConnectionUiState
    data class Failed(val message: UiText) : ConnectionUiState
}

/**
 * 连接与认证状态机：服务器输入 → status → 登录/配对 → 已认证。
 * 切换服务器即重建 [DenovaClient]，隔离会话 Cookie。
 */
class ConnectionViewModel(
    application: Application,
    private val cookieStore: CookieStore = CookieStore(File(application.filesDir, "cookies")),
    private val createClient: (ServerKey) -> DenovaClient = { key ->
        // 会话 Cookie 落盘（按 origin 隔离）：进程被杀后重启可直接复用登录态，
        // 缩短终端 attached=0 的窗口（G6 决策 C）。退出登录时清空。
        DenovaClient(key, PersistentCookieJar(key.origin, cookieStore))
    },
) : AndroidViewModel(application) {

    private val store = ConnectionStore(application)

    private val _state = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Empty)
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private val _actionError = MutableStateFlow<UiText?>(null)
    val actionError: StateFlow<UiText?> = _actionError.asStateFlow()

    private val _savedServer = MutableStateFlow<String?>(null)
    val savedServer: StateFlow<String?> = _savedServer.asStateFlow()

    /** 当前连接的 client；登录成功后由上层取出绑定给编辑器。 */
    var client: DenovaClient? = null
        private set

    init {
        viewModelScope.launch {
            _savedServer.value = store.loadServer()
        }
    }

    fun connect(input: String) {
        val key = ServerKey.parse(input)
        if (key == null) {
            DenovaLog.w("Connection", "rejected invalid server address")
            _state.value = ConnectionUiState.Failed(UiText.of(R.string.conn_err_invalid_server))
            return
        }
        DenovaLog.i("Connection", "connecting to ${key.origin}")
        val created = createClient(key)
        // 任何请求收到 401 都视为会话失效：回到连接页并说明原因，
        // 而不是把服务端那句「请先输入远程访问用户名和密码」直接抛给用户。
        created.onUnauthorized = { handleSessionExpired(created, key) }
        client = created
        viewModelScope.launch { store.saveServer(input) }
        checkStatus(key)
    }

    /** 会话失效（401）：只处理当前客户端，切回未认证态并给出可读提示。 */
    private fun handleSessionExpired(c: DenovaClient, key: ServerKey) {
        if (c !== client) return
        DenovaLog.w("Connection", "session expired, returning to login")
        viewModelScope.launch {
            _state.value = ConnectionUiState.Unauthenticated(key, local = false)
            _actionError.value = UiText.of(R.string.conn_session_expired)
        }
    }

    fun login(username: String, password: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        viewModelScope.launch {
            val gen = c.generation.snapshot()
            try {
                // D03 恢复耗时埋点：认证往返起点
                DenovaLog.i("Perf", "login-start")
                // 登录接口的 401 表示密码错误，不能当作「会话过期」。
                val result = c.suppressingUnauthorized { c.login(username.trim(), password) }
                if (!c.generation.isCurrent(gen)) {
                    DenovaLog.i("Connection", "stale login result ignored gen=$gen")
                    return@launch
                }
                // 新登录成为当前世代：登录前发出的 status / 旧 401 一律作废，避免把新会话顶掉。
                c.generation.retire()
                DenovaLog.i("Connection", "login ok local=${result.local}")
                c.resetUnauthorizedFlag()
                _actionError.value = null
                _state.value = ConnectionUiState.Authenticated(current, result.local)
            } catch (e: DenovaApiException) {
                DenovaLog.w("Connection", "login failed code=${e.code} status=${e.status}")
                _actionError.value = when (e.code) {
                    "api.access.invalidCredentials" -> UiText.of(R.string.conn_err_bad_credentials)
                    "api.access.lanDisabled" -> UiText.of(R.string.conn_err_lan_disabled)
                    else -> e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.conn_err_login_failed)
                }
            } catch (e: Exception) {
                DenovaLog.w("Connection", "login failed", e)
                _actionError.value = UiText.of(R.string.conn_err_network)
            }
        }
    }

    fun pair(token: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        viewModelScope.launch {
            try {
                val result = c.pair(token.trim())
                c.generation.retire()
                DenovaLog.i("Connection", "pair ok local=${result.local}")
                _actionError.value = null
                _state.value = ConnectionUiState.Authenticated(current, result.local)
            } catch (e: DenovaApiException) {
                DenovaLog.w("Connection", "pair failed code=${e.code} status=${e.status}")
                _actionError.value = when (e.code) {
                    "api.access.pairingInvalid" -> UiText.of(R.string.conn_err_pairing_invalid)
                    "api.access.lanDisabled" -> UiText.of(R.string.conn_err_lan_disabled)
                    else -> e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.conn_err_pair_failed)
                }
            } catch (e: Exception) {
                DenovaLog.w("Connection", "pair failed", e)
                _actionError.value = UiText.of(R.string.conn_err_network)
            }
        }
    }

    fun logout() {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Authenticated)?.server ?: return
        viewModelScope.launch {
            DenovaLog.i("Connection", "logout")
            try {
                c.logout()
            } catch (e: Exception) {
                // 退出失败不阻塞本地回到未认证态；服务端会话撤销失败属已知边界。
                DenovaLog.w("Connection", "logout failed", e)
            }
            // 本地持久化的会话 Cookie 必须一起清掉，否则「退出登录」在重启后形同没退
            (c.okHttpClient.cookieJar as? PersistentCookieJar)?.clear()
            _state.value = ConnectionUiState.Unauthenticated(current, local = false)
        }
    }

    private fun checkStatus(key: ServerKey) {
        viewModelScope.launch {
            val c = client ?: return@launch
            // AUTH-004：记下发出时的客户端与世代。换了服务器就是新客户端，旧客户端的迟到结果一律作废；
            // 同一客户端内再用世代判断（登录成功、退出登录都会推进世代）。
            val gen = c.generation.snapshot()
            fun stale() = c !== client || !c.generation.isCurrent(gen)
            _state.value = ConnectionUiState.Checking(key)
            try {
                val status = c.status()
                if (stale()) {
                    DenovaLog.i(
                        "Connection",
                        "stale status ignored gen=$gen current=${c.generation.snapshot()} authenticated=${status.authenticated}",
                    )
                    return@launch
                }
                DenovaLog.i("Connection", "status ok authenticated=${status.authenticated} local=${status.local}")
                _state.value = if (status.authenticated) {
                    ConnectionUiState.Authenticated(key, status.local)
                } else {
                    ConnectionUiState.Unauthenticated(key, status.local)
                }
            } catch (e: DenovaApiException) {
                if (stale()) {
                    DenovaLog.i(
                        "Connection",
                        "stale status error ignored gen=$gen current=${c.generation.snapshot()} status=${e.status}",
                    )
                    return@launch
                }
                DenovaLog.w("Connection", "status failed code=${e.code} status=${e.status}")
                _state.value = ConnectionUiState.Failed(e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.conn_err_unreachable, e.status))
            } catch (e: Exception) {
                if (stale()) {
                    DenovaLog.i("Connection", "stale status failure ignored gen=$gen")
                    return@launch
                }
                DenovaLog.w("Connection", "status failed", e)
                _state.value = ConnectionUiState.Failed(UiText.of(R.string.conn_err_cannot_connect))
            }
        }
    }
}
