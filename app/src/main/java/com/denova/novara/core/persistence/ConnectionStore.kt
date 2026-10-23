package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.connectionDataStore by preferencesDataStore(name = "connection")

private val KEY_SERVER = stringPreferencesKey("server")

/** 连接偏好持久化：只保存服务器地址原文，不保存凭证/令牌。 */
class ConnectionStore(private val context: Context) {

    suspend fun saveServer(raw: String) {
        context.connectionDataStore.edit { it[KEY_SERVER] = raw.trim() }
    }

    suspend fun loadServer(): String? = context.connectionDataStore.data.first()[KEY_SERVER]
}
