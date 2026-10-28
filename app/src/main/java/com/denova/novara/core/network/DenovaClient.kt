package com.denova.novara.core.network

import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.save.AutosaveConflictCodec
import com.denova.novara.core.save.AutosaveConflictRecord
import com.denova.novara.core.save.ConflictArchive
import com.denova.novara.core.save.ConflictStorage
import com.denova.novara.core.save.FileRemote
import com.denova.novara.core.save.PreservedConflict
import com.denova.novara.core.save.PreservedConflictPayload
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentAskAnswer
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentContextAnalysis
import com.denova.novara.core.stream.AgentContextAnalysisRequest
import com.denova.novara.core.stream.CONTEXT_ANALYSIS_PROBE
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
import com.denova.novara.core.stream.TurnEvent
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
import kotlinx.serialization.builtins.ListSerializer
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
    val message: String = "",
    /** 生成开场（GAM-003）：turns=0 时用它代替 message，服务端会跑首轮开场。 */
    @SerialName("start_opening") val startOpening: Boolean? = null,
    /** 从该轮重生成（GAM-010）：服务端回退到它再重新跑这一轮。 */
    @SerialName("regenerate_from_turn_id") val regenerateFromTurnId: String? = null,
    /** 续接挂起的输入（GAM-012）：来自 active 投影的 `pending_interruption_id`。 */
    @SerialName("resume_interruption_id") val resumeInterruptionId: String? = null,
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
    /** 队列控制的目标项（`active.queue[].command_id`）；其余命令必须缺省。 */
    @SerialName("target_command_id") val targetCommandId: String? = null,
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
) : FileRemote, GameStreamTransport, AgentStreamTransport, ConflictArchive, AgentGateway {

    companion object {
        /** 退出登录时等待在途请求收尾的上限；超时后照常退出，避免无限卡住。 */
        const val LOGOUT_DRAIN_MILLIS = 3_000L
    }

    /** 连接身份（隔离 key）：HistoryStore / 草稿等按此分目录。 */
    val serverOrigin: String get() = server.origin

    /** 任一会话请求收到 401 时回调（由连接层设置），用于回到登录页。 */
    var onUnauthorized: (() -> Unit)? = null

    private val unauthorizedNotified = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 登录请求期间为 true：401 在那里只代表密码错误，不触发「会话过期」。 */
    private val suppressUnauthorized = java.util.concurrent.atomic.AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys = true
        // 服务端可选字段可能显式返回 null（如 running 态的 recovery_actions:null），
        // 强制收敛到模型默认值，避免非空类型解析崩溃。
        coerceInputValues = true
    }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 资料库写请求专用：服务端 `bindCompleteLoreItemInput` 要求 9 个字段**全部出现**在 JSON 里
     * （缺失即 400 `loreFieldsRequired`），所以这里必须 `encodeDefaults = true`；
     * 同时用 `explicitNulls = false` 让「未传 index_memberships」保持为「不改成员关系」。
     */
    private val loreJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * 创建类请求专用：`encodeDefaults=true` 让表单默认值也显式上送（与 Web 行为一致，
     * 不依赖服务端归一化），`explicitNulls=false` 让未选择的可选对象整段省略。
     */
    private val createJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

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

    /**
     * 慢接口（跑模型/生图/生语音）专用：分类预览要把 100+ 条条目交给模型，
     * 30s 读超时会在正常等待中被判超时（真机表现为 SocketTimeoutException → 界面只显示「分类失败」）。
     */
    private val slowHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
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

    /**
     * 归档冲突材料（WRI-013）：`POST /api/autosave-conflicts`，返回记录 id 与服务端落盘路径。
     * 请求体按后端 `autosave.Input` 字段名构造，`value` 为 JSON 字符串（对应 Go `json.RawMessage`）。
     */
    override suspend fun preserve(record: AutosaveConflictRecord): PreservedConflict {
        val body = AutosaveConflictCodec.encode(record)
        val request = Request.Builder()
            .url(server.baseUrl + "/api/autosave-conflicts")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                val saved = json.decodeFromString(PreservedConflictPayload.serializer(), text)
                PreservedConflict(saved.id, saved.path, ConflictStorage.SERVER)
            }
        }
    }

    /**
     * 解析项目文件树：`recursive=true` 时服务端在条目预算内广度优先铺开，
     * 预算用尽的分支返回 `children_state=partial` + `continuation`，由 [resolveProjectDirectoryPage] 续页。
     */
    suspend fun resolveProjectFiles(
        projectId: String,
        paths: List<String>,
        recursive: Boolean = true,
        entryBudget: Int? = null,
    ): ProjectFileTreeResolveResponse = postResolve(
        projectId = projectId,
        targets = paths.map { ProjectFileResolveTarget(it) },
        recursive = recursive,
        entryBudget = entryBudget,
    )

    /** 目录续页（WRI-002）：「加载更多」用上一页给出的 `continuation` 取下一页，游标仅在 revision 未变时有效。 */
    suspend fun resolveProjectDirectoryPage(
        projectId: String,
        path: String,
        cursor: String,
        entryBudget: Int? = null,
    ): ProjectFileTreeResolveResponse = postResolve(
        projectId = projectId,
        targets = listOf(ProjectFileResolveTarget(path, cursor)),
        recursive = true,
        entryBudget = entryBudget,
    )

    private suspend fun postResolve(
        projectId: String,
        targets: List<ProjectFileResolveTarget>,
        recursive: Boolean,
        entryBudget: Int?,
    ): ProjectFileTreeResolveResponse {
        val body = json.encodeToString(
            ProjectFileResolveRequest.serializer(),
            ProjectFileResolveRequest(targets = targets, recursive = recursive, entryBudget = entryBudget),
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
     * 建书（WRI-001）：`POST /api/books/create`。
     *
     * 后端只接受 `title` / `author` / `description`，目录已存在时返回 409；
     * **该调用会切换服务端当前 workspace**，因此返回的 `project_id` 才是后续一切的键。
     */
    suspend fun createBook(title: String, author: String = "", description: String = ""): CreateBookResponse {
        val body = json.encodeToString(
            CreateBookRequest.serializer(),
            CreateBookRequest(title = title.trim(), author = author.trim(), description = description.trim()),
        )
        val request = Request.Builder()
            .url(server.baseUrl + "/api/books/create")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(CreateBookResponse.serializer(), text)
            }
        }
    }

    /**
     * 章节目录与状态（WRI-015）：`GET /api/projects/:id/book/summary`。
     * `status` 由后端按字数与确认位算出（`空章`/`初稿`/`成章`），客户端只读。
     */
    suspend fun bookSummary(projectId: String): BookSummaryResponse {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/book/summary")
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BookSummaryResponse.serializer(), text)
            }
        }
    }

    /**
     * 改章节状态（WRI-015）：`PATCH /api/projects/:id/book/chapter-status` body `{path, confirmed}`。
     *
     * 只写确认位：`path` 必须是 `chapters/` 下已存在的 md/txt，否则后端 400；
     * `status` 由后端重算，客户端不提交。
     */
    suspend fun setChapterConfirmed(projectId: String, path: String, confirmed: Boolean): ChapterStatusResponse {
        val body = json.encodeToString(ChapterStatusRequest.serializer(), ChapterStatusRequest(path, confirmed))
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/book/chapter-status")
            .build()
        val request = Request.Builder()
            .url(url)
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(ChapterStatusResponse.serializer(), text)
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

    /**
     * 创建版本（VER-001）：`POST .../versions`，body 只有 `message`。
     *
     * 工作区没有变更时服务端返回「当前工作区没有可保存的变更」，原样冒泡给界面提示。
     */
    suspend fun versionCreate(projectId: String, message: String): VersionCommandResult {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/versions")
            .build()
        val body = json.encodeToString(
            VersionCreateRequest.serializer(),
            VersionCreateRequest(message = message.trim()),
        )
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(
                Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)).build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(VersionCommandResult.serializer(), text)
            }
        }
    }

    /** 版本 diff（VER-002）：`GET .../versions/:id/diff?comparison=&path=`。 */
    suspend fun versionDiff(
        projectId: String,
        versionId: String,
        comparison: String = "workspace",
        path: String = "",
    ): VersionDiff {
        val builder = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/versions/$versionId/diff")
            .addQueryParameter("comparison", comparison)
        if (path.isNotBlank()) builder.addQueryParameter("path", path)
        val url = builder.build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(VersionDiff.serializer(), text)
            }
        }
    }

    /** 恢复计划（VER-002）：`POST .../versions/:id/restore-plan`，`paths` 为空即整本书范围。 */
    suspend fun versionRestorePlan(
        projectId: String,
        versionId: String,
        paths: List<String>,
    ): VersionRestorePlan = postVersionRestore(
        projectId = projectId,
        versionId = versionId,
        action = "restore-plan",
        paths = paths,
        decode = { json.decodeFromString(VersionRestorePlan.serializer(), it) },
    )

    /** 执行恢复（VER-002）：只回传用户确认过的路径，服务端据此实际恢复。 */
    suspend fun versionRestore(
        projectId: String,
        versionId: String,
        paths: List<String>,
    ): VersionRestoreResult = postVersionRestore(
        projectId = projectId,
        versionId = versionId,
        action = "restore",
        paths = paths,
        decode = { json.decodeFromString(VersionRestoreResult.serializer(), it) },
    )

    private suspend fun <T> postVersionRestore(
        projectId: String,
        versionId: String,
        action: String,
        paths: List<String>,
        decode: (String) -> T,
    ): T {
        val url = server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/versions/$versionId/$action")
            .build()
        val body = json.encodeToString(
            VersionRestoreRequest.serializer(),
            VersionRestoreRequest(paths = paths),
        )
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(
                Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)).build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                decode(text)
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
    override suspend fun sessionList(): List<SessionSummary> {
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
    override suspend fun createSession(title: String): SessionSummary {
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
    override fun postChat(request: SendChatRequest): Flow<AgentUiFrame> = flow {
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
    override suspend fun answerSessionAsk(
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
    override suspend fun cancelSessionAsk(
        sessionId: String,
        askId: String,
        reason: String,
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
    override suspend fun submitAgentCommand(request: AgentCommandRequest): AgentCommandReceipt {
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

    /**
     * 上下文分析（AGT-012）。
     *
     * 服务端要求 `message` 非空（分析的是「下一轮会带上什么」），因此用与 Web 相同的探针文本，
     * 不会写进会话历史、也不触发运行。
     */
    override suspend fun contextAnalysis(sessionId: String, message: String): AgentContextAnalysis {
        val body = json.encodeToString(
            AgentContextAnalysisRequest.serializer(),
            AgentContextAnalysisRequest(sessionId = sessionId, message = message),
        )
        val call = Request.Builder()
            .url(agentChatUrl("context-analysis"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(call).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentContextAnalysis.serializer(), text)
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

    /**
     * 读取写作 session 的 canonical 消息历史（rehydrate/挂接后重建基线）。
     * [before] 是分页游标：带上一页的 `page.next_before` 即向历史更早处翻页（AGT-010）。
     */
    override suspend fun sessionMessages(sessionId: String, limit: Int, before: String?): AgentMessagesPage {
        val request = Request.Builder().url(sessionMessagesUrl(sessionId, limit, before)).get().build()
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
    override suspend fun projectAssetBytes(projectId: String, path: String): ByteArray {
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
    override suspend fun fetchBytes(url: String): ByteArray {
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

    /**
     * 读取 story 的 canonical 历史（rehydrate 后重建叙事基线）。
     * [before] 传上一页的 `before_cursor` 即可继续向前翻（GAM-007）。
     */
    suspend fun interactiveHistory(
        storyId: String,
        branchId: String,
        limit: Int,
        before: String? = null,
    ): StoryHistoryPage {
        val request = Request.Builder()
            .url(interactiveHistoryUrl(storyId, branchId, limit, before))
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

    /**
     * 故事快照（GAM-003 / GAM-006）：`GET /api/interactive/stories/:id/snapshot?branch=`。
     * 返回尾部轮次窗口、规范状态、当前轮（行动选项来源）与分支图；更早历史仍走 [interactiveHistory]。
     */
    suspend fun interactiveSnapshot(storyId: String, branchId: String): InteractiveSnapshot {
        val request = Request.Builder()
            .url(interactiveSnapshotUrl(storyId, branchId))
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toGameStreamError(resp.code, body)
                json.decodeFromString(InteractiveSnapshot.serializer(), body)
            }
        }
    }

    /**
     * 编辑某轮叙事（GAM-015）：`PATCH .../turns/:turnId/narrative` body `{branch_id, narrative, expected_narrative}`。
     * 返回该轮的新内容（服务端会追加一次编辑事件并保持 turn 身份不变）。
     */
    suspend fun updateTurnNarrative(
        storyId: String,
        turnId: String,
        branchId: String,
        narrative: String,
        expectedNarrative: String,
    ): TurnEvent {
        val body = json.encodeToString(
            UpdateTurnNarrativeRequest.serializer(),
            UpdateTurnNarrativeRequest(branchId, narrative, expectedNarrative),
        )
        val request = Request.Builder()
            .url(interactiveStoryUrl(storyId, "turns/$turnId/narrative"))
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(UpdateTurnNarrativeResult.serializer(), text).turn
            }
        }
    }

    // ---- 提问与运行时命令（P4-A / GAM-011、GAM-012、GAM-013）----

    /** 回答游戏提问：`POST /api/interactive/chat/asks/:askId/answer`。 */
    suspend fun answerInteractiveAsk(
        storyId: String,
        branchId: String,
        askId: String,
        answers: List<AgentAskAnswer>,
    ): AgentAskResolution {
        val body = json.encodeToString(
            GameAskAnswerRequest.serializer(),
            GameAskAnswerRequest(storyId, branchId, answers),
        )
        return postInteractiveAsk(askId, "answer", body)
    }

    /** 取消游戏提问：`POST /api/interactive/chat/asks/:askId/cancel`。 */
    suspend fun cancelInteractiveAsk(
        storyId: String,
        branchId: String,
        askId: String,
        reason: String = AgentAskLogic.CANCEL_REASON_USER,
    ): AgentAskResolution {
        val body = json.encodeToString(
            GameAskCancelRequest.serializer(),
            GameAskCancelRequest(storyId, branchId, reason),
        )
        return postInteractiveAsk(askId, "cancel", body)
    }

    private suspend fun postInteractiveAsk(askId: String, action: String, body: String): AgentAskResolution {
        val request = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/interactive/chat/asks/$askId/$action")
                    .build(),
            )
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentAskResolution.serializer(), text)
            }
        }
    }

    /** 运行时命令：`POST /api/interactive/chat/commands`（`suspend` / `abort` / `abandon_pending`）。 */
    suspend fun submitGameCommand(command: GameCommandRequest) {
        val body = json.encodeToString(GameCommandRequest.serializer(), command)
        val request = Request.Builder()
            .url(
                server.baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("api/interactive/chat/commands")
                    .build(),
            )
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    // ---- 分支与分支方案（P4-A / GAM-008、GAM-009）----

    /** 分支列表：`GET /api/interactive/stories/:id/branches`。 */
    suspend fun interactiveBranches(storyId: String): BranchesResponse {
        val request = Request.Builder().url(interactiveBranchUrl(storyId)).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BranchesResponse.serializer(), text)
            }
        }
    }

    /** 从指定轮创建分支：`POST .../branches` body `{parent_event_id, title}`。 */
    suspend fun createInteractiveBranch(
        storyId: String,
        parentEventId: String,
        title: String = "",
    ): BranchSummary {
        val body = json.encodeToString(
            CreateBranchRequest.serializer(),
            CreateBranchRequest(parentEventId, title),
        )
        val request = Request.Builder()
            .url(interactiveBranchUrl(storyId))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BranchSummary.serializer(), text)
            }
        }
    }

    /** 切换分支：`POST .../switch-branch` body `{branch_id}`。 */
    suspend fun switchInteractiveBranch(storyId: String, branchId: String) {
        val body = json.encodeToString(SwitchBranchRequest.serializer(), SwitchBranchRequest(branchId))
        val request = Request.Builder()
            .url(interactiveStoryUrl(storyId, "switch-branch"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 切换轮次版本（GAM-010）：`POST .../switch-turn-version` body `{branch_id, turn_id, version_turn_id}`。
     * 只换展示的版本，不新生成；成功后需重取快照与历史（状态会随之回退到该版本）。
     */
    suspend fun switchInteractiveTurnVersion(
        storyId: String,
        branchId: String,
        turnId: String,
        versionTurnId: String,
    ) {
        val body = json.encodeToString(
            SwitchTurnVersionRequest.serializer(),
            SwitchTurnVersionRequest(branchId, turnId, versionTurnId),
        )
        val request = Request.Builder()
            .url(interactiveStoryUrl(storyId, "switch-turn-version"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /** 删除分支：`DELETE .../branches/:branchId`。 */
    suspend fun deleteInteractiveBranch(storyId: String, branchId: String) {
        val request = Request.Builder().url(interactiveBranchUrl(storyId, branchId)).delete().build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 保存分支方案：`PUT .../branches/:branchId/plan` body `{markdown, base_revision}`。
     * revision 不匹配返回 409（`DenovaApiException` 携带服务端文案），调用方据此提示重新加载。
     */
    suspend fun updateBranchPlan(
        storyId: String,
        branchId: String,
        markdown: String,
        baseRevision: String,
    ): UpdateBranchPlanResult {
        val body = json.encodeToString(
            UpdateBranchPlanRequest.serializer(),
            UpdateBranchPlanRequest(markdown, baseRevision),
        )
        val request = Request.Builder()
            .url(interactiveBranchUrl(storyId, branchId, "plan"))
            .put(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(UpdateBranchPlanResult.serializer(), text)
            }
        }
    }

    // ---- 故事库（P4-A / GAM-001、GAM-002）----

    /** 故事列表：`GET /api/interactive/stories` → `{current_story_id, stories[]}`。 */
    suspend fun interactiveStories(): StoryIndex {
        val request = Request.Builder().url(interactiveStoriesUrl()).get().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(StoryIndex.serializer(), text)
            }
        }
    }

    // ---- 新建故事资源目录（P4-A / GAM-001）----

    /** 叙事风格（teller）：`GET /api/interactive/tellers` → `{tellers[], default_id}`。 */
    suspend fun interactiveTellers(): TellersResponse =
        getJson(interactiveBaseUrl("tellers")) { json.decodeFromString(TellersResponse.serializer(), it) }

    /** 策划模板：`GET /api/game-planning-templates` → `{game_planning_templates[]}`。 */
    suspend fun gamePlanningTemplates(): PlanningTemplatesResponse =
        getJson(apiUrl("game-planning-templates")) { json.decodeFromString(PlanningTemplatesResponse.serializer(), it) }

    /** 事件包：`GET /api/event-packages` → `{event_packages[]}`。 */
    suspend fun eventPackages(): EventPackagesResponse =
        getJson(apiUrl("event-packages")) { json.decodeFromString(EventPackagesResponse.serializer(), it) }

    /** 规则系统：`GET /api/rule-systems` → `{rule_systems[]}`。 */
    suspend fun ruleSystems(): RuleSystemsResponse =
        getJson(apiUrl("rule-systems")) { json.decodeFromString(RuleSystemsResponse.serializer(), it) }

    /** Actor 状态系统：`GET /api/actor-states` → `{actor_states[]}`。 */
    suspend fun actorStates(): ActorStatesResponse =
        getJson(apiUrl("actor-states")) { json.decodeFromString(ActorStatesResponse.serializer(), it) }

    /** 图像方案：`GET /api/image-presets` → `{presets[]}`（顶层键是 presets）。 */
    suspend fun imagePresets(): ImagePresetsResponse =
        getJson(apiUrl("image-presets")) { json.decodeFromString(ImagePresetsResponse.serializer(), it) }

    /** 资料库条目（主角候选）：`GET /api/projects/:id/book/lore/items`。 */
    suspend fun projectLoreItems(projectId: String): LoreItemsResponse =
        getJson(projectLoreItemsUrl(projectId)) { json.decodeFromString(LoreItemsResponse.serializer(), it) }

    // ---- 资料库（P4-C / LOR-001…004）----

    suspend fun loreCategories(projectId: String): List<LoreCategory> =
        getJson(loreUrl(projectId, "categories")) {
            json.decodeFromString(ListSerializer(LoreCategory.serializer()), it)
        }

    /** 分类增删改移：`POST .../lore/categories`，返回改完的完整分类表。 */
    suspend fun mutateLoreCategory(projectId: String, mutation: LoreCategoryMutation): List<LoreCategory> =
        writeJson(
            url = loreUrl(projectId, "categories"),
            payload = json.encodeToString(LoreCategoryMutation.serializer(), mutation),
        ) { json.decodeFromString(ListSerializer(LoreCategory.serializer()), it) }

    suspend fun createLoreItem(projectId: String, input: LoreItemInput): LoreItemSummary =
        writeJson(
            url = loreUrl(projectId, "items"),
            payload = loreJson.encodeToString(LoreItemInput.serializer(), input),
        ) { json.decodeFromString(LoreItemResponse.serializer(), it).item }

    suspend fun updateLoreItem(projectId: String, id: String, input: LoreItemInput): LoreItemSummary =
        writeJson(
            url = loreUrl(projectId, "items/$id"),
            payload = loreJson.encodeToString(LoreItemInput.serializer(), input),
            method = "PUT",
        ) { json.decodeFromString(LoreItemResponse.serializer(), it).item }

    suspend fun deleteLoreItem(projectId: String, id: String) {
        writeJson<Unit>(url = loreUrl(projectId, "items/$id"), method = "DELETE") { }
    }

    /** 材料上传（multipart `file`）：`POST .../items/:id/materials/upload`。 */
    suspend fun uploadLoreMaterial(
        projectId: String,
        itemId: String,
        file: UploadFile,
    ): LoreItemSummary {
        val body = multipartBody(emptyMap(), fileField = "file", file = file)
        val request = Request.Builder().url(loreUrl(projectId, "items/$itemId/materials/upload")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(LoreItemSummary.serializer(), text)
            }
        }
    }

    /**
     * 材料关联 / 改名 / 移除 / 设为封面 / 远程图片：`POST .../items/:id/materials`。
     *
     * `op=remote` 时服务端要下载远端图片，用慢客户端（默认 30s 读超时会误判超时）。
     */
    suspend fun mutateLoreMaterial(
        projectId: String,
        itemId: String,
        mutation: LoreMaterialMutation,
        slow: Boolean = false,
    ): LoreItemSummary =
        writeJson(
            url = loreUrl(projectId, "items/$itemId/materials"),
            payload = json.encodeToString(LoreMaterialMutation.serializer(), mutation),
            slow = slow,
        ) { json.decodeFromString(LoreItemSummary.serializer(), it) }

    suspend fun loreAssets(projectId: String): List<LoreMaterial> =
        getJson(loreUrl(projectId, "assets")) {
            json.decodeFromString(ListSerializer(LoreMaterial.serializer()), it)
        }

    /** 生成条目配图：`POST .../items/:id/image/generate`（失败时服务端给 `api.lore.image*` 原因）。 */
    suspend fun generateLoreImage(
        projectId: String,
        itemId: String,
        request: LoreImageGenerateRequest = LoreImageGenerateRequest(),
    ): LoreItemSummary =
        writeJson(
            url = loreUrl(projectId, "items/$itemId/image/generate"),
            payload = json.encodeToString(LoreImageGenerateRequest.serializer(), request),
            slow = true,
        ) { json.decodeFromString(LoreItemSummary.serializer(), it) }

    /** 生成条目语音：`POST .../items/:id/speech/generate`，返回更新后的条目（含新 MP3 材料）。 */
    suspend fun generateLoreSpeech(projectId: String, itemId: String, request: LoreSpeechRequest): LoreItemSummary =
        writeJson(
            url = loreUrl(projectId, "items/$itemId/speech/generate"),
            payload = json.encodeToString(LoreSpeechRequest.serializer(), request),
            slow = true,
        ) { json.decodeFromString(LoreItemSummary.serializer(), it) }

    suspend fun loreIndex(projectId: String): LoreIndexSnapshot =
        getJson(loreUrl(projectId, "index")) { json.decodeFromString(LoreIndexSnapshot.serializer(), it) }

    suspend fun updateLoreIndex(projectId: String, update: LoreIndexUpdate): LoreIndexSnapshot =
        writeJson(
            url = loreUrl(projectId, "index"),
            payload = json.encodeToString(LoreIndexUpdate.serializer(), update),
            method = "PUT",
        ) { json.decodeFromString(LoreIndexSnapshot.serializer(), it) }

    suspend fun previewLoreIndex(projectId: String, guide: LoreIndexGuide): LoreIndexPreview =
        writeJson(
            url = loreUrl(projectId, "index/preview"),
            payload = json.encodeToString(LoreIndexGuide.serializer(), guide),
            slow = true,
        ) { json.decodeFromString(LoreIndexPreview.serializer(), it) }

    suspend fun previewLoreClassification(
        projectId: String,
        request: LoreClassificationRequest,
    ): LoreClassificationPreview =
        writeJson(
            url = loreUrl(projectId, "classification/preview"),
            payload = json.encodeToString(LoreClassificationRequest.serializer(), request),
            slow = true,
        ) { json.decodeFromString(LoreClassificationPreview.serializer(), it) }

    suspend fun applyLoreClassification(
        projectId: String,
        request: LoreClassificationApplyRequest,
    ): LoreClassificationApplyResult =
        writeJson(
            url = loreUrl(projectId, "classification/apply"),
            payload = json.encodeToString(LoreClassificationApplyRequest.serializer(), request),
            slow = true,
        ) { json.decodeFromString(LoreClassificationApplyResult.serializer(), it) }

    /**
     * 创建故事：`POST /api/interactive/stories` → `StorySummary`。
     *
     * 服务端在创建时已把 `index.current_story_id` 指到新故事（`Store.CreateStory`），
     * 因此创建成功后**不需要**再调用 `:id/select`，与 Web `handleCreateStory` 一致。
     * 请求用 `encodeDefaults=true` 显式上送默认值，避免依赖服务端归一化。
     */
    suspend fun createInteractiveStory(request: CreateStoryRequest): StorySummary {
        val body = createJson.encodeToString(CreateStoryRequest.serializer(), request)
        val http = Request.Builder()
            .url(interactiveBaseUrl("stories"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(StorySummary.serializer(), text)
            }
        }
    }

    /**
     * 选中故事：`POST /api/interactive/stories/:id/select`。
     * 服务端把选择写进 index 的 `current_story_id`（全 workspace 共享）。
     * `preview` 故事与不存在的 id 都返回 400，因此调用方必须先成功再更新本地选择。
     */
    suspend fun selectInteractiveStory(storyId: String) {
        val request = Request.Builder()
            .url(interactiveStoryUrl(storyId, "select"))
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 改名：`PATCH /api/interactive/stories/:id` body `{title}` → 更新后的 `StorySummary`。
     * 该端点没有 `base_revision` / `If-Match` 语义，服务端只追加 `story_config_updated` 事件。
     */
    suspend fun renameInteractiveStory(storyId: String, title: String): StorySummary {
        val body = json.encodeToString(UpdateStoryTitleRequest.serializer(), UpdateStoryTitleRequest(title))
        val request = Request.Builder()
            .url(interactiveStoryUrl(storyId))
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(StorySummary.serializer(), text)
            }
        }
    }

    /** 删除故事：`DELETE /api/interactive/stories/:id`。 */
    suspend fun deleteInteractiveStory(storyId: String) {
        val request = Request.Builder().url(interactiveStoryUrl(storyId)).delete().build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    // ---- 书籍管理（P4-C / BOK-001·002·003）----

    /** 服务端当前工作区：`GET /api/workspace/current`。 */
    suspend fun currentWorkspace(): WorkspaceCurrentResponse =
        getJson(apiUrl("workspace/current")) { json.decodeFromString(WorkspaceCurrentResponse.serializer(), it) }

    /** 书籍元信息：`GET /api/books/info?path=`。 */
    suspend fun bookInfo(path: String): BookMeta {
        val url = apiUrl("books/info").newBuilder().addQueryParameter("path", path).build()
        return getJson(url) { json.decodeFromString(BookMeta.serializer(), it) }
    }

    /** 更新书籍元信息：`PUT /api/books/info`（title 为空表示不改标题）。 */
    suspend fun updateBookInfo(path: String, title: String, author: String, description: String): BookMeta {
        val body = json.encodeToString(
            BookInfoRequest.serializer(),
            BookInfoRequest(path = path, title = title, author = author, description = description),
        )
        val request = Request.Builder()
            .url(apiUrl("books/info"))
            .put(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BookMeta.serializer(), text)
            }
        }
    }

    /** 从书架移除（磁盘目录保留）：`POST /api/books/remove`。 */
    suspend fun removeBook(path: String): BookRemoveResponse {
        val body = json.encodeToString(BookRemoveRequest.serializer(), BookRemoveRequest(path))
        val request = Request.Builder()
            .url(apiUrl("books/remove"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BookRemoveResponse.serializer(), text)
            }
        }
    }

    /** 保存书籍顺序：`POST /api/books/reorder`（提交完整路径表）。 */
    suspend fun reorderBooks(paths: List<String>) {
        val body = json.encodeToString(BookReorderRequest.serializer(), BookReorderRequest(paths))
        val request = Request.Builder()
            .url(apiUrl("books/reorder"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /** 切换排序模式：`POST /api/books/sort-mode`（`manual` / `recent`）。 */
    suspend fun setBookSortMode(mode: String) {
        val body = json.encodeToString(BookSortModeRequest.serializer(), BookSortModeRequest(mode))
        val request = Request.Builder()
            .url(apiUrl("books/sort-mode"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /** 导出书籍：`GET /api/books/export?path=&format=`，返回文件名与字节（不落盘由界面决定）。 */
    suspend fun exportBook(path: String, format: String = "txt"): BookExportFile {
        val url = apiUrl("books/export").newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("format", format)
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    throw toException(resp.code, bytes.toString(Charsets.UTF_8))
                }
                BookExportFile(
                    fileName = resp.header("Content-Disposition")?.let(::fileNameFromDisposition).orEmpty()
                        .ifBlank { "book.$format" },
                    bytes = bytes,
                )
            }
        }
    }

    /** 上传封面：`POST /api/books/cover/upload`（multipart `path` + `file`）。 */
    suspend fun uploadBookCover(path: String, file: UploadFile): BookCoverResult {
        val body = multipartBody(
            fields = mapOf("path" to path),
            fileField = "file",
            file = file,
        )
        val request = Request.Builder().url(apiUrl("books/cover/upload")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BookCoverResult.serializer(), text)
            }
        }
    }

    /** 生成封面：`POST /api/books/cover/generate`（需要可用的图像 Agent / 预设）。 */
    suspend fun generateBookCover(payload: BookCoverGenerateRequest): BookCoverResult {
        val body = json.encodeToString(BookCoverGenerateRequest.serializer(), payload)
        val request = Request.Builder()
            .url(apiUrl("books/cover/generate"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(BookCoverResult.serializer(), text)
            }
        }
    }

    /** 预览小说章节切分：`POST /api/books/import-novel/preview`（**不写入** workspace）。 */
    suspend fun previewNovelImport(
        file: UploadFile,
        sampleChars: Int = 0,
        splitRegex: String = "",
        splitStrategy: String = "",
    ): NovelImportPreview {
        val body = multipartBody(
            fields = buildMap {
                if (sampleChars > 0) put("sample_chars", sampleChars.toString())
                if (splitRegex.isNotBlank()) put("split_regex", splitRegex)
                if (splitStrategy.isNotBlank()) put("split_strategy", splitStrategy)
            },
            fileField = "file",
            file = file,
        )
        val request = Request.Builder().url(apiUrl("books/import-novel/preview")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(NovelImportPreview.serializer(), text)
            }
        }
    }

    /** 提交小说导入：`POST /api/books/import-novel`（服务端会新建书籍并切换工作区）。 */
    suspend fun importNovel(
        file: UploadFile,
        bookTitle: String = "",
        author: String = "",
        description: String = "",
        sampleChars: Int = 0,
        splitRegex: String = "",
        splitStrategy: String = "",
    ): NovelImportResult {
        val body = multipartBody(
            fields = buildMap {
                if (bookTitle.isNotBlank()) put("book_title", bookTitle)
                if (author.isNotBlank()) put("author", author)
                if (description.isNotBlank()) put("description", description)
                if (sampleChars > 0) put("sample_chars", sampleChars.toString())
                if (splitRegex.isNotBlank()) put("split_regex", splitRegex)
                if (splitStrategy.isNotBlank()) put("split_strategy", splitStrategy)
            },
            fileField = "file",
            file = file,
        )
        val request = Request.Builder().url(apiUrl("books/import-novel")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(NovelImportResult.serializer(), text)
            }
        }
    }

    /** 预览酒馆角色卡：`POST /api/imports/character-card/preview`（只解析，不写入）。 */
    suspend fun previewCharacterCard(file: UploadFile): CharacterCardPreview {
        val body = multipartBody(emptyMap(), fileField = "file", file = file)
        val request = Request.Builder().url(apiUrl("imports/character-card/preview")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(CharacterCardPreview.serializer(), text)
            }
        }
    }

    /** 以角色卡新建书籍：`POST /api/books/import-character-card`（target 固定为 new_book）。 */
    suspend fun importCharacterCardAsNewBook(file: UploadFile, bookTitle: String = ""): CharacterCardImportResult {
        val body = multipartBody(
            fields = buildMap { if (bookTitle.isNotBlank()) put("book_title", bookTitle) },
            fileField = "file",
            file = file,
        )
        val request = Request.Builder().url(apiUrl("books/import-character-card")).post(body).build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(CharacterCardImportResult.serializer(), text)
            }
        }
    }

    /** 封面地址（带版本参数，避免缓存旧图）；由界面交给认证图片加载器。 */
    fun bookCoverUrl(path: String, version: String): HttpUrl =
        apiUrl("books/cover").newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("v", version)
            .build()

    private fun multipartBody(
        fields: Map<String, String>,
        fileField: String,
        file: UploadFile,
    ): okhttp3.MultipartBody {
        val builder = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
        fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }
        val mime = file.mimeType.ifBlank { "application/octet-stream" }.toMediaType()
        builder.addFormDataPart(fileField, file.fileName, file.bytes.toRequestBody(mime))
        return builder.build()
    }

    /** `Content-Disposition: attachment; filename="x.txt"; filename*=UTF-8''%E4%B8%AD.txt` */
    private fun fileNameFromDisposition(header: String): String {
        val encoded = Regex("filename\\*=UTF-8''([^;]+)").find(header)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        val plain = Regex("filename=\"([^\"]+)\"").find(header)?.groupValues?.getOrNull(1)
        return plain.orEmpty()
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

    // ---- 工作台：项目与会话（P4-B / WB-001 / WB-002 / WB-006 / WB-010）----

    /**
     * 工作台项目树：`GET /api/agent-chat/projects[?project_id=]`。
     * 只读，不会切换全局写作工作区；`project_id` 为空时返回全部已登记项目。
     */
    suspend fun agentChatProjects(projectId: String = ""): List<AgentChatProject> {
        val url = apiUrl("agent-chat/projects").newBuilder()
            .apply { projectId.takeIf { it.isNotBlank() }?.let { addQueryParameter("project_id", it) } }
            .build()
        return getJson(url) { text ->
            json.decodeFromString(AgentChatProjectsResponse.serializer(), text).projects.map {
                it.copy(sessions = it.sessions)
            }
        }
    }

    /** 运行中的会话心跳：`GET /api/agent-chat/activity`（只扫执行身份，不读会话正文）。 */
    suspend fun agentChatActivity(): List<AgentChatActivityBinding> =
        getJson(apiUrl("agent-chat/activity")) { text ->
            json.decodeFromString(AgentChatActivityResponse.serializer(), text).bindings
        }

    /** 跨项目历史搜索：`GET /api/agent-chat/history?query=&project_id=&offset=&limit=`。 */
    suspend fun agentChatHistory(
        query: String = "",
        projectId: String = "",
        offset: Int = 0,
        limit: Int = 0,
    ): AgentChatHistoryPage {
        val url = apiUrl("agent-chat/history").newBuilder()
            .apply {
                query.takeIf { it.isNotBlank() }?.let { addQueryParameter("query", it) }
                projectId.takeIf { it.isNotBlank() }?.let { addQueryParameter("project_id", it) }
                if (offset > 0) addQueryParameter("offset", offset.toString())
                if (limit > 0) addQueryParameter("limit", limit.toString())
            }
            .build()
        return getJson(url) { text -> json.decodeFromString(AgentChatHistoryPage.serializer(), text) }
    }

    /** 导出会话 Markdown：`GET /api/projects/:id/agent-chat/session/export?session_id=`。 */
    suspend fun agentChatExport(projectId: String, sessionId: String): String {
        val url = projectAgentChatUrl(projectId, "session/export").newBuilder()
            .addQueryParameter("session_id", sessionId)
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                text
            }
        }
    }

    /**
     * 登记已存在的宿主目录：`POST /api/agent-chat/projects`（body `{path}`，必须是绝对路径）。
     * 路径必须来自宿主目录选择，客户端不得把手机目录当作宿主目录（WB-010）。
     */
    suspend fun agentChatAddProject(path: String): AgentChatProject {
        val body = json.encodeToString(AddAgentChatProjectRequest.serializer(), AddAgentChatProjectRequest(path))
        val request = Request.Builder()
            .url(apiUrl("agent-chat/projects"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentChatProject.serializer(), text)
            }
        }
    }

    /** 在宿主上新建 General 项目目录：`POST /api/agent-chat/projects/directory`（body `{name,parent_directory?}`）。 */
    suspend fun createAgentChatDirectory(name: String, parentDirectory: String? = null): AgentChatProject {
        val body = json.encodeToString(
            CreateAgentChatDirectoryRequest.serializer(),
            CreateAgentChatDirectoryRequest(name = name, parentDirectory = parentDirectory?.takeIf { it.isNotBlank() }),
        )
        val request = Request.Builder()
            .url(apiUrl("agent-chat/projects/directory"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentChatProject.serializer(), text)
            }
        }
    }

    /** 重命名项目：`PATCH /api/agent-chat/projects/:id` body `{name}`（与 path 互斥）。 */
    suspend fun agentChatRenameProject(projectId: String, name: String): AgentChatProject =
        patchAgentChatProject(projectId, UpdateAgentChatProjectRequest(name = name))

    /** 重新关联目录：`PATCH /api/agent-chat/projects/:id` body `{path}`（绝对路径）。 */
    suspend fun agentChatRelinkProject(projectId: String, path: String): AgentChatProject =
        patchAgentChatProject(projectId, UpdateAgentChatProjectRequest(path = path))

    private suspend fun patchAgentChatProject(
        projectId: String,
        payload: UpdateAgentChatProjectRequest,
    ): AgentChatProject {
        val body = json.encodeToString(UpdateAgentChatProjectRequest.serializer(), payload)
        val request = Request.Builder()
            .url(apiUrl("agent-chat/projects/$projectId"))
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentChatProject.serializer(), text)
            }
        }
    }

    /** 归档（从工作台移除）项目：`DELETE /api/agent-chat/projects/:id`。 */
    suspend fun agentChatArchiveProject(projectId: String): AgentChatProject {
        val request = Request.Builder().url(apiUrl("agent-chat/projects/$projectId")).delete().build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                if (text.isBlank()) {
                    AgentChatProject(id = projectId, status = "archived")
                } else {
                    json.decodeFromString(AgentChatProject.serializer(), text)
                }
            }
        }
    }

    /** 保存项目顺序：`POST /api/agent-chat/projects/reorder` body `{project_ids}`（服务端全量覆盖）。 */
    suspend fun agentChatReorderProjects(projectIds: List<String>) {
        if (projectIds.isEmpty()) return
        val body = json.encodeToString(
            ReorderAgentChatProjectsRequest.serializer(),
            ReorderAgentChatProjectsRequest(projectIds),
        )
        val request = Request.Builder()
            .url(apiUrl("agent-chat/projects/reorder"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 打开宿主（运行 Denova 的机器）目录选择器：`POST /api/host/dialogs/directory`。
     *
     * 该端点受 `localHostEffectMiddleware` 保护：远程客户端（Android）会收到 403
     * `api.access.localHostEffect`。调用方必须把该错误如实展示，并且**不得**退化为使用手机本地目录。
     */
    suspend fun selectHostDirectory(initialPath: String = ""): HostDirectorySelection {
        val body = json.encodeToString(
            HostDirectoryRequest.serializer(),
            HostDirectoryRequest(initialPath = initialPath),
        )
        val request = Request.Builder()
            .url(apiUrl("host/dialogs/directory"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(HostDirectorySelection.serializer(), text)
            }
        }
    }

    // ---- 工作台：项目作用域 Agent（P4-B，/api/projects/:id/agent-chat/*）----

    /** 项目内新建会话：`POST /api/projects/:id/agent-chat/sessions`。 */
    suspend fun projectAgentCreateSession(
        projectId: String,
        title: String = "",
        customAgentId: String? = null,
    ): AgentChatSession {
        val body = json.encodeToString(
            CreateAgentChatSessionRequest.serializer(),
            CreateAgentChatSessionRequest(title = title, customAgentId = customAgentId),
        )
        val request = Request.Builder()
            .url(projectAgentChatUrl(projectId, "sessions"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentChatSession.serializer(), text)
            }
        }
    }

    /** 会话重命名：`POST .../agent-chat/sessions/rename` body `{session_id,title}`（title 不能为空）。 */
    suspend fun projectAgentRenameSession(projectId: String, sessionId: String, title: String) {
        postProjectAgentChat(
            projectId,
            "sessions/rename",
            json.encodeToString(
                RenameAgentChatSessionRequest.serializer(),
                RenameAgentChatSessionRequest(sessionId, title),
            ),
        )
    }

    /** 删除会话：`POST .../agent-chat/sessions/delete` body `{session_id}`；运行中或最后一个会话被服务端拒绝。 */
    suspend fun projectAgentDeleteSession(projectId: String, sessionId: String) {
        postProjectAgentChat(
            projectId,
            "sessions/delete",
            json.encodeToString(DeleteAgentChatSessionRequest.serializer(), DeleteAgentChatSessionRequest(sessionId)),
        )
    }

    /** 项目 Agent 发送：`POST .../agent-chat/chat`，响应为 AI SDK UI message 流。 */
    fun projectAgentPostChat(projectId: String, request: SendChatRequest): Flow<AgentUiFrame> = flow {
        val body = json.encodeToString(SendChatRequest.serializer(), request)
        val http = Request.Builder()
            .url(projectAgentChatUrl(projectId, "chat"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        streamHttpClient.newCall(http).execute().use { resp ->
            if (!resp.isSuccessful) throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty agent chat stream body")
            emitAll(agentFramesFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    /** 项目 Agent 挂接：`GET .../agent-chat/chat/stream?session_id=&task_id=&after=`。 */
    fun projectAgentStream(
        projectId: String,
        sessionId: String,
        taskId: String,
        after: String?,
    ): Flow<AgentUiFrame> = flow {
        val url = projectAgentChatUrl(projectId, "chat/stream").newBuilder()
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("task_id", taskId)
            .apply { after?.takeIf { it.isNotBlank() }?.let { addQueryParameter("after", it) } }
            .build()
        streamHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw toGameStreamError(resp.code, resp.body?.string().orEmpty())
            val source = resp.body?.source()
                ?: throw GameStreamError(resp.code, null, "empty agent chat stream body")
            emitAll(agentFramesFromSource(source))
        }
    }.flowOn(Dispatchers.IO)

    /** 项目 Agent 活动投影：`GET .../agent-chat/chat/active?session_id=`。 */
    suspend fun projectAgentActive(projectId: String, sessionId: String): AgentActiveView {
        val url = projectAgentChatUrl(projectId, "chat/active").newBuilder()
            .addQueryParameter("session_id", sessionId)
            .build()
        return getJson(url) { text -> json.decodeFromString(AgentActiveView.serializer(), text) }
    }

    /** 项目 Agent 命令（暂停 / 停止 / 追加 / 队列控制）：`POST .../agent-chat/chat/commands`。 */
    suspend fun projectAgentSubmitCommand(
        projectId: String,
        request: AgentCommandRequest,
    ): AgentCommandReceipt {
        val body = json.encodeToString(AgentCommandRequest.serializer(), request)
        val http = Request.Builder()
            .url(projectAgentChatUrl(projectId, "chat/commands"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentCommandReceipt.serializer(), text)
            }
        }
    }

    /** 项目 Agent 恢复动作：`POST .../agent-chat/chat/recovery` body `{session_id, action}`。 */
    suspend fun projectAgentRecover(
        projectId: String,
        sessionId: String,
        action: RecoveryAction,
    ): RecoveryReceipt {
        val body = json.encodeToString(
            AgentRecoveryRequest.serializer(),
            AgentRecoveryRequest(action = action, sessionId = sessionId),
        )
        val request = Request.Builder()
            .url(projectAgentChatUrl(projectId, "chat/recovery"))
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

    /** 项目 Agent 历史：`GET .../agent-chat/session/messages?session_id=&limit=&before=`。 */
    suspend fun projectAgentMessages(
        projectId: String,
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): AgentMessagesPage {
        val url = projectAgentChatUrl(projectId, "session/messages").newBuilder()
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("limit", limit.toString())
            .apply { before?.takeIf { it.isNotBlank() }?.let { addQueryParameter("before", it) } }
            .build()
        return getJson(url) { text -> json.decodeFromString(AgentMessagesPage.serializer(), text) }
    }

    /** 项目 Agent 回答提问：`POST .../agent-chat/session/asks/:id/answer`。 */
    suspend fun projectAgentAnswerAsk(
        projectId: String,
        sessionId: String,
        askId: String,
        answers: List<AgentAskAnswer>,
    ): AgentAskResolution = postProjectAskResolution(
        projectId = projectId,
        path = "session/asks/$askId/answer",
        body = json.encodeToString(
            SessionAskAnswerRequest.serializer(),
            SessionAskAnswerRequest(sessionId = sessionId, answers = answers),
        ),
    )

    /** 项目 Agent 取消提问：`POST .../agent-chat/session/asks/:id/cancel`。 */
    suspend fun projectAgentCancelAsk(
        projectId: String,
        sessionId: String,
        askId: String,
        reason: String = AgentAskLogic.CANCEL_REASON_USER,
    ): AgentAskResolution = postProjectAskResolution(
        projectId = projectId,
        path = "session/asks/$askId/cancel",
        body = json.encodeToString(
            SessionAskCancelRequest.serializer(),
            SessionAskCancelRequest(sessionId = sessionId, reason = reason),
        ),
    )

    private suspend fun postProjectAskResolution(
        projectId: String,
        path: String,
        body: String,
    ): AgentAskResolution {
        val request = Request.Builder()
            .url(projectAgentChatUrl(projectId, path))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentAskResolution.serializer(), text)
            }
        }
    }

    /** 项目 Agent 上下文分析：`POST .../agent-chat/chat/context-analysis`（探针文本不写历史）。 */
    suspend fun projectAgentContextAnalysis(
        projectId: String,
        sessionId: String,
        message: String = CONTEXT_ANALYSIS_PROBE,
    ): AgentContextAnalysis {
        val body = json.encodeToString(
            AgentContextAnalysisRequest.serializer(),
            AgentContextAnalysisRequest(sessionId = sessionId, message = message),
        )
        val request = Request.Builder()
            .url(projectAgentChatUrl(projectId, "chat/context-analysis"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                json.decodeFromString(AgentContextAnalysis.serializer(), text)
            }
        }
    }

    private suspend fun postProjectAgentChat(projectId: String, path: String, body: String) {
        val request = Request.Builder()
            .url(projectAgentChatUrl(projectId, path))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw toException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private fun projectAgentChatUrl(projectId: String, path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/agent-chat/$path")
            .build()

    private fun sessionMessagesUrl(sessionId: String, limit: Int, before: String? = null): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/session/messages")
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("limit", limit.toString())
            .apply { before?.takeIf { it.isNotBlank() }?.let { addQueryParameter("before", it) } }
            .build()

    private fun chatSendUrl(): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat").build()

    private fun agentChatUrl(path: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/$path").build()

    private fun agentActiveUrl(sessionId: String): HttpUrl =
        agentChatUrl("active").newBuilder().addQueryParameter("session_id", sessionId).build()

    /** `api/interactive/<segments>`（stories / tellers）。 */
    private fun interactiveBaseUrl(segments: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/interactive/$segments").build()

    /** `api/<segments>`（game-planning-templates / event-packages / rule-systems / actor-states / image-presets）。 */
    private fun apiUrl(segments: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/$segments").build()

    private fun projectLoreItemsUrl(projectId: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/book/lore/items")
            .build()

    /** `api/projects/:id/book/lore/<suffix>`。 */
    private fun loreUrl(projectId: String, suffix: String): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects/$projectId/book/lore/$suffix")
            .build()

    /** 写请求 + JSON 解码（资料库 / 技能 / 设置等共用）；失败保留错误码，供界面区分原因。 */
    private suspend fun <T> writeJson(
        url: HttpUrl,
        payload: String? = null,
        method: String = "POST",
        slow: Boolean = false,
        decode: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val body = payload?.toRequestBody(jsonMediaType)
        val builder = Request.Builder().url(url)
        when (method) {
            "PUT" -> builder.put(body ?: "".toRequestBody(jsonMediaType))
            "PATCH" -> builder.patch(body ?: "".toRequestBody(jsonMediaType))
            "DELETE" -> builder.delete()
            else -> builder.post(body ?: "".toRequestBody(jsonMediaType))
        }
        (if (slow) slowHttpClient else okHttpClient).newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toException(resp.code, text)
            decode(text)
        }
    }

    /** 只读 GET + JSON 解码（资源目录类端点共用）；失败保留错误码，供界面区分原因。 */
    private suspend fun <T> getJson(url: HttpUrl, decode: (String) -> T): T =
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw toException(resp.code, text)
                decode(text)
            }
        }

    private fun interactiveStoriesUrl(): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder().addPathSegments("api/interactive/stories").build()

    /** 故事作用域端点：`api/interactive/stories/:id[/suffix]`。 */
    private fun interactiveStoryUrl(storyId: String, suffix: String? = null): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(
                if (suffix != null) {
                    "api/interactive/stories/$storyId/$suffix"
                } else {
                    "api/interactive/stories/$storyId"
                },
            )
            .build()

    private fun interactiveSnapshotUrl(storyId: String, branchId: String): HttpUrl =
        interactiveStoryUrl(storyId, "snapshot").newBuilder()
            .apply { if (branchId.isNotBlank()) addQueryParameter("branch", branchId) }
            .build()

    private fun interactiveHistoryUrl(
        storyId: String,
        branchId: String,
        limit: Int,
        before: String? = null,
    ): HttpUrl =
        server.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/interactive/stories/$storyId/history")
            .apply { if (branchId.isNotBlank()) addQueryParameter("branch", branchId) }
            .addQueryParameter("limit", limit.toString())
            .apply { before?.takeIf { it.isNotBlank() }?.let { addQueryParameter("before", it) } }
            .build()

    /** 分支作用域端点：`api/interactive/stories/:id/branches[/:branchId[/suffix]]`。 */
    private fun interactiveBranchUrl(
        storyId: String,
        branchId: String? = null,
        suffix: String? = null,
    ): HttpUrl {
        val path = buildString {
            append("api/interactive/stories/$storyId/branches")
            if (branchId != null) append("/$branchId")
            if (suffix != null) append("/$suffix")
        }
        return server.baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
    }

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

    /** 取受保护素材的原始字节（带会话 Cookie），用于本地播放等拿不到 URL 的场景。 */
    suspend fun assetBytes(projectId: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path.toHttpUrl()
        } else {
            projectAssetUrl(projectId, path)
        }
        okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful) throw toException(resp.code, body?.string().orEmpty())
            body?.bytes() ?: ByteArray(0)
        }
    }

    private fun toException(status: Int, body: String): DenovaApiException {
        val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body) }.getOrNull()
        // 401 统一在这里上报：会话被吊销（服务端密码哈希/局域网开关变化、会话文件读失败）或被本机退出后，
        // 界面不该只弹一句「请先输入远程访问用户名和密码」，而要回到连接页提示重新登录。
        if (status == 401) notifyUnauthorized()
        return DenovaApiException(status, parsed?.code, parsed?.error ?: body.ifBlank { null })
    }

    /** 会话失效（401）的一次性通知：同一次失效只回调一次，避免并发请求刷屏。 */
    private fun notifyUnauthorized() {
        if (suppressUnauthorized.get()) return
        if (unauthorizedNotified.compareAndSet(false, true)) {
            DenovaLog.w("Auth", "session unauthorized (401) origin=${server.origin}")
            onUnauthorized?.invoke()
        }
    }

    /** 重新登录成功后由连接层复位，允许下一次失效再次通知。 */
    fun resetUnauthorizedFlag() {
        unauthorizedNotified.set(false)
    }

    /**
     * 登录请求本身用 401 表示「用户名或密码错误」，不能按「会话失效」处理（否则会把刚打开的连接页
     * 又切一次状态、提示语也变成过期）。登录期间临时屏蔽 401 回调。
     */
    suspend fun <T> suppressingUnauthorized(block: suspend () -> T): T {
        suppressUnauthorized.set(true)
        try {
            return block()
        } finally {
            suppressUnauthorized.set(false)
        }
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
