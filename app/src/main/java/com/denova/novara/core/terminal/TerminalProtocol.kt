package com.denova.novara.core.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 终端会话（后端 `terminal.Info` 的投影；建会话时额外带 `token` 用于 attach）。 */
@Serializable
data class TerminalSessionInfo(
    val id: String = "",
    @SerialName("owner_tab_id") val ownerTabId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    val title: String = "",
    val command: String = "",
    val cwd: String = "",
    val cols: Int = 0,
    val rows: Int = 0,
    /** 会话所属项目；复用存活会话时必须匹配，否则后端会返回 owner 冲突。 */
    @SerialName("project_id") val projectId: String = "",
    val attached: Int = 0,
    val exited: Boolean = false,
    @SerialName("exit_code") val exitCode: Int = 0,
    @SerialName("exit_error") val exitError: String? = null,
    val token: String = "",
)

/**
 * 启动命令候选（`commands[]` 元素）。
 *
 * 实测 85ec97c：该字段是**对象数组** `{"id":"codex","name":"Codex CLI"}`，
 * 不是字符串数组——按字符串解会直接 `JsonDecodingException` 打断整个连接流程。
 */
@Serializable
data class TerminalCommandOption(
    val id: String = "",
    val name: String = "",
)

/** `GET /api/terminal/sessions` 的响应。 */
@Serializable
data class TerminalSessionsPage(
    val enabled: Boolean = false,
    val shell: String = "",
    val commands: List<TerminalCommandOption> = emptyList(),
    @SerialName("default_cwd") val defaultCwd: String = "",
    @SerialName("max_sessions") val maxSessions: Int = 0,
    @SerialName("scrollback_kb") val scrollbackKb: Int = 0,
    val sessions: List<TerminalSessionInfo> = emptyList(),
)

/** `POST /api/projects/:id/terminal/sessions` 的请求体。 */
@Serializable
data class TerminalCreateRequest(
    @SerialName("owner_tab_id") val ownerTabId: String,
    @SerialName("profile_id") val profileId: String = "",
    val title: String = "",
    val cols: Int,
    val rows: Int,
)

/** 客户端 → 服务端的 JSON 控制帧（`type` = input / resize / ping）。 */
@Serializable
data class TerminalControl(
    val type: String,
    val data: String? = null,
    val cols: Int = 0,
    val rows: Int = 0,
)

/** 服务端 → 客户端的帧：binary 是原始 PTY 输出，text 是下列控制消息。 */
sealed interface TerminalFrame {
    data class Ready(val session: TerminalSessionInfo?) : TerminalFrame
    data class Output(val bytes: ByteArray) : TerminalFrame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Output && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class Exit(val code: Int, val error: String?) : TerminalFrame
    data class Error(val message: String) : TerminalFrame
    data object Pong : TerminalFrame

    /** socket 已关闭（服务端主动关闭或被网络中断）；code 为 WS close code。 */
    data class Closed(val code: Int, val reason: String) : TerminalFrame
}
