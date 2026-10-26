package com.denova.novara.core.editor

/** 一次可撤销状态的快照：正文 + 光标位置（撤销/重做时一起恢复）。 */
data class EditSnapshot(
    val text: String,
    val caret: Int = 0,
)

/**
 * 撤销 / 重做栈（WRI-014「选区和撤销按基线更新」的验证条件）。
 *
 * 记录的是**改动前**的快照，因此撤销回到「这一次编辑突发开始之前」：
 * 连续输入（间隔小于 [coalesceMillis]）只保留最早的那份基线，避免每个字符各占一步。
 *
 * [reset] 对应 Web 的 `resetHistory`：打开文件、加载外部版本、采纳合并结果之后，
 * 旧正文不再可撤销 —— 否则用户能撤回到被丢弃的本地改动，再保存就把服务端内容覆盖掉。
 */
class EditHistory(
    private val capacity: Int = 100,
    private val coalesceMillis: Long = COALESCE_MILLIS,
) {
    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var lastRecordAt: Long? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 基线重设：两侧历史清空，当前内容成为新的起点。 */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        lastRecordAt = null
    }

    /**
     * 记录改动前快照。[now] 由调用方给（毫秒），便于单测构造突发与停顿。
     * 新的编辑会让重做栈失效——分叉之后原来的「重做」不再是同一时间线。
     */
    fun record(now: Long, before: EditSnapshot) {
        if (redoStack.isNotEmpty()) redoStack.clear()
        val last = lastRecordAt
        lastRecordAt = now
        // 同一突发：已有基线代表突发起点，不再压栈。
        if (last != null && now - last < coalesceMillis && undoStack.isNotEmpty()) return
        undoStack.addLast(before)
        while (undoStack.size > capacity) undoStack.removeFirst()
    }

    /** 撤销：返回应恢复到的快照，当前快照进重做栈；无可撤销时返回 null。 */
    fun undo(current: EditSnapshot): EditSnapshot? =
        undoStack.removeLastOrNull()?.also {
            redoStack.addLast(current)
            // 撤销之后的编辑必须开新基线，不能与撤销前那次突发合并。
            lastRecordAt = null
        }

    /** 重做：返回应恢复到的快照，当前快照回到撤销栈；无可重做时返回 null。 */
    fun redo(current: EditSnapshot): EditSnapshot? =
        redoStack.removeLastOrNull()?.also {
            undoStack.addLast(current)
            lastRecordAt = null
        }

    companion object {
        /** 连续输入的合并窗口：一次停顿超过它才算新的可撤销步骤。 */
        const val COALESCE_MILLIS = 700L
    }
}
