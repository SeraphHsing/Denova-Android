package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.core.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // 文案落在资源里（values-zh 与基线同口径），这里守「指向哪条资源」。
        assertEquals(UiText.of(R.string.onboard_init_prompt), OnboardingLogic.INIT_PROMPT)
    }

    @Test
    fun `first chapter prompt embeds the book title`() {
        val prompt = OnboardingLogic.firstChapterPrompt("灵渊劫")
        assertEquals(UiText.of(R.string.onboard_first_prompt, "灵渊劫"), prompt)
    }

    @Test
    fun `every step has a title and body`() {
        OnboardingStep.entries.forEach {
            assertNotEquals(0, OnboardingLogic.stepTitle(it))
            assertNotEquals(0, OnboardingLogic.stepBody(it))
        }
    }
}
