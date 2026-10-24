package com.denova.novara

import com.denova.novara.core.network.agentFramesFromSource
import com.denova.novara.core.stream.AgentUiFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiStreamReaderTest {

    @Test
    fun `reads chunks and done marker`() = runBlocking {
        val source = Buffer().writeUtf8(
            "data: {\"type\":\"start\",\"messageId\":\"m1\"}\n\n" +
                "data: {\"type\":\"text-delta\",\"id\":\"t1\",\"delta\":\"你好\"}\n\n" +
                "data: [DONE]\n\n",
        )
        val frames = agentFramesFromSource(source).toList()
        assertEquals(3, frames.size)
        assertEquals("start", (frames[0] as AgentUiFrame.Chunk).chunk.type)
        assertEquals("你好", (frames[1] as AgentUiFrame.Chunk).chunk.delta)
        assertTrue(frames[2] is AgentUiFrame.Done)
    }

    @Test
    fun `reads crlf ended stream`() = runBlocking {
        val source = Buffer().writeUtf8("data: {\"type\":\"finish\",\"finishReason\":\"stop\"}\r\n\r\ndata: [DONE]\r\n\r\n")
        val frames = agentFramesFromSource(source).toList()
        assertEquals(2, frames.size)
        assertEquals("stop", (frames[0] as AgentUiFrame.Chunk).chunk.finishReason)
        assertTrue(frames[1] is AgentUiFrame.Done)
    }
}
