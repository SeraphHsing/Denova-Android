package com.denova.novara

import com.denova.novara.core.stream.GameActiveView
import com.denova.novara.core.stream.GameRecoveryDecision
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.decideRecovery
import com.denova.novara.core.stream.parseGameStreamError
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecoveryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `rehydrate codes map to Rehydrate`() {
        val codes = listOf(
            "agent_runtime.rehydrate_required",
            "agent_stream.cursor_expired",
            "agent_stream.invalid_cursor",
        )
        for (code in codes) {
            assertEquals(
                GameRecoveryDecision.Rehydrate(null),
                decideRecovery(null, GameStreamError(409, code, "x")),
            )
        }
    }

    @Test
    fun `unknown stream error maps to RetryActive`() {
        assertEquals(
            GameRecoveryDecision.RetryActive,
            decideRecovery(null, GameStreamError(500, null, "boom")),
        )
    }

    @Test
    fun `active task attaches`() {
        val active = GameActiveView(active = true, taskId = "t1")
        assertEquals(GameRecoveryDecision.Attach("t1"), decideRecovery(active, null))
    }

    @Test
    fun `inactive settles`() {
        assertEquals(
            GameRecoveryDecision.Settled,
            decideRecovery(GameActiveView(active = false), null),
        )
    }

    @Test
    fun `no active and no error retries`() {
        assertEquals(GameRecoveryDecision.RetryActive, decideRecovery(null, null))
    }

    @Test
    fun `rehydrate keeps task id from active`() {
        val active = GameActiveView(active = true, taskId = "t1")
        val decision = decideRecovery(active, GameStreamError(409, "agent_stream.cursor_expired", "x"))
        assertEquals(GameRecoveryDecision.Rehydrate("t1"), decision)
    }

    @Test
    fun `parses cursor expired error with bounds`() {
        val err = parseGameStreamError(
            409,
            """{"error":"expired","code":"agent_stream.cursor_expired","earliest_cursor":10,"latest_cursor":20}""",
            json,
        )
        assertEquals("agent_stream.cursor_expired", err.code)
        assertEquals(409, err.status)
        assertEquals(10L, err.earliestCursor)
        assertEquals(20L, err.latestCursor)
    }

    @Test
    fun `active view tolerates null recovery actions`() {
        val tolerant = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val view = tolerant.decodeFromString(
            GameActiveView.serializer(),
            """{"active":true,"task_id":"t1","recovery_actions":null,"recovery_paused":false,"runtime_recoverable":false}""",
        )
        assertEquals("t1", view.taskId)
        assertTrue(view.recoveryActions.isEmpty())
    }

    @Test
    fun `parses invalid body gracefully`() {
        val err = parseGameStreamError(500, "not json", json)
        assertNull(err.code)
        assertEquals("not json", err.message)
    }
}
