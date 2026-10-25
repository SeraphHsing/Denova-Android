package com.denova.novara.feature.editor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream

/**
 * 预览里的图片（WRI-007）：Markdown 里的相对路径必须换算成项目资源接口
 * `GET /api/projects/:id/files/asset?path=`，而 WebView 用不了 OkHttp 的会话 Cookie，
 * 所以请求在 [shouldInterceptRequest] 里由 OkHttp 代取（同一 CookieJar），失败时返回原状态码，
 * 浏览器按破图处理、`alt` 文案仍可见。
 *
 * 只重写 `<img src>`：外链（http/https）与 data URI 原样保留，不替用户改协议。
 */
class PreviewAssetLoader(
    private val client: DenovaClient,
    private val projectId: String,
    private val documentPath: String,
) {

    /** 把 HTML 里的相对图片路径改写成带身份的项目资源地址。 */
    fun rewrite(html: String): String = IMG_SRC.replace(html) { match ->
        val raw = match.groupValues[1]
        val resolved = resolve(raw)
        DenovaLog.i("Editor", "preview img raw=$raw resolved=${resolved ?: "null"}")
        if (resolved == null) match.value else "<img src=\"$resolved\""
    }

    /** 仅用于日志：当前文档的项目 / 路径 / 服务端 host，便于对照「为什么没重写」。 */
    fun describe(): String = "project=$projectId doc=$documentPath host=${client.projectAssetUrl(projectId, "x").host}"

    /**
     * 预览文档的 base URL：必须给 WebView 一个真实 origin，
     * 否则 `data:` 文档会以不透明来源丢弃 http 子资源请求（图片表现为破图）。
     */
    val baseUrl: String get() = client.serverOrigin

    /**
     * 相对路径按「当前文档所在目录」解析（与 CommonMark / 浏览器一致），
     * `..` 段做折叠；结果必须是项目内路径，越界返回 null 交给浏览器原样请求（表现为破图而不是越权读取）。
     */
    fun resolve(raw: String): String? {
        val path = resolveAssetPath(documentPath, raw) ?: return null
        return client.projectAssetUrl(projectId, path).toString()
    }

    /** 只接管项目资源：其他请求（包括外链图片）交回 WebView。 */
    fun webViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url ?: return null
            DenovaLog.i("Editor", "preview request path=${uri.encodedPath}")
            if (!uri.encodedPath.orEmpty().endsWith("/files/asset")) return null
            val expectedHost = client.projectAssetUrl(projectId, "x").host
            if (uri.host != expectedHost) return null
            val target = uri.toString().toHttpUrl()
            return try {
                client.okHttpClient.newCall(okhttp3.Request.Builder().url(target).build()).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        DenovaLog.w("Editor", "preview asset failed path=${uri.getQueryParameter("path")} status=${resp.code}")
                        return WebResourceResponse("text/plain", "utf-8", resp.code, "asset", emptyMap(), null)
                    }
                    // 必须在响应关闭前把字节读出来：WebView 读流发生在 use 块之后。
                    val bytes = body.bytes()
                    val mime = body.contentType()?.toString()?.substringBefore(';') ?: "application/octet-stream"
                    DenovaLog.i("Editor", "preview asset ok path=${uri.getQueryParameter("path")} mime=$mime bytes=${bytes.size}")
                    WebResourceResponse(mime, null, 200, "OK", emptyMap(), ByteArrayInputStream(bytes))
                }
            } catch (e: Exception) {
                DenovaLog.w("Editor", "preview asset error path=${uri.getQueryParameter("path")}", e)
                WebResourceResponse("text/plain", "utf-8", 502, "asset", emptyMap(), null)
            }
        }
    }

    /**
     * 生成用于 `loadDataWithBaseURL` 的 HTML：插入表格包一层横向滚动容器（WRI-006），
     * 图片限制最大宽度保持原始比例（WRI-007）。
     */
    fun decorate(html: String): String = wrapTables(html)

    companion object {
        /**
         * 相对路径 → 项目内路径（纯函数，便于单测）：按文档所在目录解析并折叠 `.` / `..`。
         * 返回 null 表示不该重写：空路径、外链、data URI、或越出项目根。
         */
        fun resolveAssetPath(documentPath: String, raw: String): String? {
            val src = raw.trim()
            if (src.isEmpty()) return null
            if (src.startsWith("http://", true) || src.startsWith("https://", true) || src.startsWith("data:", true)) {
                return null
            }
            val baseDir = documentPath.substringBeforeLast('/', missingDelimiterValue = "")
            val joined = when {
                src.startsWith("/") -> src.trimStart('/')
                baseDir.isEmpty() -> src
                else -> "$baseDir/$src"
            }
            val segments = ArrayDeque<String>()
            for (segment in joined.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            if (segments.isEmpty()) return null
            return segments.joinToString("/")
        }

        /**
         * 反向换算：把项目内绝对路径改成相对当前文档的引用（插入图片时写进 Markdown 的形态）。
         * 与 [resolveAssetPath] 互为逆运算，两边都有单测。
         */
        fun relativeAssetPath(documentPath: String, assetPath: String): String {
            val from = documentPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
            val to = assetPath.split('/').filter { it.isNotEmpty() }
            if (to.isEmpty()) return assetPath
            var common = 0
            while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
            val up = List(from.size - common) { ".." }
            return (up + to.drop(common)).joinToString("/").ifEmpty { assetPath }
        }

        /** 给每个 `<table>` 包一层可横向滚动的容器（WRI-006 宽表格不被裁掉）。 */
        fun wrapTables(html: String): String = html
            .replace("<table>", "<div class=\"table-scroll\"><table>")
            .replace("</table>", "</table></div>")

        private val IMG_SRC = Regex("""<img\s+[^>]*src="([^"]*)"""")

        /** 预览样式：表格可横向滚动、图片等比缩放、破图时保留 alt。 */
        const val STYLE = """
            body { font-family: sans-serif; padding: 16px; line-height: 1.6; }
            .table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid #ccc; padding: 6px; white-space: nowrap; }
            img { max-width: 100%; height: auto; }
            img[alt] { color: #999; font-size: 12px; }
            pre { background: #f5f5f5; padding: 8px; overflow-x: auto; }
            blockquote { border-left: 3px solid #ccc; margin: 0; padding-left: 12px; color: #555; }
        """
    }
}
