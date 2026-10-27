package com.denova.novara.core.game

import com.denova.novara.core.network.CreateStoryRequest
import com.denova.novara.core.network.LoreItemSummary
import com.denova.novara.core.network.NamedResource
import com.denova.novara.core.network.StoryCheckSettings
import com.denova.novara.core.network.StoryImageSettings
import com.denova.novara.core.network.StoryModuleRefs
import com.denova.novara.core.network.StoryOpeningConfig
import com.denova.novara.core.network.StoryPresentationSettings
import com.denova.novara.core.network.StoryProtagonist
import com.denova.novara.core.network.StoryStateSchemaPolicy

/** 新建故事表单的资源目录（六个导演模块 + 资料库主角候选）。 */
data class StoryCreationResources(
    val tellers: List<NamedResource> = emptyList(),
    val defaultTellerId: String = "",
    val planningTemplates: List<NamedResource> = emptyList(),
    val eventPackages: List<NamedResource> = emptyList(),
    val ruleSystems: List<NamedResource> = emptyList(),
    val actorStates: List<NamedResource> = emptyList(),
    val imagePresets: List<NamedResource> = emptyList(),
    val loreCharacters: List<LoreItemSummary> = emptyList(),
) {
    val loaded: Boolean
        get() = tellers.isNotEmpty() || planningTemplates.isNotEmpty() || imagePresets.isNotEmpty()
}

/** 表单校验问题；由 ViewModel 映射成中英文案。 */
enum class StoryCreationIssue {
    CUSTOM_PROTAGONIST_NAME,
    MISSING_LORE_ITEM,
    EMPTY_PRESET_OPENING,
    EMPTY_CUSTOM_OPENING,
    CHOICE_COUNT_RANGE,
    REPLY_CHARS_RANGE,
    IMAGE_INTERVAL_RANGE,
    DIFFICULTY_RANGE,
    ROLL_MODIFIER_RANGE,
}

/**
 * 新建故事表单状态（P4-A / GAM-001）。字段与 `CreateStoryRequest` 一一对应，
 * 默认值取 Web `NewStorySetupPanel` / `opening.ts` / `check-settings.ts` 的同口径值。
 */
data class StoryCreationForm(
    val title: String = "",
    val origin: String = "",
    // 主角：default（按资料库自动识别）/ custom / lore
    val protagonistMode: String = "default",
    val protagonistName: String = "",
    val protagonistProfile: String = "",
    val protagonistLoreId: String = "",
    // 开场：ai / preset / custom
    val openingMode: String = "ai",
    val openingPresetId: String = "",
    val openingPresetText: String = "",
    val openingCustomText: String = "",
    // 导演模块
    val planningTemplateId: String = "",
    val narrativeStyleId: String = "",
    val narrativeStyleEnabled: Boolean = true,
    val eventPackageIds: List<String> = emptyList(),
    val eventPackagesEnabled: Boolean = true,
    val ruleSystemId: String = "",
    val ruleSystemEnabled: Boolean = true,
    val actorStateId: String = "",
    val actorStateEnabled: Boolean = true,
    val imagePresetId: String = "",
    // 生成参数
    val imageMode: String = "manual",
    val imageIntervalTurns: Int = 3,
    val replyTargetChars: Int = 2000,
    val choiceCount: Int = 5,
    val planningMode: String = "enabled",
    val stateSchemaMode: String = "adapt_template",
    // 检定
    val difficultyShift: Int = 0,
    val rollModifier: Int = 0,
    val ruleStateConsumptionMode: String = "hybrid_auto",
    // 呈现
    val presentationBackground: Boolean = true,
    val presentationCharacters: Boolean = true,
)

/**
 * 创建请求的纯逻辑构造（不含网络）。
 *
 * 与 Web 的差异：Web 基础组不暴露标题/前提（依赖后端占位标题），Android 允许填写；
 * 留空时仍由服务端生成占位标题（`title_source=pending`）。
 */
object StoryCreation {

    fun validate(form: StoryCreationForm): StoryCreationIssue? = when {
        form.protagonistMode == "custom" && form.protagonistName.isBlank() -> StoryCreationIssue.CUSTOM_PROTAGONIST_NAME
        form.protagonistMode == "lore" && form.protagonistLoreId.isBlank() -> StoryCreationIssue.MISSING_LORE_ITEM
        form.openingMode == "preset" && form.openingPresetText.isBlank() -> StoryCreationIssue.EMPTY_PRESET_OPENING
        form.openingMode == "custom" && form.openingCustomText.isBlank() -> StoryCreationIssue.EMPTY_CUSTOM_OPENING
        form.choiceCount !in 2..10 -> StoryCreationIssue.CHOICE_COUNT_RANGE
        form.replyTargetChars <= 0 -> StoryCreationIssue.REPLY_CHARS_RANGE
        form.imageMode == "interval" && form.imageIntervalTurns !in 1..50 -> StoryCreationIssue.IMAGE_INTERVAL_RANGE
        form.difficultyShift !in -2..2 -> StoryCreationIssue.DIFFICULTY_RANGE
        form.rollModifier !in -20..20 -> StoryCreationIssue.ROLL_MODIFIER_RANGE
        else -> null
    }

    fun build(form: StoryCreationForm): CreateStoryRequest = CreateStoryRequest(
        title = form.title.trim(),
        origin = form.origin.trim(),
        protagonist = protagonist(form),
        storyTellerId = form.narrativeStyleId,
        planningTemplateId = form.planningTemplateId,
        planningMode = form.planningMode,
        moduleRefs = StoryModuleRefs(
            narrativeStyleId = form.narrativeStyleId,
            narrativeStyleDisabled = !form.narrativeStyleEnabled,
            eventPackageIds = if (form.eventPackagesEnabled) form.eventPackageIds else emptyList(),
            eventPackagesDisabled = !form.eventPackagesEnabled,
            ruleSystemId = form.ruleSystemId,
            ruleSystemDisabled = !form.ruleSystemEnabled,
            actorStateId = form.actorStateId,
            actorStateDisabled = !form.actorStateEnabled,
            imagePresetId = form.imagePresetId,
        ),
        replyTargetChars = form.replyTargetChars,
        choiceCount = form.choiceCount,
        opening = opening(form),
        imageSettings = StoryImageSettings(
            mode = form.imageMode,
            intervalTurns = form.imageIntervalTurns.coerceIn(1, 50),
            presetId = form.imagePresetId,
        ),
        checkSettings = StoryCheckSettings(
            difficultyShift = form.difficultyShift,
            rollModifier = form.rollModifier,
            ruleStateConsumptionMode = form.ruleStateConsumptionMode,
        ),
        presentationSettings = StoryPresentationSettings(
            background = form.presentationBackground,
            characters = form.presentationCharacters,
        ),
        stateSchemaPolicy = StoryStateSchemaPolicy(form.stateSchemaMode),
    )

    /** `default` 只需 mode；`custom` 带名称与设定；`lore` 只传来源 id（服务端解析名称/设定）。 */
    private fun protagonist(form: StoryCreationForm): StoryProtagonist = when (form.protagonistMode) {
        "custom" -> StoryProtagonist(
            mode = "custom",
            name = form.protagonistName.trim(),
            profile = form.protagonistProfile.trim(),
        )
        "lore" -> StoryProtagonist(mode = "lore", sourceLoreItemId = form.protagonistLoreId)
        else -> StoryProtagonist(mode = "default")
    }

    /** 开场只传当前模式需要的字段：`ai` 无文案，`preset` 需文案，`custom` 为权威续写。 */
    private fun opening(form: StoryCreationForm): StoryOpeningConfig = when (form.openingMode) {
        "preset" -> StoryOpeningConfig(
            mode = "preset",
            presetId = form.openingPresetId,
            presetText = form.openingPresetText.trim(),
        )
        "custom" -> StoryOpeningConfig(mode = "custom", customText = form.openingCustomText.trim())
        else -> StoryOpeningConfig(mode = "ai")
    }

    /**
     * 资源目录到达后补齐默认选择：只填空字段，不覆盖用户已选。
     * 默认值与 Web 一致（叙事风格取服务端 `default_id`、图像方案 `game-cg`、其余模块 `default`）。
     */
    fun applyDefaults(
        form: StoryCreationForm,
        resources: StoryCreationResources,
    ): StoryCreationForm {
        val tellerDefault = resources.defaultTellerId.ifBlank { "rhythm" }
        val presetDefault = resources.imagePresets.firstOrNull { it.id == "game-cg" }?.id
            ?: resources.imagePresets.firstOrNull()?.id
            ?: "game-cg"
        return form.copy(
            narrativeStyleId = form.narrativeStyleId.ifBlank {
                resources.tellers.firstOrNull { it.id == tellerDefault }?.id
                    ?: resources.tellers.firstOrNull()?.id.orEmpty()
            },
            planningTemplateId = form.planningTemplateId.ifBlank {
                resources.planningTemplates.firstOrNull { it.id == "default" }?.id
                    ?: resources.planningTemplates.firstOrNull()?.id.orEmpty()
            },
            eventPackageIds = if (form.eventPackageIds.isEmpty()) {
                resources.eventPackages.firstOrNull { it.id == "default" }?.let { listOf(it.id) }
                    ?: resources.eventPackages.take(1).map { it.id }
            } else {
                form.eventPackageIds
            },
            ruleSystemId = form.ruleSystemId.ifBlank {
                resources.ruleSystems.firstOrNull { it.id == "default" }?.id
                    ?: resources.ruleSystems.firstOrNull()?.id.orEmpty()
            },
            actorStateId = form.actorStateId.ifBlank {
                resources.actorStates.firstOrNull { it.id == "default" }?.id
                    ?: resources.actorStates.firstOrNull()?.id.orEmpty()
            },
            imagePresetId = form.imagePresetId.ifBlank { presetDefault },
        )
    }
}
