package com.denova.novara.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DraftDao {

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE fileKey = :fileKey")
    suspend fun get(fileKey: String): DraftEntity?

    @Query("DELETE FROM drafts WHERE fileKey = :fileKey")
    suspend fun delete(fileKey: String)
}
