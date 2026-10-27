package com.denova.novara

import com.denova.novara.core.game.GameChoices
import com.denova.novara.core.game.StoryStateLedgerBuilder
import com.denova.novara.core.network.InteractiveSnapshot
import com.denova.novara.core.stream.TurnEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照解析、行动选项与状态台账（P4-A / GAM-003、GAM-005、GAM-006）。 */
class GameSnapshotTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val snapshotJson = """
        {
          "story_id": "st_1",
          "branch_id": "main",
          "context_revision": 9,
          "turn_count": 12,
          "turn_start": 10,
          "has_earlier_turns": true,
          "history_before_cursor": "c10",
          "state": {
            "actors": {
              "npc1": {"name": "苏月", "role": "npc", "state": {"hp": 100}},
              "protagonist": {
                "name": "林尘",
                "role": "protagonist",
                "template_id": "default",
                "state": {"hp": 80, "title": "剑客", "bag": ["剑", "丹"]}
              }
            },
            "locations": {"客栈": {"desc": "热闹"}, "山门": "closed"},
            "clock": {"day": 3, "phase": "夜"}
          },
          "actor_state_schema": {
            "version": 1,
            "revision": 2,
            "system": {
              "templates": [
                {"id": "default", "name": "默认", "fields": [
                  {"id": "hp", "name": "气血", "type": "number"},
                  {"id": "title", "name": "称号", "type": "string"},
                  {"id": "bag", "name": "行囊", "type": "list"}
                ]}
              ]
            }
          },
          "current_turn": {
            "id": "t12",
            "user": "推门",
            "narrative": "门开了。",
            "hot_state": {"choices": ["回退"]},
            "turn_result": {"choices": ["观察四周", "拔剑"]},
            "state_delta": {
              "actor_ops": [{"op": "set", "actor_id": "protagonist", "field_id": "hp", "value": 60}],
              "ops": [{"op": "set", "path": "clock.day", "value": 4}]
            },
            "state_status": "ready"
          },
          "graph": {"nodes": [], "branches": [{"id": "main", "current": true}]},
          "unknown_future_field": {"a": 1}
        }
    """.trimIndent()

    @Test
    fun `parses snapshot window and current turn`() {
        val snapshot = json.decodeFromString(InteractiveSnapshot.serializer(), snapshotJson)

        assertEquals("st_1", snapshot.storyId)
        assertEquals("main", snapshot.branchId)
        assertEquals(12, snapshot.turnCount)
        assertEquals(10, snapshot.turnStart)
        assertTrue(snapshot.hasEarlierTurns)
        assertEquals("c10", snapshot.historyBeforeCursor)
        assertEquals(1, snapshot.graph.branches.size)
        assertEquals("ready", snapshot.currentTurn?.stateStatus)
        assertEquals(2, snapshot.currentTurn?.turnResult?.choices?.size)
    }

    @Test
    fun `choices prefer turn result and fall back to hot state`() {
        val snapshot = json.decodeFromString(InteractiveSnapshot.serializer(), snapshotJson)
        assertEquals(listOf("观察四周", "拔剑"), GameChoices.of(snapshot.currentTurn))

        val hotOnly = TurnEvent(
            hotState = com.denova.novara.core.stream.HotState(choices = listOf(" 前进 ", "", "前进")),
        )
        // 去空白、去重、丢空项；turn_result 为空时回退 hot_state。
        assertEquals(listOf("前进"), GameChoices.of(hotOnly))
        assertTrue(GameChoices.of(null).isEmpty())
    }

    @Test
    fun `ledger builds actors with template order and lead first`() {
        val ledger = StoryStateLedgerBuilder.build(
            json.decodeFromString(InteractiveSnapshot.serializer(), snapshotJson),
        )

        assertEquals(2, ledger.actors.size)
        val lead = ledger.actors.first()
        assertEquals("protagonist", lead.id)
        assertEquals("林尘", lead.name)
        assertTrue(lead.isLead)
        // 模板字段顺序 + 显示名（hp→气血），数组值拼平；模板未声明的字段不丢失。
        assertEquals(
            listOf("气血" to "80", "称号" to "剑客", "行囊" to "剑, 丹"),
            lead.fields.map { it.name to it.value },
        )
        assertEquals("苏月", ledger.actors[1].name)
        assertFalse(ledger.actors[1].isLead)
    }

    @Test
    fun `ledger expands world top level keys`() {
        val ledger = StoryStateLedgerBuilder.build(
            json.decodeFromString(InteractiveSnapshot.serializer(), snapshotJson),
        )

        val world = ledger.world.associate { it.name to it.value }
        assertEquals("desc=热闹", world["locations.客栈"])
        assertEquals("closed", world["locations.山门"])
        assertEquals("3", world["clock.day"])
        assertEquals("夜", world["clock.phase"])
        assertFalse(ledger.isEmpty)
    }

    @Test
    fun `ledger renders this turn changes with display names`() {
        val ledger = StoryStateLedgerBuilder.build(
            json.decodeFromString(InteractiveSnapshot.serializer(), snapshotJson),
        )

        // 角色字段用显示名（hp→气血）；普通路径原样。
        assertEquals(listOf("林尘.气血: 60", "clock.day: 4"), ledger.changes)
    }

    @Test
    fun `ledger skips empty world placeholders`() {
        val text = """
            {
              "state": {
                "actors": {},
                "events": [],
                "rules": {},
                "threads": "  ",
                "locations": {"客栈": "热闹", "空房": ""}
              }
            }
        """.trimIndent()
        val ledger = StoryStateLedgerBuilder.build(json.decodeFromString(InteractiveSnapshot.serializer(), text))

        // 空骨架键（空数组/空对象/空串）与空子项都不展示，避免一排「—」误导。
        assertEquals(mapOf("locations.客栈" to "热闹"), ledger.world.associate { it.name to it.value })
    }

    @Test
    fun `ledger handles empty state and long values`() {
        val empty = StoryStateLedgerBuilder.build(InteractiveSnapshot())
        assertTrue(empty.isEmpty)
        assertTrue(empty.changes.isEmpty())

        val long = "x".repeat(300)
        val formatted = StoryStateLedgerBuilder.formatValue(JsonPrimitive(long))
        assertEquals(241, formatted.length)
        assertTrue(formatted.endsWith("…"))
    }
}
