package com.denova.novara.core.network

import com.denova.novara.core.model.ServerKey
import com.denova.novara.core.save.FileRemote
import com.denova.novara.core.stream.GameActiveView
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.GameStreamTransport
import com.denova.novara.core.stream.RecoveryAction
import com.denova.novara.core.stream.RecoveryReceipt
import com.denova.novara.core.stream.RecoveryRequest
import com.denova.novara.core.stream.StoryHistoryPage
import com.denova.novara.core.stream.parseGameStreamError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
 * 单个服务器的认证客户端。每个 ServerKey 一个实例；Cookie 通过内存 [InMemoryCookieJar]
 * 隔离，切换服务器即重建客户端，从而隔离会话 Cookie。
 */
class DenovaClient(private val server: ServerKey) : FileRemote, GameStreamTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 共享 OkHttpClient（含会话 CookieJar）：受保护图片、SSE、WS 都复用此实例，保证会话一致。 */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        okHttpClient.newCall(request).execute().use { resp ->
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
