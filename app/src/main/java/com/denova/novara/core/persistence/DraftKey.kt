package com.denova.novara.core.persistence

/**
 * 草稿身份（AUTH-006）：`origin \0 projectId \0 path`。
 *
 * 草稿读写有两处入口——`EditorSession`（打开时恢复 / 清理）与 `FileSaveCoordinator`（保存前落盘），
 * 两边必须用同一份 key，否则会出现「保存写了新 key、打开读旧 key」或反过来的静默丢失。
 * 因此这里收敛成单一纯函数，两边都调它。
 *
 * `origin` 为空时退化为旧格式 `projectId \0 path`：单测与「没有服务器上下文」的调用点保持兼容。
 */
object DraftKey {

    /** 当前作用域的草稿 key。 */
    fun of(origin: String, projectId: String, path: String): String =
        if (origin.isBlank()) legacy(projectId, path) else "$origin\u0000$projectId\u0000$path"

    /** 升级前的旧格式 key（不含服务器作用域），只用于一次性迁移与清理。 */
    fun legacy(projectId: String, path: String): String = "$projectId\u0000$path"
}
