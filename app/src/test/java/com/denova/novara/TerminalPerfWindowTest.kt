package com.denova.novara

import com.denova.novara.core.terminal.TerminalPerf
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03-P 埋点的窗口语义：每 5s 输出一次窗口汇总后必须清零，
 * 否则「剔除预热窗口再取中位」会退化成同一条累计曲线，测量结论不成立。
 */
class TerminalPerfWindowTest {

    @Test
    fun `window summary resets window counters but keeps totals`() {
        TerminalPerf.reset()
        TerminalPerf.onFrame(1024, queued = true)
        TerminalPerf.onFrameParsed(120)
        TerminalPerf.onFlush()

        val window = TerminalPerf.windowSummaryAndReset()
        assertTrue(window, window.contains("frames=1"))
        assertTrue(window, window.contains("bytes=1024"))
        assertTrue(window, window.contains("out[n=1"))

        val empty = TerminalPerf.windowSummaryAndReset()
        assertTrue(empty, empty.contains("frames=0"))

        // 累计值不受窗口清零影响
        val total = TerminalPerf.summary()
        assertTrue(total, total.contains("frames=1"))
    }

    @Test
    fun `dropped frames are counted separately from backlog`() {
        TerminalPerf.reset()
        TerminalPerf.onFrame(1024, queued = true)
        TerminalPerf.onFrame(1024, queued = false)

        val summary = TerminalPerf.summary()
        assertTrue(summary, summary.contains("dropped=1"))
        assertTrue(summary, summary.contains("backlogMax=1"))
    }
}
