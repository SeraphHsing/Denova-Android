package com.denova.novara

import com.denova.novara.core.network.RequestGeneration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求世代语义（迟到响应作废，AUTH-004 基础设施）。 */
class RequestGenerationTest {

    @Test
    fun `snapshot is current until retire`() {
        val generation = RequestGeneration()
        val snapshot = generation.snapshot()
        assertTrue(generation.isCurrent(snapshot))
    }

    @Test
    fun `retire invalidates prior snapshots`() {
        val generation = RequestGeneration()
        val old = generation.snapshot()
        generation.retire()
        assertFalse(generation.isCurrent(old))
        assertTrue(generation.isCurrent(generation.snapshot()))
    }

    @Test
    fun `multiple retires keep increasing generation`() {
        val generation = RequestGeneration()
        val first = generation.retire()
        val second = generation.retire()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }
}
