package com.denova.novara.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CommandDao {

    @Upsert
    suspend fun upsert(command: CommandEntity)

    @Query("SELECT * FROM commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): CommandEntity?

    @Query("SELECT * FROM commands WHERE target = :target ORDER BY createdAt ASC")
    suspend fun listByTarget(target: String): List<CommandEntity>

    @Query("UPDATE commands SET status = :status WHERE commandId = :commandId")
    suspend fun updateStatus(commandId: String, status: String)

    @Query("DELETE FROM commands WHERE commandId = :commandId")
    suspend fun delete(commandId: String)
}
