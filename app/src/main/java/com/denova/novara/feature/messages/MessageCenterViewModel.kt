package com.denova.novara.feature.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.messages.MessageCenterLogic
import com.denova.novara.core.messages.MessageFilter
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.ProductMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 消息中心界面状态（P4-D / MSG-001…003）。 */
data class MessageCenterUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val items: List<ProductMessage> = emptyList(),
    val unread: Int = 0,
    val filter: MessageFilter = MessageFilter.ALL,
    val activeId: String = "",
    val markingAll: Boolean = false,
    /** 宽屏（`lg`）才自动选中第一条；窄屏列表与详情互斥。 */
    val wideLayout: Boolean = false,
) {
    val visible: List<ProductMessage> get() = MessageCenterLogic.filtered(items, filter)
    val active: ProductMessage? get() = items.firstOrNull { it.id == activeId }
}

/**
 * 消息中心：列表 / 详情 / 已读 / 全部已读 / 跳回自动化。
 *
 * 与基线一致的边界：打开列表不置已读；进入某条详情且它未读时才 `read`；
 * 窄屏列表与详情互斥，返回列表时保持选中 id 以便恢复焦点。
 */
class MessageCenterViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(MessageCenterUiState())
    val state: StateFlow<MessageCenterUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        if (this.client === client) return
        this.client = client
        _state.value = MessageCenterUiState(filter = _state.value.filter, wideLayout = _state.value.wideLayout)
        load()
    }

    fun load() {
        val c = client ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val page = c.messages()
                val items = MessageCenterLogic.sorted(page.items)
                _state.value = _state.value.copy(
                    loading = false,
                    items = items,
                    unread = page.unreadCount.takeIf { it > 0 } ?: MessageCenterLogic.unreadCount(items),
                    activeId = _state.value.activeId.takeIf { id -> items.any { it.id == id } }.orEmpty(),
                )
                DenovaLog.i("Messages", "loaded items=${items.size} unread=${_state.value.unread}")
            } catch (e: Exception) {
                DenovaLog.w("Messages", "load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.messages_load_failed),
                )
            }
        }
    }

    fun setFilter(filter: MessageFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun setWideLayout(wide: Boolean) {
        if (_state.value.wideLayout == wide) return
        _state.value = _state.value.copy(wideLayout = wide)
    }

    /** 进入详情：未读时写 `read`，失败回滚本地状态并如实提示。 */
    fun open(id: String) {
        _state.value = _state.value.copy(activeId = id, error = null)
        val message = _state.value.items.firstOrNull { it.id == id } ?: return
        DenovaLog.i(
            "Messages",
            "open id=${id.take(24)} bodyLen=${message.body.length} " +
                "renderedLen=${MessageCenterLogic.bodyText(message).length}",
        )
        if (!MessageCenterLogic.shouldMarkRead(message)) return
        val c = client ?: return
        viewModelScope.launch {
            try {
                val updated = c.markMessageRead(id)
                _state.value = _state.value.copy(
                    items = MessageCenterLogic.applyRead(_state.value.items, id, updated.readAt.ifBlank { nowIso() }),
                    unread = (_state.value.unread - 1).coerceAtLeast(0),
                )
                DenovaLog.i("Messages", "read id=${id.take(24)}")
            } catch (e: Exception) {
                DenovaLog.w("Messages", "read failed id=${id.take(24)}", e)
                _state.value = _state.value.copy(error = failureText(e, R.string.messages_read_failed))
            }
        }
    }

    /** 离开详情（窄屏返回列表）；保留 `activeId` 之外的界面状态不变。 */
    fun closeDetail() {
        _state.value = _state.value.copy(activeId = "")
    }

    /** 全部已读：未读为 0 或进行中时禁用（基线同一门控）。 */
    fun markAllRead() {
        val c = client ?: return
        if (_state.value.unread <= 0 || _state.value.markingAll) return
        viewModelScope.launch {
            _state.value = _state.value.copy(markingAll = true, error = null)
            try {
                val page = c.markAllMessagesRead()
                _state.value = _state.value.copy(
                    markingAll = false,
                    items = MessageCenterLogic.markAllRead(_state.value.items, nowIso()),
                    unread = page.unreadCount,
                    notice = UiText.of(R.string.messages_marked_all),
                )
                DenovaLog.i("Messages", "marked all read")
            } catch (e: Exception) {
                DenovaLog.w("Messages", "mark all failed", e)
                _state.value = _state.value.copy(
                    markingAll = false,
                    error = failureText(e, R.string.messages_read_failed),
                )
            }
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    private fun nowIso(): String =
        java.time.Instant.now().toString()

    private fun failureText(error: Exception, fallback: Int): UiText =
        (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }?.let(UiText::raw)
            ?: UiText.of(fallback)
}
