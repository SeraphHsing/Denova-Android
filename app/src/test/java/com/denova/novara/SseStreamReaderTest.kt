package com.denova.novara

import com.denova.novara.core.network.sseEventsFromSource
import com.denova.novara.core.stream.GameEventType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseStreamReaderTest {

    @Test
    fun `reads and decodes events from source`() = runBlocking {
        val source = Buffer().writeUtf8("id: 42\nevent: chunk\ndata: {\"content\":\"hi\"}\n\n")
        val events = sseEventsFromSource(source).toList()
        assertEquals(1, events.size)
        assertEquals("42", events[0].id)
        assertEquals(GameEventType.CHUNK, events[0].type)
        assertEquals("{\"content\":\"hi\"}", events[0].data)
    }

    @Test
    fun `reads multiple events`() = runBlocking {
        val source = Buffer().writeUtf8("event: chunk\ndata: a\n\nevent: done\ndata: b\n\n")
        val events = sseEventsFromSource(source).toList()
        assertEquals(listOf(GameEventType.CHUNK, GameEventType.DONE), events.map { it.type })
    }

    @Test
    fun `handles crlf line endings`() = runBlocking {
        val source = Buffer().writeUtf8("id: 7\r\nevent: chunk\r\ndata: x\r\n\r\n")
        val events = sseEventsFromSource(source).toList()
        assertEquals(1, events.size)
        assertEquals("7", events[0].id)
        assertEquals(GameEventType.CHUNK, events[0].type)
        assertEquals("x", events[0].data)
    }
}
