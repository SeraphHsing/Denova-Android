package com.denova.novara.core.stream

/**
 * 游戏 SSE 事件类型（对齐 game-event-contract.csv，35 类）。
 * [handled] 表示 Web 端处理并投影到 UI；[checkpoint] 表示属于 checkpoint 序列（游标恢复）。
 */
enum class GameEventType(val handled: Boolean, val checkpoint: Boolean = false) {
    TASK_CHECKPOINT(true, true),
    TASK_CHECKPOINT_COMMITTED(true, true),
    TASK_REHYDRATE_REQUIRED(true, true),
    AGENT_CYCLE_STARTED(true),
    CHUNK(true),
    SUBAGENT_SETTLED(true),
    THINKING(true),
    INTERACTIVE_CONTENT_RECLASSIFIED(true),
    MODEL_RETRY(true),
    SUSPENDED(true),
    TOOL_CALL(true),
    TOOL_ARGS_DELTA(true),
    TOOL_STARTED(true),
    TOOL_PROGRESS(true),
    TOOL_RESULT(true),
    CONTEXT_COMPACTION(true),
    TODO_UPDATED(true),
    TOKEN_USAGE(true),
    INTERACTIVE_TURN_PERSISTED(true),
    RUNTIME_RECOVERY_REQUIRED(true),
    ERROR(true),
    DONE(true),
    ABORTED(true),
    ASK_PENDING(true),
    ASK_RESOLVED(true),

    GOAL_EVALUATION_FAILED(false),
    CONTEXT_CLEANUP(false),
    CONTEXT_NORMALIZER(false),
    POST_RUN_VERIFICATION(false),
    RUN_STATE(false),
    SUBAGENT_ARTIFACT(false),
    SUBAGENT_TRANSCRIPT_SYNCHRONIZED(false),
    TOOL_TARGET(false),
    VERIFICATION(false),
    WORKSPACE_CHANGE(false),

    UNKNOWN(false),
    ;

    /** SSE 线上的 event 名（如 "agent_cycle_started"）。 */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWireName(name: String): GameEventType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}
