package com.denova.novara.core.command

import com.denova.novara.core.db.CommandDao
import com.denova.novara.core.db.CommandEntity

/** 命令持久化抽象，便于单测注入 fake。 */
interface CommandStorage {
    suspend fun save(record: CommandRecord)
    suspend fun load(commandId: String): CommandRecord?
    suspend fun listByTarget(target: String): List<CommandRecord>
    suspend fun updateStatus(commandId: String, status: CommandStatus)
    suspend fun remove(commandId: String)
}

/**
 * 本地命令记录（Room 实现）：发送前先完成本地事务（SUBMITTING），结果不明时保留记录，
 * 供下次核对 canonical 状态或复用同一 command_id 重试。
 */
class CommandStore(private val dao: CommandDao) : CommandStorage {

    override suspend fun save(record: CommandRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun load(commandId: String): CommandRecord? =
        dao.get(commandId)?.toRecord()

    override suspend fun listByTarget(target: String): List<CommandRecord> =
        dao.listByTarget(target).map { it.toRecord() }

    override suspend fun updateStatus(commandId: String, status: CommandStatus) {
        dao.updateStatus(commandId, status.name)
    }

    override suspend fun remove(commandId: String) {
        dao.delete(commandId)
    }
}

private fun CommandRecord.toEntity() = CommandEntity(
    commandId = commandId,
    target = target,
    payload = payload,
    status = status.name,
    createdAt = createdAt,
)

private fun CommandEntity.toRecord() = CommandRecord(
    commandId = commandId,
    target = target,
    payload = payload,
    status = CommandStatus.valueOf(status),
    createdAt = createdAt,
)
