package com.denova.novara

import com.denova.novara.core.network.PresetKind
import com.denova.novara.core.network.PresetResource
import com.denova.novara.core.presets.PresetEditError
import com.denova.novara.core.presets.PresetEditor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 创作方案纯逻辑（P4-C / PLN-001·002）：JSON 解析、分类校验、保存体组装、排序过滤。 */
class PresetEditorTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun resource(json: String) = PresetResource(obj(json))

    @Test
    fun `detail parsing rejects broken json and non objects`() {
        assertEquals(PresetEditError.INVALID_JSON, PresetEditor.parseDetail("{oops").first)
        assertEquals(PresetEditError.NOT_OBJECT, PresetEditor.parseDetail("[]").first)

        val (error, detail) = PresetEditor.parseDetail("{\"slots\": []}")
        assertNull(error)
        assertEquals("[]", detail?.get("slots").toString())
    }

    @Test
    fun `empty detail text parses as an empty object`() {
        val (error, detail) = PresetEditor.parseDetail("   ")

        assertNull(error)
        assertTrue(detail!!.isEmpty())
    }

    @Test
    fun `name and id are validated per kind`() {
        assertEquals(
            PresetEditError.NAME_REQUIRED,
            PresetEditor.validate(PresetKind.TELLER, "abc", "  ", obj("{\"slots\":[{\"target\":\"system\"}]}")),
        )
        // 导演 ID 允许大写与下划线，其余模块只允许小写字母 / 数字 / 连字符。
        assertNull(PresetEditor.validate(PresetKind.TELLER, "My_Teller-1", "n", obj("{\"slots\":[{\"target\":\"system\"}]}")))
        assertEquals(
            PresetEditError.INVALID_ID,
            PresetEditor.validate(PresetKind.PLANNING, "Bad_ID", "n", obj("{\"sections\":[{\"id\":\"a\",\"title\":\"b\"}]}")),
        )
    }

    @Test
    fun `teller requires slots with a supported target`() {
        assertEquals(PresetEditError.SLOTS_REQUIRED, PresetEditor.validate(PresetKind.TELLER, "t", "n", obj("{}")))
        assertEquals(
            PresetEditError.SLOTS_REQUIRED,
            PresetEditor.validate(PresetKind.TELLER, "t", "n", obj("{\"slots\":[]}")),
        )
        assertEquals(
            PresetEditError.INVALID_SLOT_TARGET,
            PresetEditor.validate(PresetKind.TELLER, "t", "n", obj("{\"slots\":[{\"target\":\"user\"}]}")),
        )
        assertNull(
            PresetEditor.validate(
                PresetKind.TELLER,
                "t",
                "n",
                obj("{\"slots\":[{\"target\":\"system\"},{\"target\":\"turn_context\"}]}"),
            ),
        )
    }

    @Test
    fun `planning requires sections with id and title`() {
        assertEquals(PresetEditError.SECTIONS_REQUIRED, PresetEditor.validate(PresetKind.PLANNING, "p", "n", obj("{}")))
        assertEquals(
            PresetEditError.SECTION_FIELDS_REQUIRED,
            PresetEditor.validate(PresetKind.PLANNING, "p", "n", obj("{\"sections\":[{\"id\":\"a\"}]}")),
        )
        assertNull(
            PresetEditor.validate(
                PresetKind.PLANNING,
                "p",
                "n",
                obj("{\"sections\":[{\"id\":\"a\",\"title\":\"T\"}]}"),
            ),
        )
    }

    @Test
    fun `actor state requires at least one template`() {
        assertEquals(
            PresetEditError.TEMPLATES_REQUIRED,
            PresetEditor.validate(PresetKind.ACTOR, "s", "n", obj("{\"actor_state\":{\"templates\":[]}}")),
        )
        assertNull(
            PresetEditor.validate(
                PresetKind.ACTOR,
                "s",
                "n",
                obj("{\"actor_state\":{\"templates\":[{\"id\":\"p\"}]}}"),
            ),
        )
    }

    @Test
    fun `save body keeps unknown fields and drops read only ones`() {
        val base = resource(
            """
            {
              "version": 6,
              "id": "demo",
              "name": "旧名",
              "description": "旧说明",
              "path": "/data/.denova/x.json",
              "revision": "abc123",
              "custom": true,
              "future_field": { "keep": true },
              "sections": [ { "id": "old", "title": "旧" } ]
            }
            """.trimIndent(),
        )

        val body = PresetEditor.buildSaveBody(
            kind = PresetKind.PLANNING,
            base = base.raw,
            id = "demo",
            name = "新名",
            description = "新说明",
            detail = obj("{\"sections\":[{\"id\":\"new\",\"title\":\"新\"}]}"),
        )

        assertEquals("新名", body["name"].toString().trim('"'))
        assertEquals("新说明", body["description"].toString().trim('"'))
        assertEquals("6", body["version"].toString())
        assertTrue(body.containsKey("future_field"))
        assertNull(body["path"])
        assertNull(body["revision"])
        assertNull(body["custom"])
        assertEquals("new", ((body["sections"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)["id"].toString().trim('"'))
    }

    @Test
    fun `save body omits the id when it is blank and removes dropped detail keys`() {
        val base = resource("{\"id\":\"demo\",\"prompt\":\"p\",\"slots\":[]}")

        val body = PresetEditor.buildSaveBody(
            kind = PresetKind.IMAGE,
            base = base.raw,
            id = "",
            name = "n",
            description = "",
            detail = obj("{\"prompt\":\"q\"}"),
        )

        assertNull(body["id"])
        assertNull(body["slots"])
        assertEquals("q", body["prompt"].toString().trim('"'))
    }

    @Test
    fun `sort puts builtins first and filter matches every word`() {
        val builtin = resource("{\"id\":\"zeta\",\"name\":\"Zeta 模板\",\"description\":\"\",\"custom\":false}")
        val custom = resource("{\"id\":\"alpha\",\"name\":\"Alpha 模板\",\"description\":\"测试\",\"custom\":true}")
        val ours = resource("{\"id\":\"beta\",\"name\":\"Beta\",\"description\":\"\",\"custom\":true}")

        assertEquals(listOf("zeta", "alpha", "beta"), PresetEditor.sort(listOf(custom, ours, builtin)).map { it.id })
        assertEquals(listOf("alpha"), PresetEditor.filter(listOf(custom, ours, builtin), "alpha 测试").map { it.id })
        assertEquals(3, PresetEditor.filter(listOf(custom, ours, builtin), "").size)
    }

    @Test
    fun `detail text renders the current structure and the documented skeleton`() {
        val teller = resource(
            "{\"id\":\"t\",\"slots\":[{\"id\":\"main\",\"target\":\"system\"}],\"style_refs\":[\"a\"]}",
        )

        val text = PresetEditor.detailText(PresetKind.TELLER, teller)
        assertTrue(text.contains("\"slots\""))
        assertTrue(text.contains("\"style_refs\""))

        // 没有数据的类型给出可编辑骨架（导演必须带 slots）。
        assertTrue(PresetEditor.detailText(PresetKind.TELLER, null).contains("\"target\": \"system\""))
    }

    @Test
    fun `resource summary counts the structural children`() {
        assertEquals("1 slots", resource("{\"id\":\"t\",\"slots\":[{}]}").summary(PresetKind.TELLER))
        assertEquals("2 events", resource("{\"id\":\"e\",\"events\":[{},{}]}").summary(PresetKind.EVENT))
        assertEquals(
            "1 checks",
            resource("{\"id\":\"r\",\"trpg_system\":{\"rule_templates\":[{}]}}").summary(PresetKind.RULE),
        )
    }
}
