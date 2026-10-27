package com.denova.novara.core.workbench

import com.denova.novara.core.network.AgentChatHistoryItem
import com.denova.novara.core.network.AgentChatHistoryPage

/**
 * 工作台历史列表的分页与导出纯逻辑（P4-B / WB-006）。
 *
 * 与基线 `AgentChatSessionHistoryDialog.tsx` 对齐：一次取 80 条、`has_more` 决定「加载更多」、
 * 追加时按 `project_id + session_id` 去重（翻页与重载可能重叠，重复 key 会让列表抖动）。
 */
object HistoryList {

    /** 基线 `HISTORY_PAGE_SIZE`。 */
    const val PAGE_SIZE: Int = 80

    /** 基线 `HISTORY_SEARCH_MAX_LENGTH`：超长查询服务端不认，界面直接截断。 */
    const val QUERY_MAX_LENGTH: Int = 200

    /** 基线 `HISTORY_SEARCH_DELAY_MS`。 */
    const val SEARCH_DEBOUNCE_MS: Long = 100

    data class Page(
        val items: List<AgentChatHistoryItem> = emptyList(),
        val total: Int = 0,
        val hasMore: Boolean = false,
    ) {
        val offset: Int get() = items.size
    }

    /** 首屏：整体替换。 */
    fun replace(response: AgentChatHistoryPage): Page = Page(
        items = response.items.distinctBy { key(it) },
        total = response.total,
        hasMore = response.hasMore,
    )

    /** 追加一页：按 key 去重，`total` / `hasMore` 以服务端最新为准。 */
    fun append(current: Page, response: AgentChatHistoryPage): Page {
        val merged = LinkedHashMap<String, AgentChatHistoryItem>(current.items.size + response.items.size)
        current.items.forEach { merged[key(it)] = it }
        response.items.forEach { merged[key(it)] = it }
        return Page(
            items = merged.values.toList(),
            total = response.total,
            hasMore = response.hasMore,
        )
    }

    fun key(item: AgentChatHistoryItem): String = "${item.projectId}\u0000${item.session.id}"

    /** 搜索框归一化：去首尾空白并按基线限制长度（服务端 `query` 上限）。 */
    fun normalizeQuery(raw: String): String = raw.trim().take(QUERY_MAX_LENGTH)

    /**
     * 导出文件名：`<标题>-<会话前 8 位>.md`。
     *
     * 去掉 Windows / Android 都不允许的字符，标题全被过滤时退回会话 id，避免出现 `.md` 这种隐藏文件。
     */
    fun exportFileName(title: String, sessionId: String): String {
        val cleaned = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
        val suffix = sessionId.filter { it.isLetterOrDigit() }.take(8)
        val base = cleaned.ifBlank { suffix.ifBlank { "session" } }
        return if (suffix.isBlank() || cleaned.isBlank()) "$base.md" else "$base-$suffix.md"
    }

    /**
     * 摘要里高亮查询词的位置（大小写不敏感，全部命中）。
     * 返回的区间是闭区间下标，供界面分段着色；查询为空时返回空列表。
     */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val needle = query.trim()
        if (needle.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - needle.length) {
            val index = text.indexOf(needle, from, ignoreCase = true)
            if (index < 0) break
            ranges += index until index + needle.length
            from = index + needle.length
        }
        return ranges
    }
}
