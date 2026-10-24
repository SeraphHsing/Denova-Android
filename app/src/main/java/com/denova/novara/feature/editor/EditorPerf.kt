package com.denova.novara.feature.editor

import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.stream.DrawLatencyMeter

/**
 * D03-W 编辑器性能埋点（G2 判据口径，见 06 §4）。
 *
 * 只做计数与耗时统计，不改变编辑行为；logcat 过滤 `EditorPerf` 即可取数：
 * - `editor-open`：打开文件（请求发出 → 正文可见）耗时与字符数
 * - `editor-input-visible`：按键 → 该帧绘制完成（每 50 个样本汇总一次）
 * - `editor-save`：保存事务耗时，附「最后一次编辑 → 进入保存」的草稿提交窗口
 */
object EditorPerf {

    private const val TAG = "EditorPerf"
    private const val SUMMARY_EVERY = 50

    private val inputLatency = DrawLatencyMeter(4096)

    private var openAtMillis = 0L
    private var editAtMillis = 0L
    private var lastEditAtMillis = 0L

    fun beginOpen() {
        openAtMillis = System.currentTimeMillis()
    }

    fun endOpen(chars: Int) {
        val started = openAtMillis
        openAtMillis = 0L
        if (started == 0L) return
        DenovaLog.i(TAG, "perf editor-open chars=$chars ms=${System.currentTimeMillis() - started}")
    }

    /** 每次内容变更调用；延迟按「最后一次按键 → 下一次绘制」计算。 */
    fun markEditStart() {
        val now = System.currentTimeMillis()
        editAtMillis = now
        lastEditAtMillis = now
    }

    /** 在 SideEffect 中调用，表示该帧已提交绘制。 */
    fun markDraw() {
        val started = editAtMillis
        if (started == 0L) return
        editAtMillis = 0L
        inputLatency.add(System.currentTimeMillis() - started)
        if (inputLatency.size % SUMMARY_EVERY == 0) {
            inputLatency.summary()?.let { DenovaLog.i(TAG, "perf editor-input-visible $it") }
        }
    }

    /** 距离最后一次编辑的毫秒数；0 表示当前没有待提交编辑（草稿已落盘）。 */
    fun pendingEditMillis(): Long {
        val started = lastEditAtMillis
        return if (started == 0L) 0L else System.currentTimeMillis() - started
    }

    /** 草稿已落盘（保存事务开始即落草稿），清除待提交窗口。 */
    fun markDraftPersisted() {
        lastEditAtMillis = 0L
    }

    fun endSave(kind: String, elapsedMillis: Long, pendingEditMillis: Long) {
        DenovaLog.i(
            TAG,
            "perf editor-save kind=$kind ms=$elapsedMillis draftWindow=${pendingEditMillis}ms",
        )
    }

    fun reset() {
        inputLatency.reset()
        openAtMillis = 0L
        editAtMillis = 0L
        lastEditAtMillis = 0L
    }
}
