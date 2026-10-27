package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 故事库契约（P4-A / GAM-001、GAM-002），对齐固定 Web 基线与后端
 * `internal/interactive/story_types.go`：
 *
 * - 列表 `GET /api/interactive/stories` → `Index{current_story_id, stories[]}`
 * - 选中 `POST /api/interactive/stories/:id/select`（写服务端 index，全 workspace 共享）
 * - 改名 `PATCH /api/interactive/stories/:id`（**无 base_revision 语义**，只追加 config 事件）
 * - 删除 `DELETE /api/interactive/stories/:id`
 *
 * 字段只取客户端要用的部分，其余由 `ignoreUnknownKeys` 忽略。
 */
@Serializable
data class StoryProtagonist(
    /** `default`（按资料库自动识别）/ `custom` / `lore`。 */
    val mode: String = "default",
    val name: String = "",
    val profile: String = "",
    @SerialName("source_lore_item_id") val sourceLoreItemId: String = "",
    @SerialName("source_lore_updated_at") val sourceLoreUpdatedAt: String = "",
)

/** 开场配置：`ai`（按 origin 生成）/ `preset`（预设文案）/ `custom`（自定义文案，权威续写）。 */
@Serializable
data class StoryOpeningConfig(
    val mode: String = "ai",
    @SerialName("preset_id") val presetId: String = "",
    @SerialName("preset_text") val presetText: String = "",
    @SerialName("custom_text") val customText: String = "",
)

/**
 * 故事摘要。`preview=true` 是开发测试故事：后端禁止选中，也不进正常选择器，
 * 客户端在列表里过滤掉（见 [com.denova.novara.core.game.StoryLibrary.visible]）。
 */
@Serializable
data class StorySummary(
    val id: String,
    val title: String = "",
    /** `user` 表示用户填写；`pending` 表示后端生成的占位标题（如「新的开始」）。 */
    @SerialName("title_source") val titleSource: String = "user",
    val origin: String = "",
    val protagonist: StoryProtagonist = StoryProtagonist(),
    @SerialName("story_teller_id") val storyTellerId: String = "",
    @SerialName("planning_template_id") val planningTemplateId: String = "",
    @SerialName("planning_mode") val planningMode: String = "enabled",
    @SerialName("reply_target_chars") val replyTargetChars: Int = 2000,
    @SerialName("choice_count") val choiceCount: Int = 5,
    val opening: StoryOpeningConfig = StoryOpeningConfig(),
    val preview: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val branches: Int = 0,
    val events: Int = 0,
    @SerialName("turn_count") val turnCount: Int = 0,
)

/** `GET /api/interactive/stories` 响应。`current_story_id` 可为空串（无选择）。 */
@Serializable
data class StoryIndex(
    @SerialName("current_story_id") val currentStoryId: String = "",
    val stories: List<StorySummary> = emptyList(),
)

/** `PATCH /api/interactive/stories/:id` 的部分更新；本切片只用于改名。 */
@Serializable
data class UpdateStoryTitleRequest(val title: String)
