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

/** 导演模块选择（创建请求的 `module_refs`）；`*_disabled=true` 表示显式关闭该模块。 */
@Serializable
data class StoryModuleRefs(
    @SerialName("narrative_style_id") val narrativeStyleId: String = "",
    @SerialName("narrative_style_disabled") val narrativeStyleDisabled: Boolean = false,
    @SerialName("event_package_ids") val eventPackageIds: List<String> = emptyList(),
    @SerialName("event_packages_disabled") val eventPackagesDisabled: Boolean = false,
    @SerialName("rule_system_id") val ruleSystemId: String = "",
    @SerialName("rule_system_disabled") val ruleSystemDisabled: Boolean = false,
    @SerialName("actor_state_id") val actorStateId: String = "",
    @SerialName("actor_state_disabled") val actorStateDisabled: Boolean = false,
    @SerialName("image_preset_id") val imagePresetId: String = "",
    @SerialName("image_preset_disabled") val imagePresetDisabled: Boolean = false,
)

/** 图像设置：`mode` ∈ `manual` / `interval`；`interval_turns` 1–50。 */
@Serializable
data class StoryImageSettings(
    val mode: String = "manual",
    @SerialName("interval_turns") val intervalTurns: Int = 3,
    @SerialName("preset_id") val presetId: String = "",
)

/** 检定设置：难度 −2..2、掷骰修正 −20..20。 */
@Serializable
data class StoryCheckSettings(
    @SerialName("difficulty_shift") val difficultyShift: Int = 0,
    @SerialName("roll_modifier") val rollModifier: Int = 0,
    @SerialName("rule_state_consumption_mode") val ruleStateConsumptionMode: String = "hybrid_auto",
    @SerialName("rule_visibility_mode") val ruleVisibilityMode: String = "audit_only",
)

/** 呈现开关（背景 / 立绘 / 环境音）；素材固定值由服务端按书籍默认注入。 */
@Serializable
data class StoryPresentationSettings(
    val background: Boolean = true,
    val characters: Boolean = true,
    val ambient: Boolean = false,
)

/** 状态结构策略：`adapt_template` / `fixed_template` / `generate`。 */
@Serializable
data class StoryStateSchemaPolicy(val mode: String = "adapt_template")

/**
 * 创建故事：`POST /api/interactive/stories` → `StorySummary`。
 *
 * 后端 `CreateStoryRequest` 除 `title` / `origin` 外均为可选，缺省走服务端默认；
 * 客户端仍按 Web 表单显式上送已选值（见 `DenovaClient.createInteractiveStory`）。
 * `custom_agent_id` / `profile_id` / `thinking_level` / `speech_settings` /
 * `initial_trait_rolls` 本切片不作为表单项，缺省即使用服务端 seed。
 */
@Serializable
data class CreateStoryRequest(
    val title: String = "",
    val origin: String = "",
    val protagonist: StoryProtagonist = StoryProtagonist(),
    @SerialName("story_teller_id") val storyTellerId: String = "",
    @SerialName("planning_template_id") val planningTemplateId: String = "",
    @SerialName("planning_mode") val planningMode: String = "",
    @SerialName("module_refs") val moduleRefs: StoryModuleRefs? = null,
    @SerialName("reply_target_chars") val replyTargetChars: Int = 2000,
    @SerialName("choice_count") val choiceCount: Int = 5,
    val opening: StoryOpeningConfig = StoryOpeningConfig(),
    @SerialName("image_settings") val imageSettings: StoryImageSettings? = null,
    @SerialName("check_settings") val checkSettings: StoryCheckSettings? = null,
    @SerialName("presentation_settings") val presentationSettings: StoryPresentationSettings? = null,
    @SerialName("state_schema_policy") val stateSchemaPolicy: StoryStateSchemaPolicy? = null,
)
