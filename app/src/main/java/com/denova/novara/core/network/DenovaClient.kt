package com.denova.novara.core.network

import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.save.FileRemote
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentAskAnswer
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentMessagesPage
import com.denova.novara.core.stream.AgentRecoveryRequest
import com.denova.novara.core.stream.AgentStreamTransport
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.GameActiveView
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.GameStreamTransport
import com.denova.novara.core.stream.RecoveryAction
import com.denova.novara.core.stream.RecoveryReceipt
import com.denova.novara.core.stream.RecoveryRequest
import com.denova.novara.core.stream.StoryHistoryPage
import com.denova.novara.core.stream.parseGameStreamError
import com.denova.novara.core.terminal.TerminalCreateRequest
import com.denova.novara.core.terminal.TerminalSessionInfo
import com.denova.novara.core.terminal.TerminalSessionsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.denova.novara.core.logging.DenovaLog
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.io.IOException

@Serializable
data class AuthStatusResponse(
    val local: Boolean = false,
    val authenticated: Boolean = false,
    val lanUrl: String? = null,
)

@Serializable
data class AuthResult(
    val local: Boolean = false,
    val authenticated: Boolean = false,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class PairRequest(val token: String)

@Serializable
data class ApiErrorBody(val error: String? = null, val code: String? = null)

@Serializable
data class ProjectFileDocument(
    val path: String,
    val content: String? = null,
    val revision: String = "",
)

@Serializable
data class OptionalFileResponse(
    @SerialName("project_id") val projectId: String = "",
    val path: String,
    val found: Boolean = false,
    val document: ProjectFileDocument? = null,
)

@Serializable
data class SaveFileRequest(
    val path: String,
    val content: String,
    @SerialName("base_revision") val baseRevision: String,
)

@Serializable
data class SendInteractiveChatRequest(
    @SerialName("command_id") val commandId: String,
    val mode: String = "story",
    @SerialName("story_id") val storyId: String,
    val branch: String? = null,
    val message: String,
)

/**
 * 正文选区引用（WRI-009）：对齐 Web `TextSelection` 与后端 `chat.TextSelectionRef`，
 * 线上字段 `file_name` / `start_line` / `end_line` / `content`，行号 1 基。
 */
@Serializable
data class TextSelectionRef(
    @SerialName("file_name") val fileName: String,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    val content: String,
)

/**
 * Agent 运行时命令（`POST /api/chat/commands`，AGT-005 / AGT-006 / AGT-007）。
 *
 * `target_operation_id` 是必填项：必须来自当前 active 投影（[com.denova.novara.core.stream.AgentActiveView.activeOperationId]），
 * 服务端据此判断目标是否仍是用户看到的那个操作，避免把命令打到已经换代的运行上。
 * 队列控制（`steer_queued` / `cancel_queued`）额外必填 `target_command_id`，且只改那一项、不重投其输入。
 */
@Serializable
data class AgentCommandRequest(
    @SerialName("session_id") val sessionId: String,
    /** `abort` / `suspend` / `follow_up` / `steer_queued` / `cancel_queued`。 */
    val type: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("target_operation_id") val targetOperationId: String,
    /** 追加 / 转向的正文与引用；`abort` / `suspend` / 队列控制不携带。 */
    val input: AgentCommandInput? = null,
    val reason: String? = null,
)

/** 追加命令的输入体，字段形状对齐后端 `agentchat.ChatRequest` 的常用子集。 */
@Serializable
data class AgentCommandInput(
    val message: String,
    val references: List<String> = emptyList(),
    val selections: List<TextSelectionRef> = emptyList(),
)

/** 回答提问的请求体（`POST /api/session/asks/:id/answer`）。 */
@Serializable
data class SessionAskAnswerRequest(
    @SerialName("session_id") val sessionId: String,
    val answers: List<AgentAskAnswer> = emptyList(),
)

/** 取消提问的请求体（`POST /api/session/asks/:id/cancel`）。 */
@Serializable
data class SessionAskCancelRequest(
    @SerialName("session_id") val sessionId: String,
    val reason: String,
)

/** 命令受理回执：服务端确认后的命令身份、操作身份与游标。 */
@Serializable
data class AgentCommandReceipt(
    @SerialName("command_id") val commandId: String = "",
    @SerialName("operation_id") val operationId: String = "",
    val cursor: Long = 0,
)

@Serializable
data class SendChatRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val message: String,
    /** 引用文件路径（对齐 Web `sendMessage` 的 `references`）。 */
    val references: List<String> = emptyList(),
    /** 正文选区引用（对齐 Web `sendMessage` 的 `selections`）。 */
    val selections: List<TextSelectionRef> = emptyList(),
)

/** 写作会话摘要（`GET /api/sessions`）；`active=true` 为当前会话。 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val active: Boolean = false,
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionSummary> = emptyList())

@Serializable
data class CreateSessionRequest(val title: String = "")

@Serializable
data class SaveFileResponse(
    @SerialName("project_id") val projectId: String = "",
    val path: String,
    val revision: String = "",
    val changed: Boolean = false,
    val message: String? = null,
)

/** 服务端非 2xx 的异常。code 为服务端错误键，message 为服务端本地化错误文本。 */
class DenovaApiException(
    val status: Int,
    val code: String?,
    message: String?,
) : Exception(message ?: "HTTP $status")

/**
 * 单个服务器的认证客户端。每个 ServerKey 一个实例；Cookie 通过 [CookieJar] 隔离，
 * 切换服务器即重建客户端，从而隔离会话 Cookie。
 *
 * [cookieJar] 默认内存态；传 [com.denova.novara.core.persistence.PersistentCookieJar]
 * 可在进程被杀后复用登录态（G6 决策 C：把「重启后必须重新登录」的分钟级窗口压到秒级）。
 */
class DenovaClient(
    private val server: ServerKey,
    private val cookieJar: CookieJar = InMemoryCookieJar(),
) : FileRemote, GameStreamTransport, AgentStreamTransport {

    companion object {
        /** 退出登录时等待在途请求收尾的上限；超时后照常退出，避免无限卡住。 */
        const val LOGOUT_DRAIN_MILLIS = 3_000L
    }

    /** 连接身份（隔离 key）：HistoryStore / 草稿等按此分目录。 */
    val serverOrigin: String get() = server.origin

    private val json = Json {
        ignoreUnknownKeys = true
        // 服务端可选字段可能显式返回 null（如 running 态的 recovery_actions:null），
        // 强制收敛到模型默认值，避免非空类型解析崩溃。
        coerceInputValues = true
    }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 在途请求计数：退出登录时据此等待进行中的读取/流收尾。 */
    val inFlight = InFlightTracker()

    /** 请求世代：logout / 重新连接时退休，使迟到响应作废（AUTH-004）。 */
    val generation = RequestGeneration()

    /** 共享 OkHttpClient（含会话 CookieJar）：受保护图片、SSE、WS 都复用此实例，保证会话一致。 */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory {
            object : EventListener() {
                override fun callStart(call: Call) = inFlight.begin(call)
                override fun callEnd(call: Call) = inFlight.end(call)
                override fun callFailed(call: Call, ioe: IOException) = inFlight.end(call)
            }
        }
        .build()

    /**
     * SSE 长连接专用客户端：不设读超时（LLM 生成期间可能长时间无字节），
     * 复用同一连接池与 Cookie 会话。普通请求仍用 [okHttpClient]（30s 读超时）。
     */
    private val streamHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    }

    suspend fun status(): AuthStatusResponse {
        val request = Request.Builder().url(server.baseUrl + "/api/auth/status").get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, body)
                json.decodeFromString(AuthStatusResponse.serializer(), body)
            }
        }
    }

    suspend fun login(username: String, password: String): AuthResult {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
        return post("/api/auth/login", body)
    }

    suspend fun pair(token: String): AuthResult {
        val body = json.encodeToString(PairRequest.serializer(), PairRequest(token))
        return post("/api/auth/pair", body)
    }

    suspend fun logout() {
        // 先等进行中的读取/流收尾（最多 [LOGOUT_DRAIN_MILLIS]），再清会话；
        // 否则退出会把服务端回包丢掉，迟到响应还可能落到下一次登录上。
        val pending = inFlight.count
        if (pending > 0) {
            val drained = inFlight.awaitIdle(LOGOUT_DRAIN_MILLIS)
            DenovaLog.i(
                "Connection",
                "logout drain pending=$pending drained=$drained after=${LOGOUT_DRAIN_MILLIS}ms",
            )
        }
        // 退休世代：即使 drain 未等完（超时），残余在途响应也一律作废。
        generation.retire()
        val request = Request.Builder()
            .url(server.baseUrl + "/api/auth/logout")
            .post("".toRequestBody(null))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                // 204 No Content 是成功；其余非 2xx 抛异常。
                if (resp.code != 204 && !resp.isSuccessful) {
                    throw toException(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }

    private suspend fun post(path: String, body: String): AuthResult {
        val request = Request.Builder()
            .url(server.baseUrl + path)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AuthResult.serializer(), text)
            }
        }
    }

    override suspend fun readFile(projectId: String, path: String): ProjectFileDocument {
        val request = Request.Builder().url(fileUrl(projectId, path)).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, body)
                json.decodeFromString(ProjectFileDocument.serializer(), body)
            }
        }
    }

    /** 保存文件；revision 冲突（409）抛 [DenovaApiException]，code 为服务端错误键。 */
    override suspend fun saveFile(
        projectId: String,
        path: String,
        content: String,
        baseRevision: String,
    ): SaveFileResponse {
        val body = json.encodeToString(
            SaveFileRequest.serializer(),
            SaveFileRequest(path = path, content = content, baseRevision = baseRevision),
        )
        val request = Request.Builder()
            .url(fileUrl(projectId, path))
            .put(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(SaveFileResponse.serializer(), text)
            }
        }
    }

    /** 解析项目文件树：`recursive=true` 一次铺开目录（第一版不做懒加载分页）。 */
    suspend fun resolveProjectFiles(
        projectId: String,
        paths: List<String>,
        recursive: Boolean = true,
    ): ProjectFileTreeResolveResponse {
        val body = json.encodeToString(
            ProjectFileResolveRequest.serializer(),
            ProjectFileResolveRequest(
                targets = paths.map { ProjectFileResolveTarget(it) },
                recursive = recursive,
            ),
        )
        val request = Request.Builder()
            .url(projectFilesResolveUrl(projectId))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ProjectFileTreeResolveResponse.serializer(), text)
            }
        }
    }

    /** 书架（书籍 == 工作区）：`GET /api/books`。 */
    suspend fun bookshelf(): BookshelfResponse {
        val request = Request.Builder().url(server.baseUrl + "/api/books").get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, body)
                json.decodeFromString(BookshelfResponse.serializer(), body)
            }
        }
    }

    /**
     * 切换服务端当前工作区（书籍切换的前置）：`POST /api/workspace/switch`。
     * 对齐 Web `handleQuickWorkspaceSwitch`——客户端状态更新前必须先让服务端切到目标 workspace。
     */
    suspend fun switchWorkspace(path: String): WorkspaceSwitchResponse {
        val body = json.encodeToString(WorkspaceSwitchRequest.serializer(), WorkspaceSwitchRequest(path))
        val request = Request.Builder()
            .url(server.baseUrl + "/api/workspace/switch")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(WorkspaceSwitchResponse.serializer(), text)
            }
        }
    }

    /** 变更分组列表：`GET /api/projects/:id/changes/groups`。 */
    suspend fun changeGroups(projectId: String, status: String? = null): ChangeGroupsResponse {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/changes/groups")
            .apply { if (!status.isNullOrBlank()) addQueryParameter("status", status) }
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ChangeGroupsResponse.serializer(), text)
            }
        }
    }

    /** 变更分组详情（含每个文件的 before/after 正文与评论）：`GET .../changes/groups/:id`。 */
    suspend fun changeGroup(projectId: String, groupId: String): WorkspaceChangeGroup {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/changes/groups/$groupId")
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ChangeGroupResponse.serializer(), text).group
            }
        }
    }

    /**
     * 审阅变更分组：`POST .../changes/groups/:gid/review` body `{decision, change_set_id?}`。
     * 带 `change_setId` 时只对那一个变更集决策（局部审阅），服务端 `ReviewRequest` 原生支持。
     */
    suspend fun reviewChangeGroup(
        projectId: String,
        groupId: String,
        decision: String,
        changeSetId: String? = null,
    ): WorkspaceChangeMutationResult =
        postChangeMutation(
            projectId,
            "groups/$groupId/review",
            json.encodeToString(
                ReviewChangeRequest.serializer(),
                ReviewChangeRequest(decision, changeSetId?.takeIf { it.isNotBlank() }),
            ),
        )

    /** 新增评论：`POST .../changes/comments`（可带 diff 行锚点）。 */
    suspend fun createChangeComment(projectId: String, request: ChangeCommentRequest): WorkspaceChangeComment {
        val http = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/projects/$projectId/changes/comments")
                    .build(),
            )
            .post(json.encodeToString(ChangeCommentRequest.serializer(), request).toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ChangeCommentResponse.serializer(), text).comment
            }
        }
    }

    /** 修改评论正文：`PATCH .../changes/comments/:id`。 */
    suspend fun updateChangeComment(projectId: String, commentId: String, body: String): WorkspaceChangeComment {
        val http = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/projects/$projectId/changes/comments/$commentId")
                    .build(),
            )
            .patch(
                json.encodeToString(ChangeCommentUpdateRequest.serializer(), ChangeCommentUpdateRequest(body))
                    .toRequestBody(jsonMediaType),
            )
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ChangeCommentResponse.serializer(), text).comment
            }
        }
    }

    /** 删除评论：`DELETE .../changes/comments/:id`（服务端软删除，历史读回 `deleted=true`）。 */
    suspend fun deleteChangeComment(projectId: String, commentId: String) {
        val http = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/projects/$projectId/changes/comments/$commentId")
                    .build(),
            )
            .delete()
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
            }
        }
    }

    /** 版本状态：`GET .../versions/status`（工作区是否干净、最近版本、自动版本配置）。 */
    suspend fun versionStatus(projectId: String): VersionStatus {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/versions/status")
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(VersionStatus.serializer(), text)
            }
        }
    }

    /** 版本历史：`GET .../versions?limit=`（只读列表，diff 与恢复属 VER-001/002）。 */
    suspend fun versionHistory(projectId: String, limit: Int = 20): List<VersionEntry> {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/versions")
            .addQueryParameter("limit", limit.toString())
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(VersionHistoryResponse.serializer(), text).versions
            }
        }
    }

    /** 撤销变更分组：`POST .../changes/groups/:gid/undo`。 */
    suspend fun undoChangeGroup(projectId: String, groupId: String): WorkspaceChangeMutationResult =
        postChangeMutation(projectId, "groups/$groupId/undo")

    /** 重做变更分组：`POST .../changes/groups/:gid/redo`。 */
    suspend fun redoChangeGroup(projectId: String, groupId: String): WorkspaceChangeMutationResult =
        postChangeMutation(projectId, "groups/$groupId/redo")

    private suspend fun postChangeMutation(
        projectId: String,
        suffix: String,
        body: String = "{}",
    ): WorkspaceChangeMutationResult {
        val request = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/projects/$projectId/changes/$suffix")
                    .build(),
            )
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(WorkspaceChangeMutationResult.serializer(), text)
            }
        }
    }

    override suspend fun active(storyId: String, branchId: String): GameActiveView {
        val request = Request.Builder().url(interactiveActiveUrl(storyId, branchId)).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, body)
                json.decodeFromString(GameActiveView.serializer(), body)
            }
        }
    }

    override fun stream(
        storyId: String,
        branchId: String,
        taskId: String,
        after: String?,
    ): Flow<GameStreamEvent> = flow {
        val request = Request.Builder()
            .url(interactiveStreamUrl(storyId, branchId, taskId, after))
            .get()
            .build()
        streamHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty stream body")
            emitAll(sseEventsFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun recover(
        storyId: String,
        branchId: String,
        action: RecoveryAction,
    ): RecoveryReceipt {
        val body = json.encodeToString(RecoveryRequest.serializer(), RecoveryRequest(action, storyId, branchId))
        val request = Request.Builder()
            .url(interactiveChatUrl("recovery"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, text)
                json.decodeFromString(RecoveryReceipt.serializer(), text)
            }
        }
    }

    /**
     * 发送游戏输入：`POST /api/interactive/chat`，响应即 `StreamTask`（SSE）。
     * `command_id` 由调用方提供并先行落本地事务（服务端按 command_id 幂等）。
     */
    fun postInteractiveChat(request: SendInteractiveChatRequest): Flow<GameStreamEvent> = flow {
        val body = json.encodeToString(SendInteractiveChatRequest.serializer(), request)
        val req = Request.Builder()
            .url(interactiveSendUrl())
            .post(body.toRequestBody(jsonMediaType))
            .build()
        streamHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty interactive chat stream body")
            emitAll(sseEventsFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 写作 Agent UI message 流：`GET /api/chat/stream?session_id=&task_id=&after=`。
     * 服务端先要 `session_id` 再按 `task_id` 精确匹配活跃任务，缺任一个都是 400。
     * 响应为 AI SDK UI message stream（`x-vercel-ai-ui-message-stream: v1`），
     * 不依赖 Last-Event-ID，重连游标通过 `after=` 显式传递。
     */
    /** 写作会话列表：`GET /api/sessions`（`active=true` 为当前会话）。 */
    suspend fun sessionList(): List<SessionSummary> {
        val request = Request.Builder().url(server.baseUrl + "/api/sessions").get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(SessionsResponse.serializer(), text).sessions
            }
        }
    }

    /** 创建写作会话：`POST /api/sessions`。 */
    suspend fun createSession(title: String = ""): SessionSummary {
        val body = json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(title))
        val request = Request.Builder()
            .url(server.baseUrl + "/api/sessions")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(SessionSummary.serializer(), text)
            }
        }
    }

    /** 发送写作输入：`POST /api/chat`，响应为 AI SDK UI message 流。 */
    fun postChat(request: SendChatRequest): Flow<AgentUiFrame> = flow {
        val body = json.encodeToString(SendChatRequest.serializer(), request)
        val req = Request.Builder()
            .url(chatSendUrl())
            .post(body.toRequestBody(jsonMediaType))
            .build()
        streamHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty chat stream body")
            emitAll(agentFramesFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun agentActive(sessionId: String): AgentActiveView {
        val request = Request.Builder().url(agentActiveUrl(sessionId)).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, body)
                json.decodeFromString(AgentActiveView.serializer(), body)
            }
        }
    }

    /**
     * 回答提问（AGT-004）：`POST /api/session/asks/:ask_id/answer`。
     * 答案体的 `question_id` / `selected_option_ids` 必须来自服务端下发的问句，不能自造。
     */
    suspend fun answerSessionAsk(
        sessionId: String,
        askId: String,
        answers: List<AgentAskAnswer>,
    ): AgentAskResolution = postAskResolution(
        path = "asks/$askId/answer",
        body = json.encodeToString(
            SessionAskAnswerRequest.serializer(),
            SessionAskAnswerRequest(sessionId = sessionId, answers = answers),
        ),
    )

    /** 取消提问（AGT-004）：`POST /api/session/asks/:ask_id/cancel`，原因默认 `user_cancelled`。 */
    suspend fun cancelSessionAsk(
        sessionId: String,
        askId: String,
        reason: String = AgentAskLogic.CANCEL_REASON_USER,
    ): AgentAskResolution = postAskResolution(
        path = "asks/$askId/cancel",
        body = json.encodeToString(
            SessionAskCancelRequest.serializer(),
            SessionAskCancelRequest(sessionId = sessionId, reason = reason),
        ),
    )

    private suspend fun postAskResolution(path: String, body: String): AgentAskResolution {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/session/")
            .addPathSegments(path)
            .build()
        val call = Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(call).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentAskResolution.serializer(), text)
            }
        }
    }

    /**
     * 提交运行时命令（暂停 / 停止，AGT-005）：`POST /api/chat/commands`。
     * 失败按 `agent_runtime.*` 错误码抛 [DenovaApiException]，由上层区分「无活动运行」与「目标已变化」。
     */
    suspend fun submitAgentCommand(request: AgentCommandRequest): AgentCommandReceipt {
        val body = json.encodeToString(AgentCommandRequest.serializer(), request)
        val call = Request.Builder()
            .url(agentChatUrl("commands"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(call).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentCommandReceipt.serializer(), text)
            }
        }
    }

    override suspend fun agentRecover(sessionId: String, action: RecoveryAction): RecoveryReceipt {
        val body = json.encodeToString(
            AgentRecoveryRequest.serializer(),
            AgentRecoveryRequest(action, sessionId),
        )
        val request = Request.Builder()
            .url(agentChatUrl("recovery"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, text)
                json.decodeFromString(RecoveryReceipt.serializer(), text)
            }
        }
    }

    override fun agentStream(sessionId: String, taskId: String, after: String?): Flow<AgentUiFrame> = flow {
        val request = Request.Builder()
            .url(writingAgentStreamUrl(server.baseUrl, sessionId, taskId, after))
            .get()
            .build()
        streamHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty agent stream body")
            emitAll(agentFramesFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    /** 读取写作 session 的 canonical 消息历史（rehydrate/挂接后重建基线）。 */
    suspend fun sessionMessages(sessionId: String, limit: Int): AgentMessagesPage {
        val request = Request.Builder().url(sessionMessagesUrl(sessionId, limit)).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, body)
                json.decodeFromString(AgentMessagesPage.serializer(), body)
            }
        }
    }

    /**
     * 项目资源原始字节：`GET /api/projects/:id/files/asset?path=`（消息里的图片部件走这里）。
     * 走同一个已鉴权的 OkHttpClient，因此不依赖图片库的 cookie 配置。
     */
    suspend fun projectAssetBytes(projectId: String, path: String): ByteArray {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/files/asset")
            .addQueryParameter("path", path)
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    throw toException(resp.code, bytes.toString(Charsets.UTF_8))
                }
                bytes
            }
        }
    }

    /** 绝对 http(s) 资源字节（消息里的远程图片部件）：沿用同一鉴权客户端，避免图片库各自配 cookie。 */
    suspend fun fetchBytes(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    throw toException(resp.code, bytes.toString(Charsets.UTF_8))
                }
                bytes
            }
        }
    }

    /** 读取 story 的 canonical 历史（rehydrate 后重建叙事基线）。 */
    suspend fun interactiveHistory(storyId: String, branchId: String, limit: Int): StoryHistoryPage {
        val request = Request.Builder()
            .url(interactiveHistoryUrl(storyId, branchId, limit))
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, body)
                json.decodeFromString(StoryHistoryPage.serializer(), body)
            }
        }
    }

    // ---- 终端（G5）----

    /** 创建 PTY 会话；`owner_tab_id` 决定归属，重进时据此找回同一 PTY。 */
    suspend fun createTerminalSession(
        projectId: String,
        ownerTabId: String,
        cols: Int,
        rows: Int,
        title: String = "",
    ): TerminalSessionInfo {
        // 后端 ResolveStartupCommand 只认 "shell" 或已配置的 profile id；空值会 400。
        val payload = TerminalCreateRequest(
            ownerTabId = ownerTabId,
            profileId = "shell",
            title = title,
            cols = cols,
            rows = rows,
        )
        val body = json.encodeToString(TerminalCreateRequest.serializer(), payload)
        val request = Request.Builder()
            .url(projectTerminalSessionsUrl(projectId))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                // 用 DenovaApiException 而不是 GameStreamError：终端要按 code（ownerConflict /
                // tokenInvalid / notFound）决定是否重试，SSE 的错误类型没有这层语义
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(TerminalSessionInfo.serializer(), text)
            }
        }
    }

    /** 存活会话列表（含 attach token）：用于切页 / 重启后重挂同一 PTY。 */
    suspend fun terminalSessions(): TerminalSessionsPage {
        val request = Request.Builder().url(terminalSessionsUrl()).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(TerminalSessionsPage.serializer(), text)
            }
        }
    }

    /** 关闭并释放 PTY。 */
    suspend fun deleteTerminalSession(sessionId: String) {
        val request = Request.Builder().url(terminalSessionUrl(sessionId)).delete().build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw toException(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }

    /** 打开 attach WebSocket（binary=PTY 字节，text=控制帧）。 */
    fun openTerminal(sessionId: String, token: String): TerminalConnection {
        val connection = TerminalConnection(json)
        val request = Request.Builder().url(terminalAttachUrl(sessionId, token)).build()
        okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    connection.bind(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    connection.onTextMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    connection.onBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    connection.onClosed(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connection.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    connection.onFailure(t.message ?: "terminal socket failure")
                }
            },
        )
        return connection
    }

    private fun projectTerminalSessionsUrl(projectId: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/terminal/sessions")
            .build()

    private fun terminalSessionsUrl(): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/terminal/sessions").build()

    private fun terminalSessionUrl(sessionId: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/terminal/sessions/$sessionId").build()

    /**
     * attach URL。
     *
     * **必须保持 http/https**：OkHttp 的 `HttpUrl.Builder.scheme` 只接受 http/https，
     * 传 `ws` / `wss` 会直接抛 `IllegalArgumentException: unexpected scheme: ws`（真机崩溃过）。
     * `newWebSocket()` 拿到 http(s) URL 后会自动按 ws(s) 升级，无需手工改 scheme。
     */
    internal fun terminalAttachUrl(sessionId: String, token: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/terminal/sessions/$sessionId/attach")
            .addQueryParameter("token", token)
            .build()

    private fun interactiveChatUrl(path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/interactive/chat/$path").build()

    private fun interactiveActiveUrl(storyId: String, branchId: String): HttpUrl =
        interactiveChatUrl("active").newBuilder()
            .addQueryParameter("story_id", storyId)
            .addQueryParameter("branch", branchId)
            .build()

    private fun interactiveStreamUrl(
        storyId: String,
        branchId: String,
        taskId: String,
        after: String?,
    ): HttpUrl =
        interactiveChatUrl("stream").newBuilder()
            .addQueryParameter("story_id", storyId)
            .addQueryParameter("branch", branchId)
            .addQueryParameter("task_id", taskId)
            .apply { if (after != null) addQueryParameter("after", after) }
            .build()

    private fun interactiveSendUrl(): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/interactive/chat").build()

    private fun sessionMessagesUrl(sessionId: String, limit: Int): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/session/messages")
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("limit", limit.toString())
            .build()

    private fun chatSendUrl(): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat").build()

    private fun agentChatUrl(path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/$path").build()

    private fun agentActiveUrl(sessionId: String): HttpUrl =
        agentChatUrl("active").newBuilder().addQueryParameter("session_id", sessionId).build()

    private fun interactiveHistoryUrl(storyId: String, branchId: String, limit: Int): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/interactive/stories/$storyId/history")
            .addQueryParameter("branch", branchId)
            .addQueryParameter("limit", limit.toString())
            .build()

    private fun toGameStreamError(status: Int, body: String): GameStreamError =
        parseGameStreamError(status, body, json)

    private fun fileUrl(projectId: String, path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/files/file")
            .addQueryParameter("path", path)
            .build()

    private fun projectFilesResolveUrl(projectId: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/files/resolve")
            .build()

    /**
     * 受保护资源 URL：`GET /api/projects/:id/files/asset?path=`，返回原始字节与 Content-Type。
     * 需要用 [okHttpClient] 发起（会话 Cookie），供图片加载器直接使用。
     */
    fun projectAssetUrl(projectId: String, path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/files/asset")
            .addQueryParameter("path", path)
            .build()

    private fun toException(status: Int, body: String): DenovaApiException {
        val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body) }.getOrNull()
        return DenovaApiException(status, parsed?.code, parsed?.error ?: body.ifBlank { null })
    }
}

/** 按 host 隔离的内存 CookieJar：每个 DenovaClient 独立，切换服务器即隔离会话。 */
private class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val existing = store.getOrPut(url.host) { mutableListOf() }
        for (cookie in cookies) {
            existing.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            existing.add(cookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host].orEmpty().filter { it.matches(url) }
    }
}
