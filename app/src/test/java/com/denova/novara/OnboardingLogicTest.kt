package com.denova.novara

import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.core.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从想法创建书籍」引导（WRI-001）：三步顺序、进度、提示词与展示门槛。
 */
class OnboardingLogicTest {

    @Test
    fun `steps follow model then book then agent`() {
        assertEquals(OnboardingStep.MODEL, OnboardingLogic.firstStep(modelReady = false, bookCount = 0, agentDone = false))
        assertEquals(OnboardingStep.BOOK, OnboardingLogic.firstStep(modelReady = true, bookCount = 0, agentDone = false))
        assertEquals(OnboardingStep.AGENT, OnboardingLogic.firstStep(modelReady = true, bookCount = 2, agentDone = false))
        assertNull(OnboardingLogic.firstStep(modelReady = true, bookCount = 2, agentDone = true))
    }

    @Test
    fun `progress label counts from one to three`() {
        assertEquals(1, OnboardingLogic.stepNumber(OnboardingStep.MODEL))
        assertEquals("2 / 3", OnboardingLogic.progressLabel(OnboardingStep.BOOK))
        assertEquals("3 / 3", OnboardingLogic.progressLabel(OnboardingStep.AGENT))
    }

    @Test
    fun `guide is hidden when skipped or completed`() {
        val step = OnboardingStep.BOOK
        assertTrue(OnboardingLogic.shouldShow(step, skipped = false, completed = false))
        assertFalse(OnboardingLogic.shouldShow(step, skipped = true, completed = false))
        assertFalse(OnboardingLogic.shouldShow(step, skipped = false, completed = true))
        assertFalse(OnboardingLogic.shouldShow(null, skipped = false, completed = false))
    }

    @Test
    fun `init prompt matches the web baseline wording`() {
        assertTrue(OnboardingLogic.INIT_PROMPT.startsWith("请和我一起启动一本新书"))
        assertTrue(OnboardingLogic.INIT_PROMPT.contains("ideas.md"))
        // 基线明确「暂时不要创建大纲、章节或写入资料库」，客户端不得擅自改口径。
        assertTrue(OnboardingLogic.INIT_PROMPT.contains("暂时不要创建大纲、章节或写入资料库"))
    }

    @Test
    fun `first chapter prompt embeds the book title`() {
        val prompt = OnboardingLogic.firstChapterPrompt("灵渊劫")
        assertTrue(prompt.contains("《灵渊劫》"))
        assertTrue(prompt.contains("chapters/"))
    }

    @Test
    fun `every step has a title and body`() {
        OnboardingStep.entries.forEach {
            assertTrue(OnboardingLogic.stepTitle(it).isNotBlank())
            assertTrue(OnboardingLogic.stepBody(it).isNotBlank())
        }
    }
}
