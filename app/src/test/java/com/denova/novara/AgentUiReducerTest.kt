package com.denova.novara

import com.denova.novara.core.stream.AgentPhase
import com.denova.novara.core.stream.AgentUiChunk
import com.denova.novara.core.stream.AgentUiReducer
import com.denova.novara.core.stream.AgentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiReducerTest {

    @Test
    fun `text deltas accumulate content`() {
        var state = AgentUiState()
        state = AgentUiReducer.reduce(state, AgentUiChunk(type = "start"))
        state = AgentUiReducer.reduce(state, AgentUiChunk(type = "text-delta", delta = "你"))
        state = AgentUiReducer.reduce(state, AgentUiChunk(type = "text-delta", delta = "好"))
        assertEquals("你好", state.content)
        assertEquals(AgentPhase.RUNNING, state.phase)
    }

    @Test
    fun `reasoning deltas accumulate thinking`() {
        var state = AgentUiState()
        state = AgentUiReducer.reduce(state, AgentUiChunk(type = "reasoning-delta", delta = "想"))
        state = AgentUiReducer.reduce(state, AgentUiChunk(type = "reasoning-delta", delta = "法"))
        assertEquals("想法", state.thinking)
    }

    @Test
    fun `tool input sets activity`() {
        val state = AgentUiReducer.reduce(
            AgentUiState(),
            AgentUiChunk(type = "tool-input-start", toolName = "search"),
        )
        assertEquals("tool:search", state.activity)
    }

    @Test
    fun `tool output clears activity`() {
        val state = AgentUiReducer.reduce(
            AgentUiState(activity = "tool:search"),
            AgentUiChunk(type = "tool-output-available"),
        )
        assertEquals("", state.activity)
    }

    @Test
    fun `finish stop marks done`() {
        val state = AgentUiReducer.reduce(AgentUiState(phase = AgentPhase.RUNNING), AgentUiChunk(type = "finish", finishReason = "stop"))
        assertEquals(AgentPhase.DONE, state.phase)
    }

    @Test
    fun `finish error marks error`() {
        val state = AgentUiReducer.reduce(AgentUiState(phase = AgentPhase.RUNNING), AgentUiChunk(type = "finish", finishReason = "error"))
        assertEquals(AgentPhase.ERROR, state.phase)
    }

    @Test
    fun `error chunk carries error text`() {
        val state = AgentUiReducer.reduce(AgentUiState(), AgentUiChunk(type = "error", errorText = "boom"))
        assertEquals(AgentPhase.ERROR, state.phase)
        assertEquals("boom", state.errorText)
    }

    @Test
    fun `unknown chunk leaves state unchanged`() {
        val state = AgentUiState(content = "x", phase = AgentPhase.RUNNING)
        assertTrue(state == AgentUiReducer.reduce(state, AgentUiChunk(type = "brand-new")))
    }
}
