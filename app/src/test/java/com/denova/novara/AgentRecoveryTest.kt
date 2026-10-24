package com.denova.novara

import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentRecoveryDecision
import com.denova.novara.core.stream.AgentStreamRecoveryCoordinator
import com.denova.novara.core.stream.AgentStreamTransport
import com.denova.novara.core.stream.AgentUiChunk
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.RecoveryAction
import com.denova.novara.core.stream.RecoveryReceipt
import com.denova.novara.core.stream.decideAgentRecovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecoveryTest {

    private class FakeTransport(var activeResult: AgentActiveView) : AgentStreamTransport {
        val streamCalls = mutableListOf<Pair<String, String?>>()
        val streams = mutableListOf<Flow<AgentUiFrame>>()
        private var index = 0

        override suspend fun agentActive(sessionId: String): AgentActiveView = activeResult

        override fun agentStream(taskId: String, after: String?): Flow<AgentUiFrame> {
            streamCalls.add(taskId to after)
            return if (index < streams.size) streams[index++] else flowOf()
        }

        override suspend fun agentRecover(sessionId: String, action: RecoveryAction): RecoveryReceipt =
            throw UnsupportedOperationException()
    }

    @Test
    fun `decision attaches with stream cursor`() {
        val view = AgentActiveView(active = true, taskId = "t1", streamCursor = 9)
        assertEquals(AgentRecoveryDecision.Attach("t1", "9"), decideAgentRecovery(view, null))
    }

    @Test
    fun `suspended settles`() {
        assertEquals(
            AgentRecoveryDecision.Settled,
            decideAgentRecovery(AgentActiveView(active = false, phase = "suspended"), null),
        )
    }

    @Test
    fun `inactive settles`() {
        assertEquals(AgentRecoveryDecision.Settled, decideAgentRecovery(AgentActiveView(active = false), null))
    }

    @Test
    fun `null active retries`() {
        assertEquals(AgentRecoveryDecision.RetryActive, decideAgentRecovery(null, null))
    }

    @Test
    fun `rehydrate codes map to rehydrate`() {
        val codes = listOf(
            "agent_runtime.rehydrate_required",
            "agent_stream.cursor_expired",
            "agent_stream.invalid_cursor",
            "agent_stream.rehydrate_required",
        )
        for (code in codes) {
            assertEquals(
                AgentRecoveryDecision.Rehydrate,
                decideAgentRecovery(null, GameStreamError(409, code, "x")),
            )
        }
    }

    @Test
    fun `coordinator attaches and finishes`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1", streamCursor = 5))
        val chunk = AgentUiFrame.Chunk(AgentUiChunk(type = "text-delta", delta = "hi"))
        transport.streams.add(flowOf(chunk, AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(listOf(chunk, AgentUiFrame.Done), frames)
        assertEquals(listOf("t1" to "5"), transport.streamCalls)
        assertEquals(0, reloads)
    }

    @Test
    fun `rehydrate chunk reloads and reattaches`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1", streamCursor = 5))
        transport.streams.add(flowOf(AgentUiFrame.Chunk(AgentUiChunk(type = "task_rehydrate_required"))))
        transport.streams.add(flowOf(AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(listOf(AgentUiFrame.Done), frames)
        assertEquals(1, reloads)
        assertEquals(2, transport.streamCalls.size)
    }

    @Test
    fun `stream rehydrate error reloads and reattaches`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        transport.streams.add(flow<AgentUiFrame> { throw GameStreamError(409, "agent_runtime.rehydrate_required", "x") })
        transport.streams.add(flowOf(AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(1, reloads)
        assertEquals(2, transport.streamCalls.size)
        assertEquals(1, frames.size)
    }

    @Test
    fun `suspended does not attach`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = false, phase = "suspended"))
        val coordinator = AgentStreamRecoveryCoordinator(transport) {}

        val frames = coordinator.recover("s1").toList()

        assertTrue(frames.isEmpty())
        assertEquals(0, transport.streamCalls.size)
    }
}
