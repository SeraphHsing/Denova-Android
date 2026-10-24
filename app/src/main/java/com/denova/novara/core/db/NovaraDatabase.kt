package com.denova.novara.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 本地结构化存储（Room）：草稿与命令身份。
 * 单例由 AppContainer 提供；业务只经 DraftStorage / CommandStorage 接口访问。
 */
@Database(
    entities = [DraftEntity::class, CommandEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NovaraDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun commandDao(): CommandDao
}
