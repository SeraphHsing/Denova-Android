package com.denova.novara.core.command

import com.denova.novara.core.stream.GameStreamError
import java.util.UUID

/** 一次命令发送的结果。 */
sealed interface SendOutcome {
    data object Accepted : SendOutcome

    data class Rejected(val code: String?, val message: String?) : SendOutcome

    data class Uncertain(val error: Throwable) : SendOutcome
}

/**
 * 命令发送编排：**先完成本地事务（写入 SUBMITTING）再发送**，按结果落
 * ACCEPTED / REJECTED / UNCERTAIN。`command_id` 由调用方生成，重试必须复用同一 id
 * （服务端按 command_id 幂等）。结果不明时不判失败、不换 id，保留记录供核对。
 */
class CommandSender(
    private val storage: CommandStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val send: suspend (commandId: String, payload: String) -> Unit,
) {

    /** 生成一条待发送命令记录。 */
    fun newRecord(target: String, payload: String, commandId: String = newId()): CommandRecord =
        CommandRecord(
            commandId = commandId,
            target = target,
            payload = payload,
            status = CommandStatus.SUBMITTING,
            createdAt = now(),
        )

    /** 发送一条命令：本地事务先落盘，再发；结果不明保留 UNCERTAIN 以便核对。 */
    suspend fun send(record: CommandRecord): SendOutcome {
        storage.save(record.copy(status = CommandStatus.SUBMITTING))
        return try {
            send(record.commandId, record.payload)
            storage.updateStatus(record.commandId, CommandStatus.ACCEPTED)
            SendOutcome.Accepted
        } catch (e: GameStreamError) {
            when {
                // 已接受但展示流已挂接，不算拒绝。
                e.code == "agent_runtime.stream_attached" -> {
                    storage.updateStatus(record.commandId, CommandStatus.ACCEPTED)
                    SendOutcome.Accepted
                }

                // 明确的 4xx 业务拒绝：未执行，可判失败。
                e.status in 400..499 && e.code != null -> {
                    storage.updateStatus(record.commandId, CommandStatus.REJECTED)
                    SendOutcome.Rejected(e.code, e.message)
                }

                // 5xx / 无 code：结果不明。
                else -> {
                    storage.updateStatus(record.commandId, CommandStatus.UNCERTAIN)
                    SendOutcome.Uncertain(e)
                }
            }
        } catch (e: Exception) {
            storage.updateStatus(record.commandId, CommandStatus.UNCERTAIN)
            SendOutcome.Uncertain(e)
        }
    }

    /** 重试复用同一 command_id（服务端幂等）。 */
    suspend fun retry(record: CommandRecord): SendOutcome = send(record)
}
