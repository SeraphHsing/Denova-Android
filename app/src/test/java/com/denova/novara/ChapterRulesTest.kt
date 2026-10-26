package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

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
        assertEquals(R.string.chapter_action_confirm, ChapterRules.actionLabel(false))
        assertEquals(R.string.chapter_action_unconfirm, ChapterRules.actionLabel(true))
        assertTrue(ChapterRules.nextConfirmed(false))
        assertFalse(ChapterRules.nextConfirmed(true))
    }

    @Test
    fun `status label prefers the server string and falls back by word count`() {
        assertEquals(UiText.raw("成章"), ChapterRules.statusLabel("成章", 900))
        assertEquals(UiText.of(R.string.chapter_status_empty), ChapterRules.statusLabel("", 0))
        assertEquals(UiText.of(R.string.chapter_status_draft), ChapterRules.statusLabel("", 42))
    }

    @Test
    fun `summary label reports chapter count and words`() {
        assertEquals(UiText.of(R.string.chapter_summary, 3, 1234), ChapterRules.summaryLabel(3, 1234))
    }
}
