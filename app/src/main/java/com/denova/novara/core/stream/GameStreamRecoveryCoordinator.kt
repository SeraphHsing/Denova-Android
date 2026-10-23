package com.denova.novara.core.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/** 一次流挂接的结束结果。 */
sealed interface GameAttachOutcome {
    /** 流正常结束（done/aborted/error 由事件本身携带）。 */
    data object Finished : GameAttachOutcome

    /** 需整体重取；request 为 `task_rehydrate_required` 携带的重连参数（409 错误时可能为 null）。 */
    data class RehydrateRequired(val request: RehydrateRequest?) : GameAttachOutcome

    /** 传输层失败。 */
    data class Failed(val error: Throwable) : GameAttachOutcome
}

/**
 * 游戏流恢复协调器：active → 决策 → 挂接（带游标）→ rehydrate 重挂循环。
 * [reloadCanonical] 由上层注入（加载 canonical 历史），完成后协调器清游标并用
 * rehydrate 携带的 cursor（若存在）重挂同一 task。纯逻辑 + 注入 [GameStreamTransport]，
 * 可用 fake 做 JVM 单测。
 */
class GameStreamRecoveryCoordinator(
    private val transport: GameStreamTransport,
    private val reloadCanonical: suspend (RehydrateRequest?) -> Unit,
    private val state: GameStreamState = GameStreamState(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    val cursor: String? get() = state.cursor
    val rehydrationRequired: Boolean get() = state.rehydrationRequired

    /** 完整恢复流程，输出流事件直到 settle / 失败。 */
    fun recover(storyId: String, branchId: String): Flow<GameStreamEvent> = flow {
        var pendingAfter: String? = null
        while (true) {
            val active = transport.active(storyId, branchId)
            when (val decision = decideRecovery(active, null)) {
                is GameRecoveryDecision.Attach -> {
                    val after = pendingAfter ?: state.cursor
                    pendingAfter = null
                    when (val outcome = attach(storyId, branchId, decision.taskId, after) { emit(it) }) {
                        GameAttachOutcome.Finished -> return@flow
                        is GameAttachOutcome.Failed -> return@flow
                        is GameAttachOutcome.RehydrateRequired -> {
                            reloadCanonical(outcome.request)
                            state.reset()
                            pendingAfter = outcome.request?.cursor?.toString()
                            // 循环：重新 active + 挂接（用 rehydrate 游标，若缺失则从头）。
                        }
                    }
                }

                is GameRecoveryDecision.Rehydrate -> {
                    reloadCanonical(null)
                    state.reset()
                    pendingAfter = null
                }

                GameRecoveryDecision.Settled,
                GameRecoveryDecision.RetryActive -> return@flow
            }
        }
    }

    private suspend fun attach(
        storyId: String,
        branchId: String,
        taskId: String,
        after: String?,
        onEvent: suspend (GameStreamEvent) -> Unit,
    ): GameAttachOutcome {
        return try {
            transport.stream(storyId, branchId, taskId, after).collect { event ->
                state.onEvent(event.id, event.type, event.data)
                if (event.type == GameEventType.TASK_REHYDRATE_REQUIRED) {
                    throw RehydrateSignal(parseRehydrateRequest(event.data, json))
                }
                onEvent(event)
            }
            GameAttachOutcome.Finished
        } catch (signal: RehydrateSignal) {
            GameAttachOutcome.RehydrateRequired(signal.request)
        } catch (e: GameStreamError) {
            when (e.code) {
                "agent_runtime.rehydrate_required",
                "agent_stream.cursor_expired",
                "agent_stream.invalid_cursor",
                -> GameAttachOutcome.RehydrateRequired(null)

                else -> GameAttachOutcome.Failed(e)
            }
        } catch (e: Exception) {
            GameAttachOutcome.Failed(e)
        }
    }

    private class RehydrateSignal(val request: RehydrateRequest?) : Exception()
}
