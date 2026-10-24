package com.denova.novara.core.stream

/** 写作 Agent 的运行态。 */
enum class AgentPhase { IDLE, RUNNING, DONE, ERROR }

/** 写作 Agent 的 UI 投影：正文累积 + 思考 + 当前活动 + 终态。 */
data class AgentUiState(
    val content: String = "",
    val thinking: String = "",
    val activity: String = "",
    val phase: AgentPhase = AgentPhase.IDLE,
    val errorText: String? = null,
)

/**
 * 把 AI SDK UI message 帧投影到 [AgentUiState]。
 * `text-delta`/`reasoning-delta` 累积，`tool-input-start` 记活动，`finish`/`error` 定终态。
 */
object AgentUiReducer {

    fun reduce(state: AgentUiState, chunk: AgentUiChunk): AgentUiState = when (chunk.type) {
        "start" -> state.copy(phase = AgentPhase.RUNNING)

        "text-delta" -> state.copy(
            content = state.content + chunk.delta.orEmpty(),
            phase = AgentPhase.RUNNING,
        )

        "reasoning-delta" -> state.copy(thinking = state.thinking + chunk.delta.orEmpty())

        "tool-input-start" -> state.copy(activity = "tool:${chunk.toolName.orEmpty()}")

        "tool-output-available", "tool-output-error" -> state.copy(activity = "")

        "error" -> state.copy(phase = AgentPhase.ERROR, errorText = chunk.errorText)

        "abort" -> state.copy(phase = AgentPhase.DONE, activity = "")

        "finish" -> state.copy(
            phase = if (chunk.finishReason == "error") AgentPhase.ERROR else AgentPhase.DONE,
            activity = "",
        )

        else -> state
    }
}
