package com.denova.novara.core.command

import kotlinx.serialization.Serializable

/**
 * 客户端命令交付状态：
 * - `SUBMITTING`：本地事务已落盘、请求已发出，尚未拿到结果。
 * - `ACCEPTED`：服务端已接受（含 `stream_attached` 的既成事实）。
 * - `UNCERTAIN`：结果不明（网络/超时/5xx），可能已执行，重试必须复用同一 command_id 并先核对 canonical。
 * - `REJECTED`：服务端明确拒绝（4xx 业务错误），未执行。
 */
enum class CommandStatus { SUBMITTING, ACCEPTED, UNCERTAIN, REJECTED }

/** 一条本地命令记录；`commandId` 是服务端幂等键，重试不得更换。 */
@Serializable
data class CommandRecord(
    val commandId: String,
    /** 目标作用域（如 `chat:session-xxx` / `game:story:branch`）。 */
    val target: String,
    /** 请求体 JSON，用于结果不明时核对与重发。 */
    val payload: String,
    val status: CommandStatus,
    val createdAt: Long,
)
