package com.denova.novara.feature.connection

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.auth.SessionExpiryConfirmer
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

    /** 登录 / 配对提交中（按钮禁用、文案切换）。 */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    /** 最近一次连接使用的地址原文；重试与网络恢复时复用它（AUTH-007 保留输入）。 */
    private var lastInput: String? = null

    /** 当前连接的 client；登录成功后由上层取出绑定给编辑器。 */
    var client: DenovaClient? = null
        private set

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)

    /**
     * 网络恢复后自动重连（对齐 Web 的 online / pageshow / visibilitychange 重拉 status）。
     * 只在失败态触发：已认证时重复拉 status 没有收益，未认证时用户可能正在输入密码。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (_state.value is ConnectionUiState.Failed) {
                DenovaLog.i("Connection", "network available, retrying")
                retry()
            }
        }
    }

    init {
        viewModelScope.launch {
            _savedServer.value = store.loadServer()
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { DenovaLog.w("Connection", "network callback unavailable", it) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    fun connect(input: String) {
        val key = ServerKey.parse(input)
        if (key == null) {
            DenovaLog.w("Connection", "rejected invalid server address")
            _state.value = ConnectionUiState.Failed(UiText.of(R.string.conn_err_invalid_server))
            return
        }
        lastInput = input
        val existing = client
        if (existing != null && existing.serverOrigin == key.origin) {
            // 同一服务器：复用客户端，保留 Cookie 与在途请求，只重跑 status。
            DenovaLog.i("Connection", "reconnecting to ${key.origin}")
            checkStatus(key)
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

    /** 重试（连接页错误块 / 网络恢复）：复用已保存的地址原文，不要求用户重新输入。 */
    fun retry() {
        val input = lastInput ?: return
        connect(input)
    }

    /**
     * 更换服务器（AUTH-006）：回到地址输入态。
     *
     * 会话 Cookie 按 origin 落盘，切换后互不影响；当前 client 丢弃，旧会话的在途回执因
     * `c !== client` 被判为过期，不会把新连接状态覆盖掉。
     */
    fun changeServer() {
        client?.generation?.retire()
        client = null
        _actionError.value = null
        _state.value = ConnectionUiState.Empty
    }

    /**
     * 会话失效（401）：只处理当前客户端，且必须先用 canonical status 二次确认。
     *
     * 迟到的 401 可能来自登录完成前发出的旧请求（登录成功后 `resetUnauthorizedFlag()` 会复位一次性标志），
     * 不确认就直接降级会把刚建立的会话顶掉（AUTH-004）。
     */
    private fun handleSessionExpired(c: DenovaClient, key: ServerKey) {
        if (c !== client) return
        viewModelScope.launch {
            val confirmer = SessionExpiryConfirmer {
                runCatching {
                    // 探测本身要抑制 401 回调，否则会递归触发确认。
                    c.suppressingUnauthorized { c.status().authenticated }
                }.getOrElse { error ->
                    when {
                        error is DenovaApiException && error.status == 401 -> false
                        else -> null
                    }
                }
            }
            if (c !== client) return@launch
            if (!confirmer.isExpired()) {
                c.resetUnauthorizedFlag()
                DenovaLog.i("Connection", "late 401 ignored, session still valid")
                return@launch
            }
            DenovaLog.w("Connection", "session expired, returning to login")
            _state.value = ConnectionUiState.Unauthenticated(key, local = false)
            _actionError.value = UiText.of(R.string.conn_session_expired)
        }
    }

    fun login(username: String, password: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        if (_submitting.value || username.isBlank() || password.isEmpty()) return
        viewModelScope.launch {
            val gen = c.generation.snapshot()
            _submitting.value = true
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
            } finally {
                _submitting.value = false
            }
        }
    }

    fun pair(token: String) {
        val c = client ?: return
        val current = (state.value as? ConnectionUiState.Unauthenticated)?.server ?: return
        if (_submitting.value || token.isBlank()) return
        viewModelScope.launch {
            _submitting.value = true
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
            } finally {
                _submitting.value = false
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
