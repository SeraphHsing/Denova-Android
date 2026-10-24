package com.denova.novara.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 命令身份表：发送前先落本地事务，结果不明时保留记录以核对/复用 command_id 重试。 */
@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey val commandId: String,
    val target: String,
    val payload: String,
    val status: String,
    val createdAt: Long,
)
