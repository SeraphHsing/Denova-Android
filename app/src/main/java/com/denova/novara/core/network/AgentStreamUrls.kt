package com.denova.novara.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 写作 Agent 任务流地址（`GET /api/chat/stream`）。
 *
 * 服务端 `HandleChatStream` 先解析 `session_id`、再按 `task_id` 精确匹配当前活跃任务：
 * 缺 `session_id` 直接返回 400 `agent_runtime.invalid_binding`，缺 `task_id` 返回 400。
 * 因此重挂（断流恢复、手动挂接）必须两个都带，否则整条恢复链在第一次 attach 就被拒。
 *
 * `after` 是事件游标（服务端 `after` 查询参数，十进制 uint64），取自 active 投影的 `stream_cursor`。
 */
internal fun writingAgentStreamUrl(
    baseUrl: String,
    sessionId: String,
    taskId: String,
    after: String? = null,
): HttpUrl = baseUrl.toHttpUrl().newBuilder()
    .addPathSegments("api/chat/stream")
    .addQueryParameter("session_id", sessionId)
    .addQueryParameter("task_id", taskId)
    .apply { if (after != null) addQueryParameter("after", after) }
    .build()
