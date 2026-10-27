package com.denova.novara

import com.denova.novara.core.game.StoryCreation
import com.denova.novara.core.game.StoryCreationForm
import com.denova.novara.core.game.StoryCreationIssue
import com.denova.novara.core.game.StoryCreationResources
import com.denova.novara.core.network.ImagePresetsResponse
import com.denova.novara.core.network.LoreItemsResponse
import com.denova.novara.core.network.NamedResource
import com.denova.novara.core.network.TellersResponse
import com.denova.novara.core.network.characterLoreItems
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新建故事表单（P4-A / GAM-001）：资源目录解析、校验与请求体构造。 */
class StoryCreationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- 资源目录：顶层键名与真实响应一致（tellers / presets / items）----

    @Test
    fun `parses tellers presets and lore items payloads`() {
        val tellers = json.decodeFromString(
            TellersResponse.serializer(),
            """{"tellers":[{"id":"rhythm","name":"Rhythm"},{"id":"calm","name":"Calm","slots":[]}],"default_id":"rhythm"}""",
        )
        assertEquals("rhythm", tellers.defaultId)
        assertEquals(listOf("rhythm", "calm"), tellers.tellers.map { it.id })

        // 图像方案的顶层键是 presets（不是 image_presets）。
        val presets = json.decodeFromString(
            ImagePresetsResponse.serializer(),
            """{"presets":[{"id":"game-cg","name":"Game CG"}]}""",
        )
        assertEquals("game-cg", presets.presets.single().id)

        val lore = json.decodeFromString(
            LoreItemsResponse.serializer(),
            """
            {"project_id":"project-1","items":[
              {"id":"lore-1","name":"Lin","type":"character","enabled":true,"tags":["protagonist"]},
              {"id":"lore-2","name":"Off","type":"character","enabled":false},
              {"id":"lore-3","name":"Place","type":"location","enabled":true}
            ]}
            """.trimIndent(),
        )
        // 只保留启用中的角色条目（与 Web StoryProtagonistSelector 同口径）。
        assertEquals(listOf("lore-1"), lore.items.characterLoreItems().map { it.id })
    }

    // ---- 校验 ----

    @Test
    fun `validate requires custom protagonist name and lore source`() {
        assertEquals(
            StoryCreationIssue.CUSTOM_PROTAGONIST_NAME,
            StoryCreation.validate(StoryCreationForm(protagonistMode = "custom", protagonistName = "  ")),
        )
        assertEquals(
            StoryCreationIssue.MISSING_LORE_ITEM,
            StoryCreation.validate(StoryCreationForm(protagonistMode = "lore", protagonistLoreId = "")),
        )
        assertNull(
            StoryCreation.validate(
                StoryCreationForm(protagonistMode = "lore", protagonistLoreId = "lore-1"),
            ),
        )
    }

    @Test
    fun `validate requires opening text per mode`() {
        assertEquals(
            StoryCreationIssue.EMPTY_PRESET_OPENING,
            StoryCreation.validate(StoryCreationForm(openingMode = "preset", openingPresetText = " ")),
        )
        assertEquals(
            StoryCreationIssue.EMPTY_CUSTOM_OPENING,
            StoryCreation.validate(StoryCreationForm(openingMode = "custom", openingCustomText = "")),
        )
        // AI 模式不需要文案。
        assertNull(StoryCreation.validate(StoryCreationForm(openingMode = "ai")))
    }

    @Test
    fun `validate bounds generation and check settings`() {
        assertEquals(
            StoryCreationIssue.CHOICE_COUNT_RANGE,
            StoryCreation.validate(StoryCreationForm(choiceCount = 1)),
        )
        assertEquals(
            StoryCreationIssue.REPLY_CHARS_RANGE,
            StoryCreation.validate(StoryCreationForm(replyTargetChars = 0)),
        )
        assertEquals(
            StoryCreationIssue.IMAGE_INTERVAL_RANGE,
            StoryCreation.validate(StoryCreationForm(imageMode = "interval", imageIntervalTurns = 0)),
        )
        assertEquals(
            StoryCreationIssue.DIFFICULTY_RANGE,
            StoryCreation.validate(StoryCreationForm(difficultyShift = 3)),
        )
        assertEquals(
            StoryCreationIssue.ROLL_MODIFIER_RANGE,
            StoryCreation.validate(StoryCreationForm(rollModifier = -21)),
        )
        // manual 模式下间隔不参与校验。
        assertNull(
            StoryCreation.validate(StoryCreationForm(imageMode = "manual", imageIntervalTurns = 0)),
        )
    }

    // ---- 请求体构造 ----

    @Test
    fun `build maps protagonist modes`() {
        val default = StoryCreation.build(
            StoryCreationForm(protagonistMode = "default", protagonistName = "ignored"),
        )
        assertEquals("default", default.protagonist.mode)
        // default 模式不携带名称（服务端按资料库自动识别）。
        assertEquals("", default.protagonist.name)

        val custom = StoryCreation.build(
            StoryCreationForm(
                protagonistMode = "custom",
                protagonistName = " 林尘 ",
                protagonistProfile = " 剑修 ",
            ),
        )
        assertEquals("custom", custom.protagonist.mode)
        assertEquals("林尘", custom.protagonist.name)
        assertEquals("剑修", custom.protagonist.profile)

        val lore = StoryCreation.build(
            StoryCreationForm(protagonistMode = "lore", protagonistLoreId = "lore-1"),
        )
        assertEquals("lore", lore.protagonist.mode)
        assertEquals("lore-1", lore.protagonist.sourceLoreItemId)
        assertEquals("", lore.protagonist.name)
    }

    @Test
    fun `build maps opening modes and trims text`() {
        val ai = StoryCreation.build(StoryCreationForm(openingMode = "ai", openingCustomText = "ignored"))
        assertEquals("ai", ai.opening.mode)
        assertEquals("", ai.opening.customText)

        val preset = StoryCreation.build(
            StoryCreationForm(openingMode = "preset", openingPresetId = "p1", openingPresetText = " 雨夜 "),
        )
        assertEquals("preset", preset.opening.mode)
        assertEquals("p1", preset.opening.presetId)
        assertEquals("雨夜", preset.opening.presetText)

        val custom = StoryCreation.build(
            StoryCreationForm(openingMode = "custom", openingCustomText = " 推门而入 "),
        )
        assertEquals("custom", custom.opening.mode)
        assertEquals("推门而入", custom.opening.customText)
    }

    @Test
    fun `build turns switches into disabled flags and clears disabled packages`() {
        val request = StoryCreation.build(
            StoryCreationForm(
                narrativeStyleId = "rhythm",
                narrativeStyleEnabled = false,
                eventPackageIds = listOf("default", "extra"),
                eventPackagesEnabled = false,
                ruleSystemId = "default",
                ruleSystemEnabled = true,
                actorStateId = "default",
                actorStateEnabled = false,
                imagePresetId = "game-cg",
                choiceCount = 3,
                replyTargetChars = 1500,
                imageMode = "interval",
                imageIntervalTurns = 5,
                difficultyShift = 1,
                rollModifier = -3,
            ),
        )
        val refs = requireNotNull(request.moduleRefs)
        assertTrue(refs.narrativeStyleDisabled)
        assertTrue(refs.eventPackagesDisabled)
        // 显式关闭事件包时不再携带列表，避免服务端仍加载。
        assertTrue(refs.eventPackageIds.isEmpty())
        assertFalse(refs.ruleSystemDisabled)
        assertTrue(refs.actorStateDisabled)
        assertEquals("game-cg", refs.imagePresetId)
        assertEquals(3, request.choiceCount)
        assertEquals(1500, request.replyTargetChars)
        assertEquals("interval", request.imageSettings?.mode)
        assertEquals(5, request.imageSettings?.intervalTurns)
        assertEquals(1, request.checkSettings?.difficultyShift)
        assertEquals(-3, request.checkSettings?.rollModifier)
        assertEquals("adapt_template", request.stateSchemaPolicy?.mode)
        assertTrue(request.presentationSettings?.background == true)
    }

    @Test
    fun `applyDefaults fills empty selections without overriding user choice`() {
        val resources = StoryCreationResources(
            tellers = listOf(NamedResource("rhythm", "Rhythm"), NamedResource("calm", "Calm")),
            defaultTellerId = "rhythm",
            planningTemplates = listOf(NamedResource("default", "Default")),
            eventPackages = listOf(NamedResource("default", "Default")),
            ruleSystems = listOf(NamedResource("default", "Default")),
            actorStates = listOf(NamedResource("default", "Default")),
            imagePresets = listOf(NamedResource("game-cg", "Game CG"), NamedResource("alt", "Alt")),
        )

        val filled = StoryCreation.applyDefaults(StoryCreationForm(), resources)
        assertEquals("rhythm", filled.narrativeStyleId)
        assertEquals("default", filled.planningTemplateId)
        assertEquals(listOf("default"), filled.eventPackageIds)
        assertEquals("default", filled.ruleSystemId)
        assertEquals("default", filled.actorStateId)
        assertEquals("game-cg", filled.imagePresetId)

        // 用户已选的模块不被覆盖，事件包保留原列表。
        val chosen = StoryCreation.applyDefaults(
            StoryCreationForm(
                narrativeStyleId = "calm",
                imagePresetId = "alt",
                eventPackageIds = listOf("default", "extra"),
            ),
            resources,
        )
        assertEquals("calm", chosen.narrativeStyleId)
        assertEquals("alt", chosen.imagePresetId)
        assertEquals(listOf("default", "extra"), chosen.eventPackageIds)
    }

    @Test
    fun `resources loaded reflects whether any directory arrived`() {
        assertFalse(StoryCreationResources().loaded)
        assertTrue(StoryCreationResources(tellers = listOf(NamedResource("rhythm"))).loaded)
    }
}
