package com.denova.novara

import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandSender
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.core.command.CommandStorage
import com.denova.novara.core.command.SendOutcome
import com.denova.novara.core.stream.GameStreamError
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSenderTest {

    private class FakeStorage : CommandStorage {
        val records = mutableMapOf<String, CommandRecord>()

        override suspend fun save(record: CommandRecord) {
            records[record.commandId] = record
        }

        override suspend fun load(commandId: String): CommandRecord? = records[commandId]

        override suspend fun listByTarget(target: String): List<CommandRecord> =
            records.values.filter { it.target == target }

        override suspend fun updateStatus(commandId: String, status: CommandStatus) {
            records[commandId]?.let { records[commandId] = it.copy(status = status) }
        }

        override suspend fun remove(commandId: String) {
            records.remove(commandId)
        }
    }

    @Test
    fun `persists submitting before sending`() = runBlocking {
        val storage = FakeStorage()
        var sawSubmittingBeforeSend = false
        val sender = CommandSender(storage) { _, _ ->
            sawSubmittingBeforeSend = storage.records.values.any { it.status == CommandStatus.SUBMITTING }
        }
        val record = sender.newRecord("chat:s1", "{}")

        val outcome = sender.send(record)

        assertTrue(outcome is SendOutcome.Accepted)
        assertTrue(sawSubmittingBeforeSend)
        assertEquals(CommandStatus.ACCEPTED, storage.records[record.commandId]?.status)
    }

    @Test
    fun `rejected on explicit 4xx`() = runBlocking {
        val storage = FakeStorage()
        val sender = CommandSender(storage) { _, _ ->
            throw GameStreamError(400, "agent_runtime.invalid_command", "bad")
        }
        val record = sender.newRecord("chat:s1", "{}")

        val outcome = sender.send(record)

        assertTrue(outcome is SendOutcome.Rejected)
        assertEquals(CommandStatus.REJECTED, storage.records[record.commandId]?.status)
    }

    @Test
    fun `uncertain on 5xx`() = runBlocking {
        val storage = FakeStorage()
        val sender = CommandSender(storage) { _, _ -> throw GameStreamError(503, null, "unavailable") }
        val record = sender.newRecord("chat:s1", "{}")

        val outcome = sender.send(record)

        assertTrue(outcome is SendOutcome.Uncertain)
        assertEquals(CommandStatus.UNCERTAIN, storage.records[record.commandId]?.status)
    }

    @Test
    fun `uncertain on network error`() = runBlocking {
        val storage = FakeStorage()
        val sender = CommandSender(storage) { _, _ -> throw IOException("timeout") }
        val record = sender.newRecord("chat:s1", "{}")

        val outcome = sender.send(record)

        assertTrue(outcome is SendOutcome.Uncertain)
        assertEquals(CommandStatus.UNCERTAIN, storage.records[record.commandId]?.status)
    }

    @Test
    fun `stream attached counts as accepted`() = runBlocking {
        val storage = FakeStorage()
        val sender = CommandSender(storage) { _, _ ->
            throw GameStreamError(409, "agent_runtime.stream_attached", "attached")
        }
        val record = sender.newRecord("chat:s1", "{}")

        val outcome = sender.send(record)

        assertTrue(outcome is SendOutcome.Accepted)
        assertEquals(CommandStatus.ACCEPTED, storage.records[record.commandId]?.status)
    }

    @Test
    fun `retry reuses same command id`() = runBlocking {
        val storage = FakeStorage()
        val sentIds = mutableListOf<String>()
        val sender = CommandSender(storage) { id, _ ->
            sentIds.add(id)
            throw IOException("x")
        }
        val record = sender.newRecord("chat:s1", "{}", commandId = "cmd-1")

        sender.send(record)
        sender.retry(record)

        assertEquals(listOf("cmd-1", "cmd-1"), sentIds)
    }
}
