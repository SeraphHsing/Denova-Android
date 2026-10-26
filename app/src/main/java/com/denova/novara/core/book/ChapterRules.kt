package com.denova.novara.core.book

/**
 * 章节状态的判定规则（WRI-015），对齐固定基线：
 *
 * - 状态字符串由后端算出（`空章` / `初稿` / `成章`），**客户端只读**，可写字段只有 `confirmed`；
 * - 空章（`words == 0`）的 `confirmed` 永远不会为 true，界面必须禁用「确认成章」；
 * - 已是成章时按钮变成「标回初稿」。
 */
object ChapterRules {

    /** 空章不可确认：服务端有 `words > 0` 前置（`internal/book/summary.go:135`）。 */
    fun canConfirm(words: Int, confirmed: Boolean): Boolean = words > 0 && !confirmed

    /** 只有已确认的章节才能标回初稿。 */
    fun canMarkDraft(confirmed: Boolean): Boolean = confirmed

    /** 按钮文案口径：已确认 → 标回初稿，否则 → 确认成章。 */
    fun actionLabel(confirmed: Boolean): String = if (confirmed) "标回初稿" else "确认成章"

    /** 提交给 `PATCH …/book/chapter-status` 的目标值。 */
    fun nextConfirmed(confirmed: Boolean): Boolean = !confirmed

    /** 状态徽标：后端字符串直出；空值按空章处理，避免界面出现空白徽标。 */
    fun statusLabel(status: String, words: Int): String = when {
        status.isNotBlank() -> status
        words == 0 -> "空章"
        else -> "初稿"
    }

    /** 列表摘要：「3 章 · 共 1,234 字」。 */
    fun summaryLabel(chapterCount: Int, totalWords: Int): String = "$chapterCount 章 · 共 $totalWords 字"
}
