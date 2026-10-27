package com.denova.novara.core.game

import com.denova.novara.core.network.StorySummary
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 故事库的纯逻辑（P4-A / GAM-002）：列表过滤、当前故事解析、更新合并、删除后的当前故事回退。
 *
 * 与 Web `InteractiveLayout.reloadStories` + `mergePreferredStory` 同口径：服务端列表顺序保留，
 * 刚创建 / 刚更新的故事置于首位；`preview` 故事不进入选择器。
 */
object StoryLibrary {

    private val displayFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 过滤掉开发测试故事（`preview=true`）：后端同样禁止选中它们。 */
    fun visible(stories: List<StorySummary>): List<StorySummary> = stories.filterNot { it.preview }

    /**
     * 解析当前故事：服务端 `current_story_id` 命中则用；否则回退到列表首个（可为 null）。
     * 不回写服务端——选择动作必须走 `POST .../select`。
     */
    fun current(stories: List<StorySummary>, currentStoryId: String): StorySummary? =
        stories.firstOrNull { it.id == currentStoryId } ?: stories.firstOrNull()

    /**
     * 把更新后的摘要合并进列表：命中 id 则原位替换（[moveToFront] 为 true 时移到首位），
     * 未命中则前插。与 Web `mergePreferredStory` 一致，避免新建后列表末尾不可见。
     */
    fun merged(
        stories: List<StorySummary>,
        updated: StorySummary,
        moveToFront: Boolean = true,
    ): List<StorySummary> = when {
        stories.none { it.id == updated.id } -> listOf(updated) + stories
        moveToFront -> listOf(updated) + stories.filterNot { it.id == updated.id }
        else -> stories.map { if (it.id == updated.id) updated else it }
    }

    /** 删除后回退当前故事：删掉的正是当前项时取剩余首个，否则保持原选择。 */
    fun afterDelete(
        stories: List<StorySummary>,
        deletedId: String,
        currentStoryId: String,
    ): Pair<List<StorySummary>, String> {
        val remaining = stories.filterNot { it.id == deletedId }
        val next = if (currentStoryId == deletedId) remaining.firstOrNull()?.id.orEmpty() else currentStoryId
        return remaining to next
    }

    /**
     * 展示用的更新时间：RFC3339（含纳秒与时区）→ `MM-dd HH:mm`。
     * 解析失败返回空串，由界面决定隐藏该行而不是显示原始时间戳。
     */
    fun formatUpdatedAt(
        iso: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (iso.isBlank()) return ""
        val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
            .recoverCatching { Instant.parse(iso) }
            .getOrNull() ?: return ""
        return displayFormatter.format(instant.atZone(zone))
    }
}
