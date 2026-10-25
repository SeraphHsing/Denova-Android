package com.denova.novara

import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentRecoveryDecision
import com.denova.novara.core.stream.AgentStreamRecoveryCoordinator
import com.denova.novara.core.stream.AgentStreamTransport
import com.denova.novara.core.stream.AgentUiChunk
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.completesTurn
import com.denova.novara.core.stream.isTerminal
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
        val streamSessions = mutableListOf<String>()
        val streams = mutableListOf<Flow<AgentUiFrame>>()
        private var index = 0

        override suspend fun agentActive(sessionId: String): AgentActiveView = activeResult

        override fun agentStream(sessionId: String, taskId: String, after: String?): Flow<AgentUiFrame> {
            streamSessions.add(sessionId)
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
        // 挂接必须带 session 身份：服务端缺 session_id 直接 400，恢复链会在第一步断掉。
        assertEquals(listOf("s1"), transport.streamSessions)
        assertEquals(0, reloads)
    }

    @Test
    fun `attach rejection without rehydrate code stops recovery`() = runBlocking {
        // 400 agent_runtime.invalid_binding（如缺 session_id）不属于 rehydrate 语义：重试也没用，直接结束。
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        transport.streams.add(flow<AgentUiFrame> { throw GameStreamError(400, "agent_runtime.invalid_binding", "x") })
        transport.streams.add(flowOf(AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(emptyList<AgentUiFrame>(), frames)
        assertEquals(1, transport.streamCalls.size)
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
    fun `network interruptions rehydrate and reattach each time`() = runBlocking {
        // AGT-009：断线两次 → 每次重读 canonical 后重新挂接，第三次正常结束；正文不重复由 reloadCanonical 保证。
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        transport.streams.add(flow<AgentUiFrame> { throw java.io.IOException("connection reset") })
        transport.streams.add(flow<AgentUiFrame> { throw java.io.IOException("connection reset again") })
        transport.streams.add(flowOf(AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(2, reloads)
        assertEquals(3, transport.streamCalls.size)
        assertEquals(listOf(AgentUiFrame.Done), frames)
    }

    @Test
    fun `clean eof without completion marker rehydrates and reattaches`() = runBlocking {
        // AGT-009 补：读到的是一次干净 EOF（FIN）但既没有 [DONE] 也没有 finish/abort/error，
        // 说明流被切断；必须按中断重挂，不能把半截结果当成这一轮完成。
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        val partial = AgentUiFrame.Chunk(AgentUiChunk(type = "text-delta", delta = "half"))
        val finish = AgentUiFrame.Chunk(AgentUiChunk(type = "finish"))
        transport.streams.add(flowOf(partial))
        transport.streams.add(flowOf(finish, AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport, retryDelayMillis = 0) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(listOf(partial, finish, AgentUiFrame.Done), frames)
        assertEquals(1, reloads)
        assertEquals(2, transport.streamCalls.size)
    }

    @Test
    fun `done marker ends recovery without reattaching`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        transport.streams.add(flowOf(AgentUiFrame.Chunk(AgentUiChunk(type = "finish")), AgentUiFrame.Done))
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(transport, retryDelayMillis = 0) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertEquals(2, frames.size)
        assertEquals(1, transport.streamCalls.size)
        assertEquals(0, reloads)
    }

    @Test
    fun `completion markers cover done and terminal chunk types`() {
        assertTrue(AgentUiFrame.Done.completesTurn())
        assertTrue(AgentUiChunk(type = "finish").isTerminal)
        assertTrue(AgentUiChunk(type = "abort").isTerminal)
        assertTrue(AgentUiChunk(type = "error").isTerminal)
        // 内容帧不是完成标记：只有它出现就结束的流一律按截断处理。
        assertEquals(false, AgentUiChunk(type = "text-delta").isTerminal)
        assertEquals(false, AgentUiFrame.Chunk(AgentUiChunk(type = "text-delta")).completesTurn())
    }

    @Test
    fun `active probe failure consumes the same retry budget`() = runBlocking {
        // 断网后第一次探测 active 就失败：必须重试而不是直接结束恢复链。
        val transport = object : AgentStreamTransport {
            var probes = 0
            override suspend fun agentActive(sessionId: String): AgentActiveView {
                probes++
                if (probes == 1) throw java.io.IOException("no route to host")
                return AgentActiveView(active = true, taskId = "t1")
            }

            override fun agentStream(sessionId: String, taskId: String, after: String?): Flow<AgentUiFrame> =
                flowOf(AgentUiFrame.Done)

            override suspend fun agentRecover(sessionId: String, action: RecoveryAction): RecoveryReceipt =
                throw UnsupportedOperationException()
        }
        val coordinator = AgentStreamRecoveryCoordinator(transport, retryDelayMillis = 0) {}

        val frames = coordinator.recover("s1").toList()

        assertEquals(2, transport.probes)
        assertEquals(listOf(AgentUiFrame.Done), frames)
    }

    @Test
    fun `transfer retries stop after the limit`() = runBlocking {
        val transport = FakeTransport(AgentActiveView(active = true, taskId = "t1"))
        repeat(4) { transport.streams.add(flow<AgentUiFrame> { throw java.io.IOException("boom $it") }) }
        var reloads = 0
        val coordinator = AgentStreamRecoveryCoordinator(
            transport = transport,
            maxTransferRetries = 2,
            retryDelayMillis = 0,
        ) { reloads++ }

        val frames = coordinator.recover("s1").toList()

        assertTrue(frames.isEmpty())
        assertEquals(2, reloads)          // 只在预算内重挂
        assertEquals(3, transport.streamCalls.size)
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
