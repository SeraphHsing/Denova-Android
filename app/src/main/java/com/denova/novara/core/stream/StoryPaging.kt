package com.denova.novara.core.stream

/** 单轮在叙事面板里的可读文本（`> 玩家输入` + 叙事），与历史基线同口径。 */
object StoryTurnText {

    fun of(turn: TurnEvent): String = buildString {
        // 跳过内部提示（如 `[Source: story opening configuration...]`），不展示给用户。
        val user = turn.user?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("[Source:") }
        if (user != null) append("> ").append(user.trim())
        if (!turn.narrative.isNullOrBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(turn.narrative.trim())
        }
    }
}

/** 轮次锚点：用于「跳转指定轮次」与「版本切换 / 重生成」。 */
data class TurnAnchor(
    val turnId: String,
    /** 轮次序号（1 基，含已翻页的更早轮次）。 */
    val number: Int,
    /** 展示用摘要（玩家输入首段，空则用叙事首段）。 */
    val label: String,
    /** 该轮在叙事块列表里的首块索引。 */
    val blockIndex: Int,
    /** 版本总数（<=1 时 UI 不展示切换控件）。 */
    val versionCount: Int = 1,
    /** 当前版本序号（1 基）。 */
    val versionNumber: Int = 1,
    /** 该轮是否能重生成（玩家输入为空的开场轮由服务端 `start_opening` 处理，这里只挡空输入）。 */
    val canRegenerate: Boolean = true,
)

object StoryPaging {

    private const val LABEL_CHARS = 28

    /**
     * 把更早的一页前置合并到现有轮次：按 id 去重，保持「旧 → 新」顺序。
     * 服务端分页游标可能重叠（`before` 为包含边界），去重避免重复渲染。
     */
    fun mergeOlder(older: List<TurnEvent>, current: List<TurnEvent>): List<TurnEvent> {
        val existing = current.map { it.id }.filter { it.isNotBlank() }.toSet()
        return older.filter { it.id.isBlank() || it.id !in existing } + current
    }

    /**
     * 生成轮次锚点：只保留有正文的轮次（跳转到空轮次没有意义）。
     * [blockIndexOf] 由叙事分块缓冲提供，未命中则跳过该轮。
     */
    fun anchors(
        turns: List<TurnEvent>,
        firstNumber: Int,
        blockIndexOf: (String) -> Int?,
    ): List<TurnAnchor> = turns.mapIndexedNotNull { index, turn ->
        val blockIndex = blockIndexOf(turn.id) ?: return@mapIndexedNotNull null
        val versionCount = turn.versions.size.coerceAtLeast(1)
        TurnAnchor(
            turnId = turn.id,
            number = firstNumber + index,
            label = label(turn),
            blockIndex = blockIndex,
            versionCount = versionCount,
            versionNumber = currentVersionNumber(turn, versionCount),
            canRegenerate = canRegenerate(turn),
        )
    }

    /** 当前版本序号（1 基）：优先看 `versions[].current`，回退 `version_idx`。 */
    fun currentVersionNumber(turn: TurnEvent, versionCount: Int = turn.versions.size): Int {
        val fromFlag = turn.versions.indexOfFirst { it.current }
        if (fromFlag >= 0) return fromFlag + 1
        if (turn.versionIdx in 0 until versionCount) return turn.versionIdx + 1
        return 1
    }

    /**
     * 切版本的目标 id：`direction=-1` 取上一版、`+1` 取下一版；越界返回 null。
     * 与 Web 一致——`turn_id` 保持当前轮，只换 `version_turn_id`。
     */
    fun versionTarget(turn: TurnEvent, direction: Int): String? {
        if (turn.versions.size < 2) return null
        val current = currentVersionNumber(turn) - 1
        val target = current + direction
        if (target !in turn.versions.indices) return null
        return turn.versions[target].turnId.takeIf { it.isNotBlank() }
    }

    /** 重生成需要能重放该轮的玩家输入；只有内部提示的空输入不允许。 */
    fun replayInput(turn: TurnEvent): String? {
        val user = turn.user?.trim().orEmpty()
        if (user.isBlank() || user.startsWith("[Source:")) return null
        return user
    }

    private fun canRegenerate(turn: TurnEvent): Boolean = replayInput(turn) != null

    fun label(turn: TurnEvent): String {
        val user = turn.user?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("[Source:") }
        val text = user?.trim()
            ?: turn.narrative?.trim().orEmpty()
        return if (text.length > LABEL_CHARS) text.take(LABEL_CHARS) + "…" else text
    }
}
