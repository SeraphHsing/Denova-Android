package com.denova.novara.core.onboarding

import androidx.annotation.StringRes
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

/** 新手引导的三步核心（对齐 Web `OnboardingGuide.tsx:14` 的 `CoreStepId`）。 */
enum class OnboardingStep { MODEL, BOOK, AGENT }

/**
 * 「从想法创建书籍」引导（WRI-001）。
 *
 * 对齐固定基线：Web 只有三步核心引导（模型 → 书籍 → Agent），**没有题材/风格/篇幅向导**；
 * 建书只提交 `title/author/description`，章节由写作 Agent 用工具落盘。
 * 「从想法开始」= 建书 + 把 [INIT_PROMPT] 填进 Agent 输入框（**不自动发送**，
 * 基线明确不自动消耗模型调用，见 `onboarding.ts` 的 agent 步文案）。
 *
 * 文案走资源（NAV-007）：提示词在界面外也要用（拼进导航参数），所以返回 [UiText]；
 * 步骤标题/说明只给 Compose 用，直接返回 `@StringRes`。
 */
object OnboardingLogic {

    /** 三步总数（用于「1 / 3」进度）。 */
    const val TOTAL = 3

    /** Web `writingAgent.initPrompt`：从想法启动一本新书。 */
    val INIT_PROMPT: UiText = UiText.of(R.string.onboard_init_prompt)

    /** Web `onboarding.firstPrompt`：进入 Agent 步后填入的第一章请求。 */
    fun firstChapterPrompt(book: String): UiText = UiText.of(R.string.onboard_first_prompt, book)

    /**
     * 当前应展示的步骤：模型不可用 → MODEL；没有书 → BOOK；还没有完成过一次 Agent 回合 → AGENT；
     * 三步都满足返回 null（引导结束，不再打扰）。
     */
    fun firstStep(modelReady: Boolean, bookCount: Int, agentDone: Boolean): OnboardingStep? = when {
        !modelReady -> OnboardingStep.MODEL
        bookCount <= 0 -> OnboardingStep.BOOK
        !agentDone -> OnboardingStep.AGENT
        else -> null
    }

    /** 进度序号（1 起）。 */
    fun stepNumber(step: OnboardingStep): Int = step.ordinal + 1

    /** 进度文案「1 / 3」。 */
    fun progressLabel(step: OnboardingStep): String = "${stepNumber(step)} / $TOTAL"

    /** 是否需要弹出引导：用户已跳过/完成、或三步都满足时不再展示。 */
    fun shouldShow(step: OnboardingStep?, skipped: Boolean, completed: Boolean): Boolean =
        step != null && !skipped && !completed

    /** 各步标题（对齐 Web `onboarding.step.*.title`）。 */
    @StringRes
    fun stepTitle(step: OnboardingStep): Int = when (step) {
        OnboardingStep.MODEL -> R.string.onboard_step_model_title
        OnboardingStep.BOOK -> R.string.onboard_step_book_title
        OnboardingStep.AGENT -> R.string.onboard_step_agent_title
    }

    /** 各步说明（对齐 Web `onboarding.step.*.body`）。 */
    @StringRes
    fun stepBody(step: OnboardingStep): Int = when (step) {
        OnboardingStep.MODEL -> R.string.onboard_step_model_body
        OnboardingStep.BOOK -> R.string.onboard_step_book_body
        OnboardingStep.AGENT -> R.string.onboard_step_agent_body
    }
}
