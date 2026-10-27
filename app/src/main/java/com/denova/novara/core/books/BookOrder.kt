package com.denova.novara.core.books

import com.denova.novara.core.network.BookRecord

/**
 * 书籍手动排序（P4-C / BOK-003）的纯逻辑：手机端用上移 / 下移替代桌面拖拽，
 * 但提交给 `POST /api/books/reorder` 的仍是**完整路径表**，与服务端 `Reorder` 契约一致。
 */
object BookOrder {

    /** 把 [path] 移动 [delta] 格；越界或找不到时原样返回（调用方可直接跳过请求）。 */
    fun move(books: List<BookRecord>, path: String, delta: Int): List<BookRecord> {
        if (delta == 0) return books
        val index = books.indexOfFirst { it.path == path }
        if (index < 0) return books
        val target = index + delta
        if (target !in books.indices) return books
        return books.toMutableList().apply { add(target, removeAt(index)) }
    }

    /** 提交顺序：只发路径，保持列表顺序（服务端只替换 Book 槽位）。 */
    fun paths(books: List<BookRecord>): List<String> = books.map { it.path }
}
