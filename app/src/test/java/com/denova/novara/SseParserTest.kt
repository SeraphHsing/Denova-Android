package com.denova.novara

import com.denova.novara.core.stream.SseParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {

    @Test
    fun `parses a single event frame`() {
        val parser = SseParser()
        val frames = parser.feed("id: 42\nevent: chunk\ndata: {\"content\":\"hi\"}\n\n")
        assertEquals(1, frames.size)
        assertEquals("42", frames[0].id)
        assertEquals("chunk", frames[0].event)
        assertEquals("{\"content\":\"hi\"}", frames[0].data)
    }

    @Test
    fun `splits multiple events in one chunk`() {
        val parser = SseParser()
        val frames = parser.feed("event: a\ndata: 1\n\nevent: b\ndata: 2\n\n")
        assertEquals(listOf("a", "b"), frames.map { it.event })
    }

    @Test
    fun `accumulates split frames across chunks`() {
        val parser = SseParser()
        assertEquals(0, parser.feed("id: 7\nevent: chunk\nda").size)
        val second = parser.feed("ta: {\"content\":\"x\"}\n\n")
        assertEquals(1, second.size)
        assertEquals("chunk", second[0].event)
        assertEquals("{\"content\":\"x\"}", second[0].data)
    }

    @Test
    fun `joins multi-line data with newline`() {
        val parser = SseParser()
        val frames = parser.feed("event: chunk\ndata: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", frames.single().data)
    }

    @Test
    fun `ignores comment and empty lines`() {
        val parser = SseParser()
        val frames = parser.feed(": comment\nevent: chunk\ndata: x\n\n")
        assertEquals(1, frames.size)
        assertEquals("chunk", frames[0].event)
    }

    @Test
    fun `strips single leading space from field value`() {
        val parser = SseParser()
        val frames = parser.feed("event: chunk\ndata: x\n\n")
        assertEquals("x", frames.single().data)
    }

    @Test
    fun `handles crlf framing`() {
        val parser = SseParser()
        val frames = parser.feed("event: chunk\r\ndata: x\r\n\r\n")
        assertEquals(1, frames.size)
        assertEquals("chunk", frames[0].event)
    }

    @Test
    fun `flushes remainder on finish`() {
        val parser = SseParser()
        parser.feed("event: done\ndata: ok")
        val frames = parser.finish()
        assertEquals(1, frames.size)
        assertEquals("done", frames[0].event)
    }

    @Test
    fun `drops frame without event`() {
        val parser = SseParser()
        val frames = parser.feed("id: 1\ndata: no-event\n\n")
        assertEquals(0, frames.size)
    }
}
