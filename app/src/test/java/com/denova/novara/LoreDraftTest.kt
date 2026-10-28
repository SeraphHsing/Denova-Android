package com.denova.novara

import com.denova.novara.core.lore.LoreDraft
import com.denova.novara.core.network.LoreCategory
import com.denova.novara.core.network.LoreItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料库草稿（P4-C / LOR-001 / LOR-004）：标签解析、表单 ↔ 条目往返、分类映射与移动、脏判定。 */
class LoreDraftTest {

    private fun item(
        id: String = "hero",
        type: String = "character",
        importance: String = "major",
        updatedAt: String = "r1",
    ) = LoreItemSummary(
        id = id,
        name = "阿兰",
        type = type,
        enabled = false,
        tags = listOf("剑士", "导师"),
        briefDescription = "北境流亡者",
        importance = importance,
        keywords = listOf("阿兰", "流亡"),
        loadMode = "resident",
        content = "# 背景",
        updatedAt = updatedAt,
    )

    @Test
    fun `tags accept chinese and english separators and dedupe`() {
        assertEquals(listOf("剑士", "导师", "rival"), LoreDraft.parseList("剑士、导师，rival, 剑士\n"))
        assertEquals(emptyList<String>(), LoreDraft.parseList("  ,  、 "))
    }

    @Test
    fun `draft round trips through an item`() {
        val draft = LoreDraft.from(item(), "character")
        assertEquals(false, draft.enabled)
        assertEquals("剑士、导师", draft.tagsText)
        assertEquals("阿兰、流亡", draft.keywordsText)

        val input = draft.toInput(baseRevision = "r1")
        assertEquals("阿兰", input.name)
        assertEquals(listOf("剑士", "导师"), input.tags)
        assertEquals("resident", input.loadMode)
        assertEquals("major", input.importance)
        assertEquals("r1", input.baseRevision)
        assertFalse(draft.isNew)
    }

    @Test
    fun `new draft needs a name and keeps the category id as type`() {
        assertFalse(LoreDraft.create("world").canSave())
        assertTrue(LoreDraft.create("world").copy(name = "王都").canSave())
        assertEquals("character", LoreDraft.typeForCategory("character"))
        // 自定义分类的条目 type 就是分类 id；退化成 other 会被服务端拒绝。
        assertEquals("custom-faction", LoreDraft.typeForCategory("custom-faction"))
        assertEquals("custom-faction", LoreDraft.create("custom-faction").type)
    }

    @Test
    fun `draft reports dirty when any editable field differs`() {
        val server = item()
        val clean = LoreDraft.from(server, "character")
        assertTrue(clean.matches(server))

        assertFalse(clean.copy(content = "# 背景（改了）").matches(server))
        assertFalse(clean.copy(tagsText = "剑士").matches(server))
        assertFalse(clean.copy(enabled = true).matches(server))
        // 服务端把重要度归一化为 important 时，草稿用同一默认值比较，不算脏。
        val normalized = LoreDraft.from(item(importance = ""), "character")
        assertTrue(normalized.copy(importance = "important").matches(item(importance = "")))
    }

    @Test
    fun `category move follows the server index semantics`() {
        val categories = listOf(LoreCategory("character"), LoreCategory("location"), LoreCategory("faction"))
        assertEquals(
            listOf("location", "character", "faction"),
            LoreDraft.moveCategory(categories, "character", 1).map { it.id },
        )
        assertEquals(
            listOf("location", "faction", "character"),
            LoreDraft.moveCategory(categories, "character", 2).map { it.id },
        )
        // 越界或未知 id 保持原表（不提交无意义的 move）。
        assertSame(categories, LoreDraft.moveCategory(categories, "character", 3))
        assertSame(categories, LoreDraft.moveCategory(categories, "missing", 0))
        assertSame(categories, LoreDraft.moveCategory(categories, "character", 0))
    }
}
