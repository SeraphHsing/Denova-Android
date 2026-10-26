package com.denova.novara

import com.denova.novara.core.book.ChapterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节状态判定（WRI-015）：空章不可确认、已确认才能标回初稿、状态文案直出后端字符串。
 */
class ChapterRulesTest {

    @Test
    fun `empty chapter cannot be confirmed`() {
        assertFalse(ChapterRules.canConfirm(words = 0, confirmed = false))
        assertFalse(ChapterRules.canMarkDraft(confirmed = false))
    }

    @Test
    fun `chapter with words can be confirmed until it already is`() {
        assertTrue(ChapterRules.canConfirm(words = 120, confirmed = false))
        assertFalse(ChapterRules.canConfirm(words = 120, confirmed = true))
        assertTrue(ChapterRules.canMarkDraft(confirmed = true))
    }

    @Test
    fun `action label follows the current confirmed flag`() {
        assertEquals("确认成章", ChapterRules.actionLabel(false))
        assertEquals("标回初稿", ChapterRules.actionLabel(true))
        assertTrue(ChapterRules.nextConfirmed(false))
        assertFalse(ChapterRules.nextConfirmed(true))
    }

    @Test
    fun `status label prefers the server string and falls back by word count`() {
        assertEquals("成章", ChapterRules.statusLabel("成章", 900))
        assertEquals("空章", ChapterRules.statusLabel("", 0))
        assertEquals("初稿", ChapterRules.statusLabel("", 42))
    }

    @Test
    fun `summary label reports chapter count and words`() {
        assertEquals("3 章 · 共 1234 字", ChapterRules.summaryLabel(3, 1234))
    }
}
