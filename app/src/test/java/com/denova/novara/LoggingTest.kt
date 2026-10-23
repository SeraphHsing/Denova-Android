package com.denova.novara

import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.logging.LogLevel
import com.denova.novara.core.logging.LogSystem
import com.denova.novara.core.logging.MemoryLogSink
import com.denova.novara.core.logging.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingTest {

    @Test
    fun `redactor masks sensitive key value pairs`() {
        assertEquals("password=***", Redactor.redact("password=hunter2"))
        assertEquals("token=***", Redactor.redact("token=abc123"))
        assertEquals("connecting to http://192.168.2.102:18081", Redactor.redact("connecting to http://192.168.2.102:18081"))
    }

    @Test
    fun `redactor masks bearer and basic credentials`() {
        assertEquals("Bearer ***", Redactor.redact("Bearer eyJhbGciOiJIUzI1NiJ9.xxx"))
        assertEquals("Basic ***", Redactor.redact("Basic dXNlcjpwYXNz"))
    }

    @Test
    fun `redactor masks secrets inside url query`() {
        assertEquals("GET /api?token=***&foo=bar", Redactor.redact("GET /api?token=abc&foo=bar"))
    }

    @Test
    fun `log system filters below threshold`() {
        val sink = MemoryLogSink()
        val system = LogSystem(sinks = mutableListOf(sink))
        system.setThreshold(LogLevel.WARN)
        system.log(LogLevel.INFO, "T", "info msg")
        system.log(LogLevel.WARN, "T", "warn msg")
        val messages = sink.snapshot().map { it.message }
        assertTrue("warn msg" in messages)
        assertFalse("info msg" in messages)
    }

    @Test
    fun `memory sink keeps bounded ring buffer`() {
        val sink = MemoryLogSink(2)
        val system = LogSystem(sinks = mutableListOf(sink))
        system.log(LogLevel.INFO, "T", "a")
        system.log(LogLevel.INFO, "T", "b")
        system.log(LogLevel.INFO, "T", "c")
        assertEquals(listOf("c", "b"), sink.snapshot().map { it.message })
    }

    @Test
    fun `log system redacts before writing to sink`() {
        val sink = MemoryLogSink()
        val system = LogSystem(sinks = mutableListOf(sink))
        system.log(LogLevel.INFO, "T", "token=secret123")
        assertEquals("token=***", sink.snapshot().single().message)
    }

    @Test
    fun `denova log facade redacts and stores`() {
        DenovaLog.memory.clear()
        DenovaLog.i("T", "token=abc")
        val entry = DenovaLog.memory.snapshot().single()
        assertEquals("token=***", entry.message)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("T", entry.tag)
    }
}
