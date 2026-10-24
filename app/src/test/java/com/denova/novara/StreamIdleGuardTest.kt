package com.denova.novara

import com.denova.novara.core.stream.collectWithIdleGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamIdleGuardTest {

    @Test
    fun `terminates when idle and task finished`() = runBlocking {
        val events = mutableListOf<Int>()
        val source = flow {
            emit(1)
            delay(5_000) // 挂起，模拟服务端不再发事件但流未关闭
            emit(2)
        }

        val terminated = source.collectWithIdleGuard(
            isTaskFinished = { true },
            onEvent = { events.add(it) },
            idleMillis = 150,
            checkIntervalMillis = 30,
        )

        assertTrue(terminated)
        assertEquals(listOf(1), events)
    }

    @Test
    fun `keeps waiting while task still active`() = runBlocking {
        val events = mutableListOf<Int>()
        val source = flow {
            emit(1)
            delay(300)
            emit(2)
        }

        val terminated = source.collectWithIdleGuard(
            isTaskFinished = { false },
            onEvent = { events.add(it) },
            idleMillis = 80,
            checkIntervalMillis = 20,
        )

        assertFalse(terminated)
        assertEquals(listOf(1, 2), events)
    }
}
