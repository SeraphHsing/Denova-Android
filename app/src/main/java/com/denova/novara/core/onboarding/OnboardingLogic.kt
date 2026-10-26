package com.denova.novara.core.onboarding

/** 新手引导的三步核心（对齐 Web `OnboardingGuide.tsx:14` 的 `CoreStepId`）。 */
enum class OnboardingStep { MODEL, BOOK, AGENT }

/**
 * 「从想法创建书籍」引导（WRI-001）。
 *
 * 对齐固定基线：Web 只有三步核心引导（模型 → 书籍 → Agent），**没有题材/风格/篇幅向导**；
 * 建书只提交 `title/author/description`，章节由写作 Agent 用工具落盘。
 * 「从想法开始」= 建书 + 把 [INIT_PROMPT] 填进 Agent 输入框（**不自动发送**，
 * 基线明确不自动消耗模型调用，见 `onboarding.ts` 的 agent 步文案）。
 */
object OnboardingLogic {

    /** 三步总数（用于「1 / 3」进度）。 */
    const val TOTAL = 3

    /** Web `writingAgent.initPrompt`：从想法启动一本新书。 */
    const val INIT_PROMPT =
        "请和我一起启动一本新书：先读取 ideas.md 和 CREATOR.md，通过对话梳理灵感、题材、冲突、世界观、人设和写作规则；" +
            "信息不足时先追问，阶段性结论更新到 ideas.md。暂时不要创建大纲、章节或写入资料库。"

    /** Web `onboarding.firstPrompt`：进入 Agent 步后填入的第一章请求。 */
    fun firstChapterPrompt(book: String): String =
        "请基于《$book》创建第一章 Markdown 文件，并写一个可继续扩展的开头。" +
            "先聚焦开场场景、主角状态和第一个悬念；正文控制在 800-1200 字左右，写入 chapters/ 下合适的章节文件。" +
            "写完后简要说明你创建或修改了哪些文件。"

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
    fun stepTitle(step: OnboardingStep): String = when (step) {
        OnboardingStep.MODEL -> "先配置语言模型"
        OnboardingStep.BOOK -> "创建你的第一本书"
        OnboardingStep.AGENT -> "用创作 Agent 写第一章开头"
    }

    /** 各步说明（对齐 Web `onboarding.step.*.body`）。 */
    fun stepBody(step: OnboardingStep): String = when (step) {
        OnboardingStep.MODEL -> "写作、游戏和对话需要可用的语言模型。请先新增连接，填写服务商、API 地址和 API Key，再添加模型。"
        OnboardingStep.BOOK -> "Denova 以书籍为工作区组织章节、设定、版本和互动故事。先新建一本书进入创作流程。"
        OnboardingStep.AGENT -> "我会把第一章开头的请求填入输入框。你可以修改后手动发送，不会自动消耗模型调用。"
    }
}
