package com.denova.novara.core.terminal

import com.denova.novara.core.stream.DrawLatencyMeter
import java.util.Locale

/**
 * D03-P 终端性能埋点（固定 PTY 速率重放时使用）。
 *
 * 口径见 `docs/android-web-parity/06-development-and-validation.md` §4：
 * - 输出可见延迟：PTY 字节到达 → 该批像素绘制完成
 * - 输入可见延迟：用户提交输入 → 回显可见
 * - 积压量：已入队未消费的帧数峰值（含丢弃计数）
 * - 解析吞吐：单帧解析耗时（avg / max）
 * - resize 耗时：网格重排（`TerminalScreen.resize`）本地耗时
 * - 刷新次数：16ms 合帧后的实际重绘次数
 *
 * 只累计计数与时长，不记录任何终端内容（AGENTS §5）。
 */
object TerminalPerf {

    private val lock = Any()

    private var frames = 0L
    private var bytes = 0L
    private var droppedFrames = 0L
    private var backlog = 0
    private var maxBacklog = 0

    private var parseMicrosTotal = 0L
    private var parseMicrosMax = 0L
    private var parseSamples = 0L

    private var flushes = 0L

    private var resizeCount = 0L
    private var resizeMillisTotal = 0L
    private var resizeMillisMax = 0L

    /** PTY 字节到达 → 绘制完成（ms）。 */
    val outputDrawLatency = DrawLatencyMeter(8192)

    /** 输入提交 → 回显可见（ms）。 */
    val inputLatency = DrawLatencyMeter(2048)

    private var inputSentAt = 0L
    private var pendingSince = 0L

    // ---- 窗口（每 300 次合帧 ≈ 5s 结算一次，便于剔除预热窗口并取中位）----
    private var windowFlushes = 0L
    private var windowFrames = 0L
    private var windowBytes = 0L
    private var windowDropped = 0L
    private var windowParseMicros = 0L
    private var windowParseSamples = 0L
    private val windowOutLatency = DrawLatencyMeter(4096)
    private val windowInputLatency = DrawLatencyMeter(1024)

    fun reset() = synchronized(lock) {
        frames = 0
        bytes = 0
        droppedFrames = 0
        backlog = 0
        maxBacklog = 0
        parseMicrosTotal = 0
        parseMicrosMax = 0
        parseSamples = 0
        flushes = 0
        resizeCount = 0
        resizeMillisTotal = 0
        resizeMillisMax = 0
        outputDrawLatency.reset()
        inputLatency.reset()
        inputSentAt = 0
        pendingSince = 0
        windowFlushes = 0
        windowFrames = 0
        windowBytes = 0
        windowDropped = 0
        windowParseMicros = 0
        windowParseSamples = 0
        windowOutLatency.reset()
        windowInputLatency.reset()
    }

    /** 收到一个 binary 帧；[queued] 为 false 表示通道满已丢弃。 */
    fun onFrame(bytesCount: Int, queued: Boolean) = synchronized(lock) {
        frames++
        bytes += bytesCount
        windowFrames++
        windowBytes += bytesCount
        if (queued) {
            backlog++
            if (backlog > maxBacklog) maxBacklog = backlog
        } else {
            droppedFrames++
            windowDropped++
        }
    }

    /** 消费一帧并记录解析耗时（微秒：单帧解析通常不足 1 ms，毫秒会丢精度）。 */
    fun onFrameParsed(parseMicros: Long) = synchronized(lock) {
        if (backlog > 0) backlog--
        parseSamples++
        parseMicrosTotal += parseMicros
        if (parseMicros > parseMicrosMax) parseMicrosMax = parseMicros
        windowParseSamples++
        windowParseMicros += parseMicros
        if (pendingSince == 0L) pendingSince = System.currentTimeMillis()
    }

    fun onResize(resizeMillis: Long) = synchronized(lock) {
        resizeCount++
        resizeMillisTotal += resizeMillis
        if (resizeMillis > resizeMillisMax) resizeMillisMax = resizeMillis
    }

    fun markInputSent() = synchronized(lock) {
        inputSentAt = System.currentTimeMillis()
    }

    /** 一次合帧刷新落屏。 */
    fun onFlush() = synchronized(lock) {
        flushes++
        windowFlushes++
        val now = System.currentTimeMillis()
        if (pendingSince != 0L) {
            outputDrawLatency.add(now - pendingSince)
            windowOutLatency.add(now - pendingSince)
            pendingSince = 0
        }
        if (inputSentAt != 0L) {
            inputLatency.add(now - inputSentAt)
            windowInputLatency.add(now - inputSentAt)
            inputSentAt = 0
        }
    }

    /**
     * 窗口汇总并清零：每 ~5s 输出一次，供测量脚本按窗口取中位、丢弃首个预热窗口。
     */
    fun windowSummaryAndReset(): String = synchronized(lock) {
        val avgParse = if (windowParseSamples == 0L) 0.0 else windowParseMicros.toDouble() / windowParseSamples / 1000.0
        val window = String.format(
            Locale.US,
            "WINDOW flushes=%d frames=%d bytes=%d dropped=%d parseAvg=%.3fms out[%s] input[%s]",
            windowFlushes,
            windowFrames,
            windowBytes,
            windowDropped,
            avgParse,
            windowOutLatency.summary() ?: "n=0",
            windowInputLatency.summary() ?: "n=0",
        )
        windowFlushes = 0
        windowFrames = 0
        windowBytes = 0
        windowDropped = 0
        windowParseMicros = 0
        windowParseSamples = 0
        windowOutLatency.reset()
        windowInputLatency.reset()
        window
    }

    /** 单行汇总，供 logcat 采样（英文、无内容、无凭证）。 */
    fun summary(): String = synchronized(lock) {
        val avgParseMs = if (parseSamples == 0L) 0.0 else parseMicrosTotal.toDouble() / parseSamples / 1000.0
        val maxParseMs = parseMicrosMax / 1000.0
        val avgResize = if (resizeCount == 0L) 0.0 else resizeMillisTotal.toDouble() / resizeCount
        String.format(
            Locale.US,
            "frames=%d bytes=%d dropped=%d backlogMax=%d flushes=%d " +
                "parseAvg=%.3fms parseMax=%.3fms " +
                "resizeCount=%d resizeAvg=%.2fms resizeMax=%dms " +
                "outDraw[%s] input[%s]",
            frames,
            bytes,
            droppedFrames,
            maxBacklog,
            flushes,
            avgParseMs,
            maxParseMs,
            resizeCount,
            avgResize,
            resizeMillisMax,
            outputDrawLatency.summary() ?: "n=0",
            inputLatency.summary() ?: "n=0",
        )
    }
}
