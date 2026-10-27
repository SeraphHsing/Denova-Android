package com.denova.novara

import com.denova.novara.core.game.StoryLibrary
import com.denova.novara.core.network.StoryIndex
import com.denova.novara.core.network.StorySummary
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 故事库纯逻辑（P4-A / GAM-002）：列表过滤、当前故事、合并与删除回退、时间格式化。 */
class StoryLibraryTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 真实服务端响应片段：含客户端未声明的字段（graph/module_refs 等由 ignoreUnknownKeys 忽略）。 */
    private val indexJson = """
        {
          "version": 3,
          "current_story_id": "st_second",
          "stories": [
            {
              "id": "st_first",
              "title": "灵渊劫",
              "title_source": "user",
              "origin": "修仙",
              "protagonist": {"mode": "lore", "name": "林尘", "source_lore_item_id": "lore-1"},
              "story_teller_id": "rhythm",
              "planning_template_id": "default",
              "planning_mode": "enabled",
              "reply_target_chars": 2000,
              "choice_count": 5,
              "opening": {"mode": "custom", "custom_text": "雨夜"},
              "created_at": "2026-10-20T10:00:00.123456789Z",
              "updated_at": "2026-10-26T09:30:00Z",
              "branches": 2,
              "events": 40,
              "turn_count": 12,
              "unknown_future_field": {"a": 1}
            },
            {
              "id": "st_second",
              "title": "新的开始",
              "title_source": "pending",
              "protagonist": {},
              "opening": {},
              "updated_at": "2026-10-26T11:00:00Z",
              "turn_count": 0
            },
            {
              "id": "st_preview",
              "title": "preview",
              "preview": true,
              "protagonist": {},
              "opening": {},
              "updated_at": "2026-10-26T11:00:00Z",
              "turn_count": 3
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses story index with defaults for omitted fields`() {
        val index = json.decodeFromString(StoryIndex.serializer(), indexJson)

        assertEquals("st_second", index.currentStoryId)
        assertEquals(3, index.stories.size)

        val first = index.stories.first()
        assertEquals("st_first", first.id)
        assertEquals("lore", first.protagonist.mode)
        assertEquals("林尘", first.protagonist.name)
        assertEquals("custom", first.opening.mode)
        assertEquals("雨夜", first.opening.customText)
        assertEquals(12, first.turnCount)
        assertEquals(2, first.branches)

        // 省略字段落默认值（choice_count / reply_target_chars / preview 等）。
        val second = index.stories[1]
        assertEquals("pending", second.titleSource)
        assertEquals(2000, second.replyTargetChars)
        assertEquals(5, second.choiceCount)
        assertEquals(false, second.preview)
        assertEquals("default", second.protagonist.mode)
    }

    @Test
    fun `visible filters preview stories`() {
        val stories = json.decodeFromString(StoryIndex.serializer(), indexJson).stories

        val visible = StoryLibrary.visible(stories)

        assertEquals(listOf("st_first", "st_second"), visible.map { it.id })
        assertTrue(visible.none { it.preview })
    }

    @Test
    fun `current prefers server selection and falls back to first`() {
        val stories = StoryLibrary.visible(json.decodeFromString(StoryIndex.serializer(), indexJson).stories)

        assertEquals("st_second", StoryLibrary.current(stories, "st_second")?.id)
        // 服务端选择缺失（如被删除）时回退首个，而不是返回 null 让舞台空转。
        assertEquals("st_first", StoryLibrary.current(stories, "st_missing")?.id)
        assertEquals("st_first", StoryLibrary.current(stories, "")?.id)
        assertNull(StoryLibrary.current(emptyList(), "st_x"))
    }

    @Test
    fun `merged inserts new story at front and replaces existing in place`() {
        val stories = listOf(story("a"), story("b"), story("c"))

        val created = StoryLibrary.merged(stories, story("new"))
        assertEquals(listOf("new", "a", "b", "c"), created.map { it.id })

        // 更新（如改名）：默认置顶，列表不出现重复项。
        val renamed = story("b", title = "B2")
        val updated = StoryLibrary.merged(created, renamed)
        assertEquals(listOf("b", "new", "a", "c"), updated.map { it.id })
        assertEquals("B2", updated.first().title)

        // 保持原位模式（不置顶）用于后台刷新场景。
        val inPlace = StoryLibrary.merged(stories, renamed, moveToFront = false)
        assertEquals(listOf("a", "b", "c"), inPlace.map { it.id })
        assertEquals("B2", inPlace[1].title)
    }

    @Test
    fun `afterDelete falls back to first remaining when current removed`() {
        val stories = listOf(story("a"), story("b"), story("c"))

        val (remaining, next) = StoryLibrary.afterDelete(stories, "a", "a")
        assertEquals(listOf("b", "c"), remaining.map { it.id })
        assertEquals("b", next)

        // 删的不是当前项：选择保持不变。
        val (kept, stillCurrent) = StoryLibrary.afterDelete(stories, "b", "c")
        assertEquals(listOf("a", "c"), kept.map { it.id })
        assertEquals("c", stillCurrent)

        // 删空：当前故事置空。
        val (empty, none) = StoryLibrary.afterDelete(listOf(story("a")), "a", "a")
        assertTrue(empty.isEmpty())
        assertEquals("", none)
    }

    @Test
    fun `formatUpdatedAt renders local minute and hides invalid input`() {
        val zone = ZoneId.of("Asia/Shanghai")

        assertEquals("10-26 17:30", StoryLibrary.formatUpdatedAt("2026-10-26T09:30:00Z", zone))
        // 后端 RFC3339Nano：带纳秒与时区偏移。
        assertEquals("10-26 17:30", StoryLibrary.formatUpdatedAt("2026-10-26T09:30:00.123456789Z", zone))
        assertEquals("10-26 17:30", StoryLibrary.formatUpdatedAt("2026-10-26T17:30:00+08:00", zone))
        assertEquals("", StoryLibrary.formatUpdatedAt("", zone))
        assertEquals("", StoryLibrary.formatUpdatedAt("not-a-time", zone))
    }

    private fun story(id: String, title: String = id) = StorySummary(id = id, title = title)
}
