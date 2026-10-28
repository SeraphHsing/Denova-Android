package com.denova.novara

import com.denova.novara.core.agents.AgentDefinitions
import com.denova.novara.core.agents.AgentEditError
import com.denova.novara.core.network.CustomAgentDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agents 定义纯逻辑（P4-C / DEF-001·002）：ID 规范化、契约映射、合并删除、分组。 */
class AgentDefinitionsTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun agent(json: String) = CustomAgentDefinition(obj(json))

    @Test
    fun `id normalization follows the server rules`() {
        assertEquals("custom-agent-1", AgentDefinitions.normalizeId("Custom Agent 1"))
        assertEquals("a_b-c", AgentDefinitions.normalizeId("  A__B  _C "))
        // 非 ASCII 字符在首位会被丢弃（与后端 NormalizeSubAgentID 同口径）。
        assertEquals("", AgentDefinitions.normalizeId("人物设计"))
        assertEquals("", AgentDefinitions.normalizeId("!!!"))
        assertTrue(AgentDefinitions.newId(1700000000000, "abc").startsWith("custom-agent-1700000000000"))
    }

    @Test
    fun `contracts map both ways for the four customizable kinds`() {
        assertEquals("writing.primary.v1", AgentDefinitions.contractFor("ide"))
        assertEquals("ide", AgentDefinitions.kindFor("writing.primary.v1"))
        assertNull(AgentDefinitions.kindFor("unknown.contract.v1"))
        assertEquals(4, AgentDefinitions.customizableKinds.size)
    }

    @Test
    fun `validation requires a name and a known contract`() {
        assertEquals(AgentEditError.NAME_REQUIRED, AgentDefinitions.validate(" ", "writing.primary.v1"))
        assertEquals(AgentEditError.INVALID_CONTRACT, AgentDefinitions.validate("n", "nope"))
        assertNull(AgentDefinitions.validate("n", "game.narrator.v1"))
    }

    @Test
    fun `new definition keeps the minimum editable fields`() {
        val definition = AgentDefinitions.newDefinition(
            id = "My Agent",
            name = " 人物设计 ",
            description = " desc ",
            contract = "writing.primary.v1",
        )

        assertEquals("my-agent", definition["id"].toString().trim('"'))
        assertEquals("人物设计", definition["name"].toString().trim('"'))
        assertEquals("desc", definition["description"].toString().trim('"'))
        assertEquals("true", definition["enabled"].toString())
    }

    @Test
    fun `upsert replaces by identity and appends new definitions`() {
        val first = agent("{\"id\":\"a\",\"name\":\"A\",\"contract\":\"writing.primary.v1\"}")
        val second = agent("{\"id\":\"b\",\"name\":\"B\",\"contract\":\"project.general.v1\"}")

        val updated = AgentDefinitions.upsert(
            listOf(first, second),
            obj("{\"id\":\"a\",\"name\":\"A2\",\"contract\":\"writing.primary.v1\"}"),
        )
        assertEquals(listOf("A2", "B"), updated.map { it.name })

        val added = AgentDefinitions.upsert(
            listOf(first),
            obj("{\"id\":\"c\",\"name\":\"C\",\"contract\":\"image.creator.v1\"}"),
        )
        assertEquals(listOf("a", "c"), added.map { it.id })

        // 空 ID 不会写进数组（服务端会按 ID 合并，空 ID 无法定位）。
        assertEquals(1, AgentDefinitions.upsert(listOf(first), obj("{\"name\":\"no id\"}")).size)
    }

    @Test
    fun `remove and changes keep the whole array`() {
        val first = agent("{\"id\":\"a\",\"name\":\"A\",\"contract\":\"writing.primary.v1\"}")
        val second = agent("{\"id\":\"b\",\"name\":\"B\",\"contract\":\"project.general.v1\"}")

        assertEquals(listOf("b"), AgentDefinitions.remove(listOf(first, second), "a").map { it.id })

        val changes = AgentDefinitions.changesOf(listOf(first, second))
        assertEquals(2, changes["custom_agents"]!!.jsonArray.size)
        assertEquals("a", changes["custom_agents"]!!.jsonArray[0].jsonObject["id"].toString().trim('"'))
    }

    @Test
    fun `visible filters disabled and unknown contracts`() {
        val ok = agent("{\"id\":\"a\",\"name\":\"A\",\"contract\":\"writing.primary.v1\",\"enabled\":true}")
        val disabled = agent("{\"id\":\"b\",\"name\":\"B\",\"contract\":\"writing.primary.v1\",\"enabled\":false}")
        val unknown = agent("{\"id\":\"c\",\"name\":\"C\",\"contract\":\"legacy.v0\"}")
        val nameless = agent("{\"id\":\"d\",\"contract\":\"project.general.v1\"}")

        assertEquals(listOf("a"), AgentDefinitions.visible(listOf(ok, disabled, unknown, nameless)).map { it.id })
    }

    @Test
    fun `grouping attaches custom agents to their runtime kind`() {
        val writing = agent("{\"id\":\"w\",\"name\":\"W\",\"contract\":\"writing.primary.v1\"}")
        val game = agent("{\"id\":\"g\",\"name\":\"G\",\"contract\":\"game.narrator.v1\"}")

        val grouped = AgentDefinitions.grouped(listOf(writing, game))

        assertEquals(6, grouped.size)
        assertEquals(listOf("w"), grouped.first { it.first.key == "ide" }.second.map { it.id })
        assertEquals(listOf("g"), grouped.first { it.first.key == "interactive_story" }.second.map { it.id })
        assertEquals(emptyList<String>(), grouped.first { it.first.key == "image" }.second.map { it.id })
    }

    @Test
    fun `with edits keeps unknown configuration fields`() {
        val original = agent(
            """
            {
              "id": "a",
              "name": "A",
              "contract": "writing.primary.v1",
              "enabled": true,
              "model": { "profile_id": "gemini", "thinking_level": "high" },
              "tools": { "shell": true },
              "skill_policy": { "mode": "managed", "pinned": ["deai-promax"] },
              "delegation": { "mode": "compatible" },
              "future_field": 1
            }
            """.trimIndent(),
        )

        val edited = original.withEdits(name = "B", description = "d", enabled = false)

        assertEquals("B", edited["name"].toString().trim('"'))
        assertEquals("false", edited["enabled"].toString())
        assertTrue(edited.containsKey("model"))
        assertTrue(edited.containsKey("tools"))
        assertTrue(edited.containsKey("future_field"))
        assertEquals("managed", (edited["skill_policy"] as JsonObject)["mode"].toString().trim('"'))
        assertEquals("writing.primary.v1", edited["contract"].toString().trim('"'))
    }
}
