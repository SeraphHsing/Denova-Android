package com.denova.novara.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 草稿表：按文件 key 存未提交内容与三方合并所需的 base 版本。 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val fileKey: String,
    val content: String,
    val baseRevision: String,
    val baseContent: String,
)
