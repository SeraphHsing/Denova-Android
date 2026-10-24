package com.denova.novara

import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.AgentUiStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiStreamTest {

    private fun chunk(frame: AgentUiFrame): com.denova.novara.core.stream.AgentUiChunk =
        (frame as AgentUiFrame.Chunk).chunk

    @Test
    fun `parses start text delta and done`() {
        val parser = AgentUiStreamParser()
        val frames = parser.feed(
            "data: {\"type\":\"start\",\"messageId\":\"m1\"}\n\n" +
                "data: {\"type\":\"text-delta\",\"id\":\"t1\",\"delta\":\"你好\"}\n\n" +
                "data: [DONE]\n\n",
        )
        assertEquals(3, frames.size)
        assertEquals("start", chunk(frames[0]).type)
        assertEquals("m1", chunk(frames[0]).messageId)
        assertEquals("text-delta", chunk(frames[1]).type)
        assertEquals("你好", chunk(frames[1]).delta)
        assertTrue(frames[2] is AgentUiFrame.Done)
    }

    @Test
    fun `accumulates split frame across chunks`() {
        val parser = AgentUiStreamParser()
        assertEquals(0, parser.feed("data: {\"type\":\"text-star").size)
        val frames = parser.feed("t\",\"id\":\"t1\"}\n\n")
        assertEquals(1, frames.size)
        assertEquals("text-start", chunk(frames[0]).type)
    }

    @Test
    fun `parses data part payload`() {
        val parser = AgentUiStreamParser()
        val frames = parser.feed(
            "data: {\"type\":\"data-agent-activity\",\"id\":\"a1\",\"data\":{\"event\":\"tool_call\",\"name\":\"search\"}}\n\n",
        )
        val c = chunk(frames.single())
        assertEquals("data-agent-activity", c.type)
        assertNotNull(c.data)
        assertTrue(c.data.toString().contains("tool_call"))
    }

    @Test
    fun `parses error and finish frames`() {
        val parser = AgentUiStreamParser()
        val frames = parser.feed(
            "data: {\"type\":\"error\",\"errorText\":\"boom\"}\n\n" +
                "data: {\"type\":\"finish\",\"finishReason\":\"stop\"}\n\ndata: [DONE]\n\n",
        )
        assertEquals("boom", chunk(frames[0]).errorText)
        assertEquals("stop", chunk(frames[1]).finishReason)
        assertTrue(frames[2] is AgentUiFrame.Done)
    }

    @Test
    fun `keeps unknown chunk type`() {
        val parser = AgentUiStreamParser()
        val frames = parser.feed("data: {\"type\":\"brand-new-part\",\"id\":\"x\"}\n\n")
        assertEquals("brand-new-part", chunk(frames.single()).type)
    }

    @Test
    fun `skips malformed json`() {
        val parser = AgentUiStreamParser()
        assertEquals(0, parser.feed("data: not-json\n\n").size)
    }

    @Test
    fun `flushes remainder on finish`() {
        val parser = AgentUiStreamParser()
        parser.feed("data: {\"type\":\"finish\",\"finishReason\":\"stop\"}\n\ndata: [DONE]")
        val frames = parser.finish()
        assertEquals(1, frames.size)
        assertTrue(frames[0] is AgentUiFrame.Done)
    }
}
