package com.denova.novara.core.network

import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentAskAnswer
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentContextAnalysis
import com.denova.novara.core.stream.AgentMessagesPage
import com.denova.novara.core.stream.CONTEXT_ANALYSIS_PROBE
import com.denova.novara.core.stream.AgentStreamTransport
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.RecoveryAction
import com.denova.novara.core.stream.RecoveryReceipt
import kotlinx.coroutines.flow.Flow

/**
 * 一个 Agent 会话通道的完整能力（P4-B 抽取）。
 *
 * 固定基线有**两套**形状相同但作用域不同的 Agent 接口：
 * - 写作（前台 Writing）：`/api/sessions`、`/api/chat`、`/api/session/…`；
 * - 工作台（项目作用域）：`/api/projects/:id/agent-chat/…`，永不切换前台书籍。
 *
 * 两者的消息部件、提问、命令、恢复与上下文分析契约一致，因此会话界面只依赖本接口。
 * 实现：[DenovaClient]（写作作用域）与 [ProjectAgentGateway]（项目作用域）。
 */
interface AgentGateway : AgentStreamTransport {

    suspend fun sessionList(): List<SessionSummary>

    suspend fun createSession(title: String = ""): SessionSummary

    fun postChat(request: SendChatRequest): Flow<AgentUiFrame>

    suspend fun submitAgentCommand(request: AgentCommandRequest): AgentCommandReceipt

    suspend fun sessionMessages(sessionId: String, limit: Int, before: String? = null): AgentMessagesPage

    suspend fun answerSessionAsk(sessionId: String, askId: String, answers: List<AgentAskAnswer>): AgentAskResolution

    suspend fun cancelSessionAsk(
        sessionId: String,
        askId: String,
        reason: String = AgentAskLogic.CANCEL_REASON_USER,
    ): AgentAskResolution

    suspend fun contextAnalysis(
        sessionId: String,
        message: String = CONTEXT_ANALYSIS_PROBE,
    ): AgentContextAnalysis

    /** 项目资源字节（消息里的工作区图片部件）。 */
    suspend fun projectAssetBytes(projectId: String, path: String): ByteArray

    /** 绝对 http(s) 资源字节（消息里的远程图片部件）。 */
    suspend fun fetchBytes(url: String): ByteArray
}

/**
 * 工作台的项目作用域 Agent 通道。
 *
 * 每个方法都把 [projectId] 显式带入请求路径——项目身份不能来自「当前选中的项目」这类可变全局状态，
 * 否则切换项目时在途回包会写到别的项目上（与写作链路的 `SessionScope` 同一纪律）。
 */
class ProjectAgentGateway(
    private val client: DenovaClient,
    val projectId: String,
) : AgentGateway {

    override suspend fun sessionList(): List<SessionSummary> =
        client.agentChatProjects(projectId)
            .firstOrNull { it.id == projectId }
            ?.sessions
            .orEmpty()
            .map { it.toSummary() }

    override suspend fun createSession(title: String): SessionSummary =
        client.projectAgentCreateSession(projectId, title).toSummary()

    override fun postChat(request: SendChatRequest): Flow<AgentUiFrame> =
        client.projectAgentPostChat(projectId, request)

    override suspend fun submitAgentCommand(request: AgentCommandRequest): AgentCommandReceipt =
        client.projectAgentSubmitCommand(projectId, request)

    override suspend fun sessionMessages(sessionId: String, limit: Int, before: String?): AgentMessagesPage =
        client.projectAgentMessages(projectId, sessionId, limit, before)

    override suspend fun answerSessionAsk(
        sessionId: String,
        askId: String,
        answers: List<AgentAskAnswer>,
    ): AgentAskResolution = client.projectAgentAnswerAsk(projectId, sessionId, askId, answers)

    override suspend fun cancelSessionAsk(
        sessionId: String,
        askId: String,
        reason: String,
    ): AgentAskResolution = client.projectAgentCancelAsk(projectId, sessionId, askId, reason)

    override suspend fun contextAnalysis(sessionId: String, message: String): AgentContextAnalysis =
        client.projectAgentContextAnalysis(projectId, sessionId, message)

    override suspend fun projectAssetBytes(projectId: String, path: String): ByteArray =
        client.projectAssetBytes(projectId, path)

    override suspend fun fetchBytes(url: String): ByteArray = client.fetchBytes(url)

    override suspend fun agentActive(sessionId: String): AgentActiveView =
        client.projectAgentActive(projectId, sessionId)

    override fun agentStream(sessionId: String, taskId: String, after: String?): Flow<AgentUiFrame> =
        client.projectAgentStream(projectId, sessionId, taskId, after)

    override suspend fun agentRecover(sessionId: String, action: RecoveryAction): RecoveryReceipt =
        client.projectAgentRecover(projectId, sessionId, action)
}

/** 工作台会话摘要 → 会话界面使用的通用摘要（字段一一对应，仅 `active` 语义不同）。 */
fun AgentChatSession.toSummary(): SessionSummary = SessionSummary(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    // AgentChat 没有单例 active 会话：把「当前正在运行的会话」当作可恢复目标。
    active = running,
    messageCount = messageCount,
)
