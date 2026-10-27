package com.denova.novara

import com.denova.novara.core.lore.LoreDraft
import com.denova.novara.core.network.LoreItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料库草稿（P4-C / LOR-001 / LOR-004）：标签解析、表单 ↔ 条目往返、分类到类型映射。 */
class LoreDraftTest {

    @Test
    fun `tags accept chinese and english separators and dedupe`() {
        assertEquals(listOf("剑士", "导师", "rival"), LoreDraft.parseList("剑士、导师，rival, 剑士\n"))
        assertEquals(emptyList<String>(), LoreDraft.parseList("  ,  、 "))
    }

    @Test
    fun `draft round trips through an item`() {
        val item = LoreItemSummary(
            id = "hero",
            name = "阿兰",
            type = "character",
            enabled = false,
            tags = listOf("剑士", "导师"),
            briefDescription = "北境流亡者",
            importance = "high",
            keywords = listOf("阿兰", "流亡"),
            loadMode = "resident",
            content = "# 背景",
            updatedAt = "r1",
        )

        val draft = LoreDraft.from(item, "character")
        assertEquals(false, draft.enabled)
        assertEquals("剑士、导师", draft.tagsText)
        assertEquals("阿兰、流亡", draft.keywordsText)

        val input = draft.toInput(baseRevision = "r1")
        assertEquals("阿兰", input.name)
        assertEquals(listOf("剑士", "导师"), input.tags)
        assertEquals("resident", input.loadMode)
        assertEquals("r1", input.baseRevision)
        assertFalse(draft.isNew)
    }

    @Test
    fun `new draft needs a name and maps unknown category to other`() {
        assertFalse(LoreDraft.create("world").canSave())
        assertTrue(LoreDraft.create("world").copy(name = "王都").canSave())
        assertEquals("character", LoreDraft.typeForCategory("character"))
        assertEquals("other", LoreDraft.typeForCategory("custom-faction"))
    }
}
