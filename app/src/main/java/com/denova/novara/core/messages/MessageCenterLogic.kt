package com.denova.novara.core.messages

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.network.ProductMessage

/** 消息中心筛选（基线 `messages.filter.*`：全部 / 待办 / 自动化 / 产品）。 */
enum class MessageFilter { ALL, ACTION, AUTOMATION, PRODUCT }

/**
 * 消息中心的纯逻辑（P4-D / MSG-001…003）。
 *
 * 与基线的三处关键口径：
 * - 过滤完全在客户端做（服务端列表无参数）；
 * - 打开列表**不**置已读，只有进入详情且该条未读才 `read`（`MessageCenter` 的 `pendingReadRef` 语义）；
 * - 窄屏列表与详情互斥，返回时回到原条目（`activeId` 复位）。
 */
object MessageCenterLogic {

    private val AUTOMATION_TYPES = setOf("automation", "automation_action")
    private const val PRODUCT_TYPE = "changelog"

    fun matches(filter: MessageFilter, message: ProductMessage): Boolean = when (filter) {
        MessageFilter.ALL -> true
        MessageFilter.ACTION -> message.actionRequired
        MessageFilter.AUTOMATION -> message.type in AUTOMATION_TYPES
        MessageFilter.PRODUCT -> message.type == PRODUCT_TYPE
    }

    /** 已按发布时间倒序（服务端已排序，客户端兜底一次，避免类型混排时乱序）。 */
    fun sorted(items: List<ProductMessage>): List<ProductMessage> =
        items.sortedByDescending { it.publishedAt }

    fun filtered(items: List<ProductMessage>, filter: MessageFilter): List<ProductMessage> =
        sorted(items).filter { matches(filter, it) }

    fun unreadCount(items: List<ProductMessage>): Int = items.count { it.unread }

    /** 类型标签：Update / Automation / Automation · Action required；未知类型原样显示。 */
    fun typeLabel(message: ProductMessage): UiText = when {
        message.type == PRODUCT_TYPE -> UiText.of(R.string.messages_type_update)
        message.type == "automation_action" -> UiText.of(R.string.messages_type_action)
        message.type == "automation" -> UiText.of(R.string.messages_type_automation)
        else -> UiText.raw(message.type)
    }

    /**
     * 打开某条消息是否要发 `read`：已经读过或不存在的条目不重复写（幂等端点也不该被刷屏）。
     */
    fun shouldMarkRead(message: ProductMessage?): Boolean = message?.unread == true

    /**
     * 正文渲染：正文是 Markdown，服务端可能把换行写成 HTML 实体（`&#10;`）。
     *
     * 这里只还原实体、按纯文本展示（基线用 Markdown 富文本渲染，属已登记的视觉差异）。
     * 试过先 `MarkdownDocument.toPlainText()` 再展示，真机上 changelog 正文会渲染成空串
     * （同一入参在 JVM 单测里有内容），因此保留原文，宁可有 `###` 标记也不丢正文。
     */
    fun bodyText(message: ProductMessage): String = message.body
        .replace("&#10;", "\n")
        .replace("&#13;", "\n")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /** 列表项在过滤结果里的位置（返回后恢复焦点 / 滚动用）。 */
    fun indexOf(items: List<ProductMessage>, id: String): Int = items.indexOfFirst { it.id == id }

    /**
     * 标记已读后的本地投影：只改目标条目的 `readAt`，未读数随之减少。
     * 服务端回包只用于校准该条，不重建整页（避免打断用户滚动）。
     */
    fun applyRead(items: List<ProductMessage>, id: String, readAt: String): List<ProductMessage> =
        items.map { if (it.id == id) it.copy(readAt = readAt) else it }

    fun markAllRead(items: List<ProductMessage>, readAt: String): List<ProductMessage> =
        items.map { if (it.unread) it.copy(readAt = readAt) else it }

    /** 首次进入时选中的条目：第一条未读，否则第一条（桌面双栏行为；窄屏不预选）。 */
    fun firstSelection(items: List<ProductMessage>, wideLayout: Boolean): String {
        if (!wideLayout) return ""
        return items.firstOrNull { it.unread }?.id ?: items.firstOrNull()?.id.orEmpty()
    }
}
