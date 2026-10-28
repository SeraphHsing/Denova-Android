package com.denova.novara.core.market

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.network.Installation
import com.denova.novara.core.network.MarketEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 资源市场 / 扩展的纯逻辑（P4-D / MKT-001…005）。
 *
 * 与基线一致的口径：
 * - 目录搜索完全在客户端做（按名称 + 描述 + id 的空格分词全包含）；
 * - 目录按 `kind` 分组（plugin / game / 其它）；
 * - 名称与描述是本地化对象，按当前语言挑，缺语言时退到另一语言；
 * - 安装 / 预览 / 权限 / 配置写操作都是**本机限定**，远程客户端拿到 403 `LOCAL_HOST_ONLY`。
 */
object MarketLogic {

    val UPDATE_MODES = listOf("manual", "notify", "auto_apply")

    /** 取本地化文本：优先当前语言，其次另一语言，最后取第一个值。 */
    fun localized(value: JsonObject, language: String): String {
        val zh = language.startsWith("zh")
        val primary = if (zh) "zh-CN" else "en-US"
        val secondary = if (zh) "en-US" else "zh-CN"
        return (value[primary] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (value[secondary] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: value.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { text -> text.isNotBlank() } }
            ?: ""
    }

    /**
     * 搜索：按空格分词，**每个词**都要出现在名称 / 描述 / id 里（大小写不敏感）。
     * 空查询返回原列表（与基线「无输入即全量」一致）。
     */
    fun search(entries: List<MarketEntry>, query: String, language: String): List<MarketEntry> {
        val tokens = query.trim().split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return entries
        return entries.filter { entry ->
            val haystack = buildString {
                append(localized(entry.name, language))
                append(' ')
                append(localized(entry.description, language))
                append(' ')
                append(entry.id)
                append(' ')
                entry.tags.forEach { append(' ').append(it) }
            }.lowercase()
            tokens.all { haystack.contains(it.lowercase()) }
        }
    }

    /**
     * 分组：按资源 `format`（例如 `denova.resource-pack`）分组，缺省归到 `resource`。
     * 该部署的 registry 用 `kinds`（`lore.collection` / `game.openings` …）描述内容，
     * 一个条目可能跨多个 kind，所以不用 kinds 当分组键（会把同一条重复列多次）。
     */
    fun groupByFormat(entries: List<MarketEntry>): List<Pair<String, List<MarketEntry>>> =
        entries.groupBy { it.format.ifBlank { "resource" } }
            .map { (format, list) -> format to list }
            .sortedBy { it.first }

    fun updateModeLabel(mode: String): UiText = when (mode) {
        "notify" -> UiText.of(R.string.market_update_mode_notify)
        "auto_apply" -> UiText.of(R.string.market_update_mode_auto)
        "manual" -> UiText.of(R.string.market_update_mode_manual)
        else -> UiText.raw(mode)
    }

    fun localStateLabel(state: String): UiText = when (state) {
        "missing" -> UiText.of(R.string.market_state_missing)
        "modified" -> UiText.of(R.string.market_state_modified)
        "" -> UiText.of(R.string.market_state_unknown)
        else -> UiText.raw(state)
    }

    fun trackingLabel(installation: Installation): UiText = when (installation.tracking) {
        "tracked" -> UiText.of(R.string.market_tracking_tracked)
        "" -> UiText.of(R.string.market_tracking_untracked)
        else -> UiText.raw(installation.tracking)
    }

    /**
     * 本机限定动作的错误映射：服务端 `platformManagementLocalHostMiddleware` 返回
     * `403 {"code":"LOCAL_HOST_ONLY","messageKey":"platform.errors.LOCAL_HOST_ONLY"}`；
     * `localHostEffectMiddleware` 只有 `error` 文案。两种都收敛成同一句「需在运行 Denova 的本机操作」。
     */
    fun hostOnlyText(code: String?, message: String?): UiText? {
        val isHostOnly = code == "LOCAL_HOST_ONLY" ||
            message?.contains("本机", ignoreCase = false) == true ||
            message?.contains("local host", ignoreCase = true) == true
        return if (isHostOnly) UiText.of(R.string.market_local_only) else null
    }

    /** 已安装资源按项目分组（项目 id 尾 8 位当标签）。 */
    fun groupByProject(installations: List<Installation>): List<Pair<String, List<Installation>>> =
        installations.groupBy { it.projectId }
            .map { (project, list) -> project to list.sortedBy { it.label.lowercase() } }
            .sortedBy { it.first }
}
