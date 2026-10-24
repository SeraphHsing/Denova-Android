package com.denova.novara

import com.denova.novara.feature.editor.EditorPerf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D03-W 草稿提交窗口与埋点状态（G2 判据口径）。 */
class EditorPerfTest {

    @Test
    fun `pending edit window grows after edit and clears after draft persisted`() {
        EditorPerf.reset()
        assertEquals(0L, EditorPerf.pendingEditMillis())

        EditorPerf.markEditStart()
        Thread.sleep(15)
        val pending = EditorPerf.pendingEditMillis()
        assertTrue("pending window should grow, was $pending", pending >= 10L)

        EditorPerf.markDraftPersisted()
        assertEquals(0L, EditorPerf.pendingEditMillis())
    }

    @Test
    fun `reset clears pending window`() {
        EditorPerf.markEditStart()
        EditorPerf.reset()
        assertEquals(0L, EditorPerf.pendingEditMillis())
    }
}
