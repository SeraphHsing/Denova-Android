package com.denova.novara.core.command

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.commandDataStore by preferencesDataStore(name = "commands")

/** 命令持久化抽象，便于单测注入 fake。 */
interface CommandStorage {
    suspend fun save(record: CommandRecord)

    suspend fun load(commandId: String): CommandRecord?

    suspend fun listByTarget(target: String): List<CommandRecord>

    suspend fun updateStatus(commandId: String, status: CommandStatus)

    suspend fun remove(commandId: String)
}

/**
 * 本地命令记录：发送前先完成本地事务（SUBMITTING），结果不明时保留记录，
 * 供下次核对 canonical 状态或复用同一 command_id 重试。
 */
class CommandStore(private val context: Context) : CommandStorage {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapKey = stringPreferencesKey("command_map")

    override suspend fun save(record: CommandRecord) {
        val map = loadAll().toMutableMap()
        map[record.commandId] = record
        persist(map)
    }

    override suspend fun load(commandId: String): CommandRecord? = loadAll()[commandId]

    override suspend fun listByTarget(target: String): List<CommandRecord> =
        loadAll().values.filter { it.target == target }.sortedBy { it.createdAt }

    override suspend fun updateStatus(commandId: String, status: CommandStatus) {
        val map = loadAll().toMutableMap()
        val existing = map[commandId] ?: return
        map[commandId] = existing.copy(status = status)
        persist(map)
    }

    override suspend fun remove(commandId: String) {
        val map = loadAll().toMutableMap()
        map.remove(commandId)
        persist(map)
    }

    private suspend fun persist(map: Map<String, CommandRecord>) {
        context.commandDataStore.edit { prefs -> prefs[mapKey] = json.encodeToString(map) }
    }

    private suspend fun loadAll(): Map<String, CommandRecord> {
        val raw = context.commandDataStore.data.first()[mapKey] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, CommandRecord>>(raw) }.getOrDefault(emptyMap())
    }
}
