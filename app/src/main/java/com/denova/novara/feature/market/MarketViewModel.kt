package com.denova.novara.feature.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.market.MarketLogic
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.Installation
import com.denova.novara.core.network.InstallationBackup
import com.denova.novara.core.network.MarketCatalog
import com.denova.novara.core.network.MarketEntry
import com.denova.novara.core.network.PluginPage
import com.denova.novara.core.network.ProjectExtensionConfig
import com.denova.novara.core.network.ProjectExtensionInput
import com.denova.novara.core.network.ProjectExtensionSettings
import com.denova.novara.core.network.UpdateCheck
import com.denova.novara.core.network.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 资源市场页的三个标签。 */
enum class MarketTab { CATALOG, INSTALLED, RUNTIME }

/** 资源市场界面状态（P4-D / MKT-001…005）。 */
data class MarketUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val language: String = "zh",
    val tab: MarketTab = MarketTab.CATALOG,
    val catalog: MarketCatalog? = null,
    val query: String = "",
    val selectedEntryId: String = "",
    val installations: List<Installation> = emptyList(),
    val selectedInstallationId: String = "",
    val backups: List<InstallationBackup> = emptyList(),
    val pluginPages: List<PluginPage> = emptyList(),
    val runtimes: List<JsonObject> = emptyList(),
    val updateStatus: UpdateStatus? = null,
    val updateCheck: UpdateCheck? = null,
    val busy: Boolean = false,
    /** 最近一次「本机限定」被拒绝的说明；用于把动作如实标灰。 */
    val hostOnly: UiText? = null,
    // ---- 项目级扩展配置（远程可写，MKT-002）----
    val projectId: String = "",
    val extensionConfig: ProjectExtensionConfig? = null,
    val disabledDraft: List<String> = emptyList(),
    val modelsText: String = "",
    val newDisabled: String = "",
    /** 宿主没有当前项目时，允许手动输入项目 ID 读取扩展配置（与编辑器页的项目 ID 输入同口径）。 */
    val projectInput: String = "",
) {
    val entries: List<MarketEntry>
        get() = MarketLogic.search(catalog?.entries.orEmpty(), query, language)

    val groups: List<Pair<String, List<MarketEntry>>> get() = MarketLogic.groupByFormat(entries)

    val selectedEntry: MarketEntry? get() = catalog?.entries?.firstOrNull { it.id == selectedEntryId }

    val selectedInstallation: Installation?
        get() = installations.firstOrNull { it.installationId == selectedInstallationId }
}

/**
 * 资源市场：目录浏览与搜索、已安装资源、插件页与运行时、程序更新状态。
 *
 * 权限口径：目录 / 已安装 / 备份 / 插件页 / 更新状态都是远程可读；**安装、预览、权限、配置、
 * 检查更新与更新策略**是本机限定，远程调用会拿到 403 `LOCAL_HOST_ONLY`，界面据此如实展示
 * 「需在运行 Denova 的本机操作」并把后续动作置灰（MKT-003）。
 */
class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient, language: String, projectId: String) {
        this.client = client
        val projectChanged = projectId.isNotBlank() && projectId != _state.value.projectId
        _state.value = _state.value.copy(
            language = language,
            projectId = if (projectId.isNotBlank()) projectId else _state.value.projectId,
        )
        if (_state.value.catalog == null) load()
        if (projectChanged || _state.value.extensionConfig == null) loadProjectExtensions()
    }

    /** 项目级扩展配置：远程可读可写（禁用插件清单 + 模型映射），写入带 expectedRevision。 */
    fun loadProjectExtensions() {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        viewModelScope.launch {
            try {
                val config = c.projectExtensions(projectId)
                _state.value = _state.value.copy(
                    extensionConfig = config,
                    disabledDraft = config.extensions.disabledPlugins,
                    modelsText = config.extensions.models.toString(),
                )
                DenovaLog.i(
                    "Market",
                    "project extensions project=$projectId revision=${config.revision.take(10)} " +
                        "disabled=${config.extensions.disabledPlugins.size}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Market", "project extensions failed", e)
                _state.value = _state.value.copy(error = failureText(e, R.string.market_extensions_failed))
            }
        }
    }

    fun setProjectInput(value: String) {
        _state.value = _state.value.copy(projectInput = value)
    }

    /** 手动指定项目后读取该项目的扩展配置。 */
    fun useProjectInput() {
        val projectId = _state.value.projectInput.trim()
        if (projectId.isBlank()) return
        _state.value = _state.value.copy(projectId = projectId, extensionConfig = null)
        loadProjectExtensions()
    }

    fun setNewDisabled(value: String) {
        _state.value = _state.value.copy(newDisabled = value)
    }

    fun addDisabled() {
        val id = _state.value.newDisabled.trim()
        if (id.isBlank() || _state.value.disabledDraft.contains(id)) return
        _state.value = _state.value.copy(
            disabledDraft = _state.value.disabledDraft + id,
            newDisabled = "",
        )
    }

    fun removeDisabled(id: String) {
        _state.value = _state.value.copy(
            disabledDraft = _state.value.disabledDraft.filter { it != id },
        )
    }

    fun setModelsText(text: String) {
        _state.value = _state.value.copy(modelsText = text)
    }

    fun saveProjectExtensions() {
        val c = client ?: return
        val config = _state.value.extensionConfig ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        val models = runCatching { Json.parseToJsonElement(_state.value.modelsText).jsonObject }
            .getOrElse {
                _state.value = _state.value.copy(error = UiText.of(R.string.market_models_invalid))
                return
            }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val saved = c.saveProjectExtensions(
                    projectId,
                    ProjectExtensionInput(
                        expectedRevision = config.revision,
                        extensions = ProjectExtensionSettings(
                            disabledPlugins = _state.value.disabledDraft,
                            models = models,
                        ),
                    ),
                )
                _state.value = _state.value.copy(
                    busy = false,
                    extensionConfig = saved,
                    disabledDraft = saved.extensions.disabledPlugins,
                    modelsText = saved.extensions.models.toString(),
                    notice = UiText.of(R.string.market_extensions_saved),
                )
                DenovaLog.i(
                    "Market",
                    "project extensions saved revision=${saved.revision.take(10)} " +
                        "disabled=${saved.extensions.disabledPlugins.size}",
                )
            } catch (e: Exception) {
                val api = e as? DenovaApiException
                val conflict = api?.status == 409
                _state.value = _state.value.copy(
                    busy = false,
                    error = if (conflict) {
                        UiText.of(R.string.market_extensions_conflict)
                    } else {
                        failureText(e, R.string.market_extensions_failed)
                    },
                )
                DenovaLog.w("Market", "project extensions save failed status=${api?.status}", e)
            }
        }
    }

    fun setLanguage(language: String) {
        _state.value = _state.value.copy(language = language)
    }

    fun selectTab(tab: MarketTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun selectEntry(id: String) {
        _state.value = _state.value.copy(selectedEntryId = id)
    }

    fun clearEntry() {
        _state.value = _state.value.copy(selectedEntryId = "")
    }

    fun load() {
        val c = client ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val catalog = c.marketCatalog()
                val installations = runCatching { c.marketInstallations() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    loading = false,
                    catalog = catalog,
                    installations = installations,
                )
                DenovaLog.i(
                    "Market",
                    "loaded entries=${catalog.entries.size} installations=${installations.size} " +
                        "stale=${catalog.stale}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Market", "load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.market_load_failed),
                )
            }
        }
    }

    fun selectInstallation(id: String) {
        _state.value = _state.value.copy(selectedInstallationId = id, backups = emptyList())
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.installationBackups(id) }
                .onSuccess { _state.value = _state.value.copy(backups = it) }
                .onFailure { DenovaLog.w("Market", "backups failed id=$id", it) }
        }
    }

    fun clearInstallation() {
        _state.value = _state.value.copy(selectedInstallationId = "", backups = emptyList())
    }

    /** 刷新目录（本机限定）：远程会拿到 403，界面转成本机限定提示。 */
    fun refreshCatalog() {
        val c = client ?: return
        runHostOnlyAction(
            action = { c.refreshMarketCatalog() },
            onSuccess = { catalog ->
                _state.value = _state.value.copy(catalog = catalog, notice = UiText.of(R.string.market_refreshed))
            },
            fallback = R.string.market_refresh_failed,
        )
    }

    fun checkInstallation(id: String) {
        val c = client ?: return
        runHostOnlyAction(
            action = { c.checkInstallation(id) },
            onSuccess = { _state.value = _state.value.copy(notice = UiText.of(R.string.market_checked)) },
            fallback = R.string.market_check_failed,
            reloadInstallations = true,
        )
    }

    fun setPolicy(id: String, mode: String) {
        val c = client ?: return
        runHostOnlyAction(
            action = { c.setInstallationPolicy(id, mode) },
            onSuccess = {
                _state.value = _state.value.copy(notice = UiText.of(R.string.market_policy_saved))
            },
            fallback = R.string.market_policy_failed,
            reloadInstallations = true,
        )
    }

    fun loadRuntime() {
        val c = client ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val pages = c.pluginPages()
                val runtimes = c.platformRuntimes()
                val status = runCatching { c.updateStatus() }.getOrNull()
                val check = runCatching { c.updateCheck() }.getOrNull()
                _state.value = _state.value.copy(
                    loading = false,
                    pluginPages = pages,
                    runtimes = runtimes,
                    updateStatus = status,
                    updateCheck = check,
                )
                DenovaLog.i(
                    "Market",
                    "runtime pages=${pages.size} runtimes=${runtimes.size} " +
                        "current=${status?.currentVersion} latest=${check?.latestVersion}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Market", "runtime load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.market_runtime_failed),
                )
            }
        }
    }

    /** 本机限定动作的统一收口：403 LOCAL_HOST_ONLY 转成可读提示并记住该限制。 */
    private fun <T> runHostOnlyAction(
        action: suspend () -> T,
        onSuccess: (T) -> Unit,
        fallback: Int,
        reloadInstallations: Boolean = false,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val result = action()
                _state.value = _state.value.copy(busy = false)
                onSuccess(result)
                if (reloadInstallations) refreshInstallations()
            } catch (e: Exception) {
                val api = e as? DenovaApiException
                val hostOnly = MarketLogic.hostOnlyText(api?.code, api?.message)
                _state.value = _state.value.copy(
                    busy = false,
                    error = hostOnly ?: failureText(e, fallback),
                    hostOnly = hostOnly ?: _state.value.hostOnly,
                )
                DenovaLog.w(
                    "Market",
                    "host-only action failed code=${api?.code} status=${api?.status}",
                    e,
                )
            }
        }
    }

    private fun refreshInstallations() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.marketInstallations() }
                .onSuccess { _state.value = _state.value.copy(installations = it) }
                .onFailure { DenovaLog.w("Market", "reload installations failed", it) }
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    /** 运行时快照里只取展示用的 2 个字段（形状随插件演进，不做强类型）。 */
    fun runtimeLabel(runtime: JsonObject): String {
        val id = (runtime["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val status = (runtime["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return listOf(id, status).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun failureText(error: Exception, fallback: Int): UiText =
        (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }?.let(UiText::raw)
            ?: UiText.of(fallback)
}
