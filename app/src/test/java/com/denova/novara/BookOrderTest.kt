package com.denova.novara

import com.denova.novara.core.books.BookOrder
import com.denova.novara.core.network.BookRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 书籍手动排序（P4-C / BOK-003）：手机端上移 / 下移 → 提交完整路径表。 */
class BookOrderTest {

    private fun book(name: String) =
        BookRecord(projectId = "project-$name", name = name, path = "/data/.denova/projects/$name")

    private val books = listOf(book("a"), book("b"), book("c"))

    @Test
    fun `moving up swaps with the previous book`() {
        val moved = BookOrder.move(books, "/data/.denova/projects/b", -1)

        assertEquals(listOf("b", "a", "c"), moved.map { it.name })
        assertEquals(
            listOf("/data/.denova/projects/b", "/data/.denova/projects/a", "/data/.denova/projects/c"),
            BookOrder.paths(moved),
        )
    }

    @Test
    fun `moving down swaps with the next book`() {
        assertEquals(listOf("a", "c", "b"), BookOrder.move(books, "/data/.denova/projects/b", 1).map { it.name })
    }

    @Test
    fun `out of range move keeps the original list`() {
        // 越界时返回同一个实例，调用方可据此跳过 reorder 请求。
        assertSame(books, BookOrder.move(books, "/data/.denova/projects/a", -1))
        assertSame(books, BookOrder.move(books, "/data/.denova/projects/c", 1))
        assertSame(books, BookOrder.move(books, "/data/.denova/projects/missing", 1))
    }
}
