package com.denova.novara.core.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Agent 流恢复协调器：active → 决策 → 挂接（带 active 的 stream_cursor）→ rehydrate 重挂循环。
 * [reloadCanonical] 由上层注入（从持久化历史重建消息）；纯逻辑 + 注入 [AgentStreamTransport]。
 */
class AgentStreamRecoveryCoordinator(
    private val transport: AgentStreamTransport,
    private val reloadCanonical: suspend () -> Unit,
) {

    fun recover(sessionId: String): Flow<AgentUiFrame> = flow {
        while (true) {
            val active = transport.agentActive(sessionId)
            when (val decision = decideAgentRecovery(active, null)) {
                is AgentRecoveryDecision.Attach -> {
                    try {
                        transport.agentStream(decision.taskId, decision.after).collect { frame ->
                            if (frame is AgentUiFrame.Chunk && isRehydrateChunk(frame.chunk)) {
                                throw AgentRehydrateSignal
                            }
                            emit(frame)
                        }
                        return@flow
                    } catch (_: AgentRehydrateSignal) {
                        reloadCanonical()
                        // 循环：重新 active + 挂接。
                    } catch (e: GameStreamError) {
                        when (e.code) {
                            "agent_runtime.rehydrate_required",
                            "agent_stream.cursor_expired",
                            "agent_stream.invalid_cursor",
                            "agent_stream.rehydrate_required",
                            -> reloadCanonical()

                            else -> return@flow
                        }
                    }
                }

                AgentRecoveryDecision.Rehydrate -> reloadCanonical()

                AgentRecoveryDecision.Settled,
                AgentRecoveryDecision.RetryActive -> return@flow
            }
        }
    }

    /** `task_rehydrate_required` typed data part：需重取，不当作普通帧外泄。 */
    private fun isRehydrateChunk(chunk: AgentUiChunk): Boolean =
        chunk.type == REHYDRATE_CHUNK_TYPE

    private object AgentRehydrateSignal : Exception()

    private companion object {
        const val REHYDRATE_CHUNK_TYPE = "task_rehydrate_required"
    }
}
