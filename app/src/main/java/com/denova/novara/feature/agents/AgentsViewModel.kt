package com.denova.novara.feature.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.agents.AgentDefinitions
import com.denova.novara.core.agents.AgentEditError
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AgentEngineDescriptor
import com.denova.novara.core.network.CustomAgentDefinition
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Agents 页面状态（P4-C / DEF-001·002）。 */
data class AgentsUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    /** 自定义定义所在的设置层；页面只编辑用户层（跨项目），工作区层属设置页 P4-D。 */
    val layer: String = "user",
    val revision: String = "",
    val customAgents: List<CustomAgentDefinition> = emptyList(),
    val engines: List<AgentEngineDescriptor> = emptyList(),
    val enginesLoading: Boolean = false,
    /** 选中项：`builtin:<key>` 或 `custom:<id>`。 */
    val selectedKey: String = "",
    val busy: Boolean = false,
    // ---- 表单 ----
    val editing: Boolean = false,
    val creating: Boolean = false,
    val editBase: CustomAgentDefinition? = null,
    val editKind: String = "ide",
    val editName: String = "",
    val editDescription: String = "",
    val editInstructions: String = "",
    val editEnabled: Boolean = true,
) {
    val selectedAgent: CustomAgentDefinition?
        get() = customAgents.firstOrNull { "custom:${it.id}" == selectedKey }

    val selectedBuiltinKey: String?
        get() = selectedKey.removePrefix("builtin:").takeIf { selectedKey.startsWith("builtin:") }

    val grouped: List<Pair<com.denova.novara.core.agents.BuiltinAgent, List<CustomAgentDefinition>>>
        get() = AgentDefinitions.grouped(customAgents)
}

/**
 * Agents：自定义定义的新建 / 修改 / 删除（DEF-001）与选择 Agent 进入会话、引擎状态查询（DEF-002）。
 *
 * 写路径只有一条：`PATCH /api/settings {layer:"user", base_revision, changes:{custom_agents:[...]}}`，
 * `custom_agents` 提交**完整数组**（服务端按 ID 合并），因此每次写之前都以服务端快照为基准，
 * 冲突时刷新 revision 并保留用户正在编辑的表单，不静默覆盖。
 */
class AgentsViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        if (this.client === client) return
        this.client = client
        _state.value = AgentsUiState(loading = true)
        load()
    }

    fun load() {
        val c = client ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val snapshot = c.settingsSnapshot()
                _state.value = _state.value.copy(
                    loading = false,
                    revision = snapshot.revisions.user,
                    customAgents = snapshot.customAgents(_state.value.layer),
                )
                DenovaLog.i(
                    "Agents",
                    "settings loaded layer=${_state.value.layer} custom=${_state.value.customAgents.size} " +
                        "revision=${_state.value.revision.take(8)}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Agents", "settings load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.agents_load_failed),
                )
            }
        }
        loadEngines()
    }

    /** 引擎目录与状态（DEF-002「查询状态」）。 */
    fun loadEngines() {
        val c = client ?: return
        if (_state.value.enginesLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(enginesLoading = true)
            try {
                val response = c.agentRuntimes()
                _state.value = _state.value.copy(enginesLoading = false, engines = response.items)
                DenovaLog.i(
                    "Agents",
                    "engines loaded count=${response.items.size} " +
                        response.items.joinToString { "${it.id}:${it.status}" },
                )
            } catch (e: Exception) {
                DenovaLog.w("Agents", "engines load failed", e)
                _state.value = _state.value.copy(
                    enginesLoading = false,
                    error = failureText(e, R.string.agents_engines_failed),
                )
            }
        }
    }

    /** 重新检查一个引擎（`POST /api/agent-runtimes/:id/check`）。 */
    fun checkEngine(engineId: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val updated = c.agentEngineCheck(engineId)
                val engines = _state.value.engines.map { if (it.id == engineId) updated else it }
                    .ifEmpty { listOf(updated) }
                _state.value = _state.value.copy(engines = engines, error = null)
                DenovaLog.i("Agents", "engine checked id=$engineId status=${updated.status}")
            } catch (e: Exception) {
                DenovaLog.w("Agents", "engine check failed id=$engineId", e)
                _state.value = _state.value.copy(
                    error = failureText(e, R.string.agents_engines_failed),
                )
            }
        }
    }

    fun select(key: String) {
        if (_state.value.selectedKey == key) return
        _state.value = _state.value.copy(selectedKey = key, editing = false, creating = false, editBase = null)
    }

    fun beginCreate(kind: String) {
        _state.value = _state.value.copy(
            editing = true,
            creating = true,
            editBase = null,
            editKind = kind,
            editName = "",
            editDescription = "",
            editInstructions = "",
            editEnabled = true,
            error = null,
            notice = null,
        )
    }

    fun beginEdit() {
        val agent = _state.value.selectedAgent ?: return
        _state.value = _state.value.copy(
            editing = true,
            creating = false,
            editBase = agent,
            editKind = AgentDefinitions.runtimeKindOf(agent),
            editName = agent.name,
            editDescription = agent.description,
            editInstructions = agent.instructions,
            editEnabled = agent.enabled,
            error = null,
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = false, creating = false, editBase = null)
    }

    fun editName(value: String) {
        _state.value = _state.value.copy(editName = value)
    }

    fun editDescription(value: String) {
        _state.value = _state.value.copy(editDescription = value)
    }

    fun editInstructions(value: String) {
        _state.value = _state.value.copy(editInstructions = value)
    }

    fun editEnabled(value: Boolean) {
        _state.value = _state.value.copy(editEnabled = value)
    }

    fun editKind(value: String) {
        if (!_state.value.creating) return
        _state.value = _state.value.copy(editKind = value)
    }

    /** 保存定义：新建克隆运行契约，编辑只覆盖可编辑字段（其余配置原样保留）。 */
    fun save() {
        val c = client ?: return
        val current = _state.value
        if (current.busy) return

        val error = if (current.creating) {
            AgentDefinitions.validate(current.editName, AgentDefinitions.contractFor(current.editKind))
        } else {
            AgentDefinitions.validate(current.editName, current.editBase?.contract.orEmpty())
        }
        if (error != null) {
            _state.value = current.copy(error = editErrorText(error))
            return
        }

        val definition = if (current.creating) {
            AgentDefinitions.newDefinition(
                id = AgentDefinitions.newId(System.currentTimeMillis()),
                name = current.editName,
                description = current.editDescription,
                contract = AgentDefinitions.contractFor(current.editKind),
                instructions = current.editInstructions,
            )
        } else {
            current.editBase?.withEdits(
                name = current.editName,
                description = current.editDescription,
                instructions = current.editInstructions,
                enabled = current.editEnabled,
            ) ?: return
        }
        val nextAgents = AgentDefinitions.upsert(current.customAgents, definition)
        val createdId = (definition["id"]?.toString()?.trim('"')).orEmpty()

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val snapshot = c.patchSettings(
                    layer = current.layer,
                    baseRevision = current.revision,
                    changes = AgentDefinitions.changesOf(nextAgents),
                )
                val agents = snapshot.customAgents(current.layer)
                _state.value = _state.value.copy(
                    busy = false,
                    revision = snapshot.revisions.user,
                    customAgents = agents,
                    editing = false,
                    creating = false,
                    editBase = null,
                    selectedKey = "custom:$createdId",
                    notice = if (current.creating) {
                        UiText.of(R.string.agents_created, current.editName)
                    } else {
                        UiText.of(R.string.agents_saved, current.editName)
                    },
                )
                DenovaLog.i(
                    "Agents",
                    "definition saved id=$createdId creating=${current.creating} total=${agents.size} " +
                        "revision=${snapshot.revisions.user.take(8)}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Agents", "definition save failed id=$createdId", e)
                val conflict = e is DenovaApiException && e.status == 409
                _state.value = _state.value.copy(
                    busy = false,
                    error = if (conflict) {
                        UiText.of(R.string.agents_conflict)
                    } else {
                        failureText(e, R.string.agents_save_failed)
                    },
                )
                if (conflict) refreshRevision()
            }
        }
    }

    /** 删除自定义定义（二次确认由界面负责）。 */
    fun delete(id: String) {
        val c = client ?: return
        val current = _state.value
        if (current.busy) return
        val nextAgents = AgentDefinitions.remove(current.customAgents, id)
        val name = current.customAgents.firstOrNull { it.id == id }?.name.orEmpty()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val snapshot = c.patchSettings(
                    layer = current.layer,
                    baseRevision = current.revision,
                    changes = AgentDefinitions.changesOf(nextAgents),
                )
                val agents = snapshot.customAgents(current.layer)
                _state.value = _state.value.copy(
                    busy = false,
                    revision = snapshot.revisions.user,
                    customAgents = agents,
                    selectedKey = "",
                    editing = false,
                    creating = false,
                    editBase = null,
                    notice = UiText.of(R.string.agents_deleted, name.ifBlank { id }),
                )
                DenovaLog.i("Agents", "definition deleted id=$id total=${agents.size}")
            } catch (e: Exception) {
                DenovaLog.w("Agents", "definition delete failed id=$id", e)
                val conflict = e is DenovaApiException && e.status == 409
                _state.value = _state.value.copy(
                    busy = false,
                    error = if (conflict) {
                        UiText.of(R.string.agents_conflict)
                    } else {
                        failureText(e, R.string.agents_delete_failed)
                    },
                )
                if (conflict) refreshRevision()
            }
        }
    }

    /** 启用 / 停用一个自定义定义（同一 PATCH 路径）。 */
    fun toggleEnabled(agent: CustomAgentDefinition, enabled: Boolean) {
        val c = client ?: return
        val current = _state.value
        if (current.busy) return
        val nextAgents = AgentDefinitions.upsert(current.customAgents, agent.withEdits(enabled = enabled))
        viewModelScope.launch {
            try {
                val snapshot = c.patchSettings(
                    layer = current.layer,
                    baseRevision = current.revision,
                    changes = AgentDefinitions.changesOf(nextAgents),
                )
                _state.value = _state.value.copy(
                    revision = snapshot.revisions.user,
                    customAgents = snapshot.customAgents(current.layer),
                )
                DenovaLog.i("Agents", "definition enabled id=${agent.id} enabled=$enabled")
            } catch (e: Exception) {
                DenovaLog.w("Agents", "definition toggle failed id=${agent.id}", e)
                _state.value = _state.value.copy(
                    error = failureText(e, R.string.agents_save_failed),
                )
            }
        }
    }

    /**
     * 为选中的 Agent 打开会话（DEF-002）：自定义定义走
     * `POST /api/sessions {title, custom_agent_id}`，内置条目直接新建普通会话。
     * 成功后由界面导航到写作 Agent 页并挂接该会话。
     */
    suspend fun openSession(): String? {
        val c = client ?: return null
        val current = _state.value
        val agent = current.selectedAgent
        return try {
            val session = c.createSessionWithAgent(
                title = agent?.name?.takeIf { it.isNotBlank() } ?: "Agent session",
                customAgentId = agent?.id.orEmpty(),
            )
            DenovaLog.i(
                "Agents",
                "session created id=${session.id} custom=${agent?.id.orEmpty()} builtin=${current.selectedBuiltinKey.orEmpty()}",
            )
            _state.value = _state.value.copy(notice = UiText.of(R.string.agents_session_created, session.title.ifBlank { session.id }))
            session.id
        } catch (e: Exception) {
            DenovaLog.w("Agents", "session create failed", e)
            _state.value = _state.value.copy(error = failureText(e, R.string.agents_session_failed))
            null
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** 冲突后只刷新 revision（保留当前表单内容，让用户重新提交）。 */
    private fun refreshRevision() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.settingsSnapshot() }.getOrNull()?.let { snapshot ->
                _state.value = _state.value.copy(
                    revision = snapshot.revisions.user,
                    customAgents = snapshot.customAgents(_state.value.layer),
                )
            }
        }
    }

    private fun editErrorText(error: AgentEditError): UiText = when (error) {
        AgentEditError.NAME_REQUIRED -> UiText.of(R.string.agents_name_required)
        AgentEditError.INVALID_CONTRACT -> UiText.of(R.string.agents_contract_invalid)
    }

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }
}
