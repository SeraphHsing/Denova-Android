package com.denova.novara

import com.denova.novara.core.stream.GameActiveView
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GameRecoveryDecision
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.GameStreamRecoveryCoordinator
import com.denova.novara.core.stream.GameStreamTransport
import com.denova.novara.core.stream.RecoveryAction
import com.denova.novara.core.stream.RecoveryReceipt
import com.denova.novara.core.stream.RehydrateRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStreamRecoveryCoordinatorTest {

    @Test
    fun `attaches and finishes on done`() = runBlocking {
        val transport = FakeTransport(GameActiveView(active = true, taskId = "t1"))
        val chunk = GameStreamEvent("42", GameEventType.CHUNK, """{"content":"hi"}""")
        val done = GameStreamEvent("43", GameEventType.DONE, "{}")
        transport.streams.add(flowOf(chunk, done))
        var reloads = 0
        val coordinator = GameStreamRecoveryCoordinator(transport, reloadCanonical = { reloads++ })

        val events = coordinator.recover("s1", "b1").toList()

        assertEquals(listOf(chunk, done), events)
        assertEquals("43", coordinator.cursor)
        assertEquals(1, transport.streamCalls.size)
        assertEquals("t1", transport.streamCalls[0].taskId)
        assertNull(transport.streamCalls[0].after)
        assertEquals(0, reloads)
    }

    @Test
    fun `settles without stream when inactive`() = runBlocking {
        val transport = FakeTransport(GameActiveView(active = false))
        val coordinator = GameStreamRecoveryCoordinator(transport, reloadCanonical = {})

        val events = coordinator.recover("s1", "b1").toList()

        assertTrue(events.isEmpty())
        assertEquals(0, transport.streamCalls.size)
    }

    @Test
    fun `rehydrate required reloads and reattaches with cursor`() = runBlocking {
        val transport = FakeTransport(GameActiveView(active = true, taskId = "t1"))
        val rehydrate = GameStreamEvent(
            null,
            GameEventType.TASK_REHYDRATE_REQUIRED,
            """{"task_id":"t1","cursor":41,"settled":false}""",
        )
        val done = GameStreamEvent("42", GameEventType.DONE, "{}")
        transport.streams.add(flowOf(rehydrate))
        transport.streams.add(flowOf(done))
        val reloads = mutableListOf<RehydrateRequest?>()
        val coordinator = GameStreamRecoveryCoordinator(transport, reloadCanonical = { reloads.add(it) })

        val events = coordinator.recover("s1", "b1").toList()

        assertEquals(listOf(done), events)
        assertEquals(1, reloads.size)
        assertEquals("t1", reloads[0]?.taskId)
        assertEquals(41L, reloads[0]?.cursor)
        assertEquals(2, transport.streamCalls.size)
        assertEquals("41", transport.streamCalls[1].after)
    }

    @Test
    fun `stream rehydrate error reloads and reattaches from scratch`() = runBlocking {
        val transport = FakeTransport(GameActiveView(active = true, taskId = "t1"))
        transport.streams.add(flow<GameStreamEvent> { throw GameStreamError(409, "agent_runtime.rehydrate_required", "x") })
        transport.streams.add(flowOf(GameStreamEvent("1", GameEventType.DONE, "{}")))
        val reloads = mutableListOf<RehydrateRequest?>()
        val coordinator = GameStreamRecoveryCoordinator(transport, reloadCanonical = { reloads.add(it) })

        val events = coordinator.recover("s1", "b1").toList()

        assertEquals(1, events.size)
        assertEquals(1, reloads.size)
        assertNull(reloads[0])
        assertEquals(2, transport.streamCalls.size)
        assertNull(transport.streamCalls[1].after)
    }

    @Test
    fun `stream failure ends recovery without emitting`() = runBlocking {
        val transport = FakeTransport(GameActiveView(active = true, taskId = "t1"))
        transport.streams.add(flow<GameStreamEvent> { throw GameStreamError(500, null, "boom") })
        val coordinator = GameStreamRecoveryCoordinator(transport, reloadCanonical = {})

        val events = coordinator.recover("s1", "b1").toList()

        assertTrue(events.isEmpty())
        assertEquals(1, transport.streamCalls.size)
    }

    private class FakeTransport(
        var activeResult: GameActiveView,
    ) : GameStreamTransport {
        val activeCalls = mutableListOf<Pair<String, String>>()
        val streamCalls = mutableListOf<StreamCall>()
        val streams = mutableListOf<Flow<GameStreamEvent>>()
        private var streamIndex = 0

        data class StreamCall(val storyId: String, val branchId: String, val taskId: String, val after: String?)

        override suspend fun active(storyId: String, branchId: String): GameActiveView {
            activeCalls.add(storyId to branchId)
            return activeResult
        }

        override fun stream(storyId: String, branchId: String, taskId: String, after: String?): Flow<GameStreamEvent> {
            streamCalls.add(StreamCall(storyId, branchId, taskId, after))
            return if (streamIndex < streams.size) streams[streamIndex++] else flowOf()
        }

        override suspend fun recover(storyId: String, branchId: String, action: RecoveryAction): RecoveryReceipt {
            throw UnsupportedOperationException()
        }
    }
}
