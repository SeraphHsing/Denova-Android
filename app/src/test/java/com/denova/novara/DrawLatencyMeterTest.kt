package com.denova.novara

import com.denova.novara.core.stream.DrawLatencyMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawLatencyMeterTest {

    @Test
    fun `empty meter has no summary`() {
        assertNull(DrawLatencyMeter().summary())
    }

    @Test
    fun `reports percentiles and max`() {
        val meter = DrawLatencyMeter(capacity = 100)
        listOf(10L, 20L, 30L, 40L, 50L).forEach { meter.add(it) }
        val summary = meter.summary()!!
        assertEquals(5, meter.size)
        assertTrue(summary.contains("p50=30ms"))
        assertTrue(summary.contains("max=50ms"))
    }

    @Test
    fun `ignores samples beyond capacity`() {
        val meter = DrawLatencyMeter(capacity = 3)
        repeat(10) { meter.add(it.toLong()) }
        assertEquals(3, meter.size)
    }

    @Test
    fun `reset clears samples`() {
        val meter = DrawLatencyMeter(capacity = 10)
        meter.add(5)
        meter.reset()
        assertEquals(0, meter.size)
        assertNull(meter.summary())
    }
}
