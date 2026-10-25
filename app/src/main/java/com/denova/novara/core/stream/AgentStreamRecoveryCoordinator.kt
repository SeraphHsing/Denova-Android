package com.denova.novara.core.stream

import com.denova.novara.core.logging.DenovaLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Agent 流恢复协调器：active → 决策 → 挂接（带 active 的 stream_cursor）→ rehydrate 重挂循环。
 * [reloadCanonical] 由上层注入（从持久化历史重建消息）；纯逻辑 + 注入 [AgentStreamTransport]。
 */
class AgentStreamRecoveryCoordinator(
    private val transport: AgentStreamTransport,
    /** 连续传输失败（断网等）的最大重挂次数；超过即放弃，避免无限重试烧流量。 */
    private val maxTransferRetries: Int = 8,
    private val retryDelayMillis: Long = 1500,
    /** 每次重挂前从 canonical 历史重建正文（保持最后一个参数，便于调用方用尾随 lambda）。 */
    private val reloadCanonical: suspend () -> Unit,
) {

    fun recover(sessionId: String): Flow<AgentUiFrame> = flow {
        var transferFailures = 0
        while (true) {
            // `agentActive` 本身也可能因断网失败：与流中断走同一条重试预算，
            // 否则「断线后第一次探测失败」会直接把整个恢复链结束（AGT-009 的关键路径）。
            val active = try {
                transport.agentActive(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                transferFailures++
                if (transferFailures > maxTransferRetries) {
                    DenovaLog.w("Agent", "active probe failed $transferFailures times; give up session=$sessionId", e)
                    return@flow
                }
                DenovaLog.w("Agent", "active probe failed (attempt $transferFailures/$maxTransferRetries)", e)
                kotlinx.coroutines.delay(retryDelayMillis)
                continue
            }
            when (val decision = decideAgentRecovery(active, null)) {
                is AgentRecoveryDecision.Attach -> {
                    DenovaLog.i(
                        "Agent",
                        "attach task=${decision.taskId} cursor=${decision.after ?: "-"} attempt=$transferFailures",
                    )
                    try {
                        transport.agentStream(sessionId, decision.taskId, decision.after).collect { frame ->
                            if (frame is AgentUiFrame.Chunk && isRehydrateChunk(frame.chunk)) {
                                throw AgentRehydrateSignal
                            }
                            emit(frame)
                        }
                        return@flow
                    } catch (_: AgentRehydrateSignal) {
                        transferFailures = 0
                        reloadCanonical()
                        // 循环：重新 active + 挂接。
                    } catch (e: GameStreamError) {
                        when (e.code) {
                            "agent_runtime.rehydrate_required",
                            "agent_stream.cursor_expired",
                            "agent_stream.invalid_cursor",
                            "agent_stream.rehydrate_required",
                            -> {
                                transferFailures = 0
                                reloadCanonical()
                            }

                            else -> {
                                // 明确记录放弃原因：500/400 的静默退出会让「断流后没恢复」无从排查。
                                DenovaLog.w("Agent", "attach rejected code=${e.code} status=${e.status}; stop recovery session=$sessionId")
                                return@flow
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 断线（AGT-009）：不走 rehydrate 语义，而是「重读 canonical 后重新挂接」。
                        // 断两次就重挂两次，正文由 canonical 历史保证不重复。
                        transferFailures++
                        if (transferFailures > maxTransferRetries) {
                            DenovaLog.w(
                                "Agent",
                                "stream retry exhausted after $transferFailures failures session=$sessionId",
                                e,
                            )
                            return@flow
                        }
                        DenovaLog.w(
                            "Agent",
                            "stream interrupted (attempt $transferFailures/$maxTransferRetries); rehydrate + reattach",
                            e,
                        )
                        reloadCanonical()
                        kotlinx.coroutines.delay(retryDelayMillis)
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
