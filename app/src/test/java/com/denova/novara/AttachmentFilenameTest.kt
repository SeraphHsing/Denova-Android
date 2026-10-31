package com.denova.novara

import com.denova.novara.core.network.attachmentFilename
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Content-Disposition` 文件名解析（TRJ-003 导出）。
 *
 * 回归点：旧实现 `substringAfter("filename=")` 会把 `"; filename*=UTF-8''…` 一起当成文件名，
 * 真机导出的缓存文件因此带上整段参数，分享面板拿到的路径也变成
 * `…jsonl___filename__UTF-8__…`（2026-11-01 logcat 确认）。
 */
class AttachmentFilenameTest {

    @Test
    fun `prefers the rfc5987 filename and drops the plain fallback`() {
        val header = "attachment; filename=\"run-20261030T121417.jsonl\"; " +
            "filename*=UTF-8''run-20261030T121417.jsonl"

        assertEquals("run-20261030T121417.jsonl", attachmentFilename(header, "fallback.jsonl"))
    }

    @Test
    fun `reads a quoted plain filename`() {
        assertEquals("trace.jsonl", attachmentFilename("attachment; filename=\"trace.jsonl\"", "x.jsonl"))
    }

    @Test
    fun `reads an unquoted plain filename`() {
        assertEquals("trace.jsonl", attachmentFilename("attachment; filename=trace.jsonl", "x.jsonl"))
    }

    @Test
    fun `decodes percent encoding in the extended form`() {
        // 「轨迹.jsonl」的 percent-encoding。
        val header = "attachment; filename*=UTF-8''%E8%BD%A8%E8%BF%B9.jsonl"

        assertEquals("轨迹.jsonl", attachmentFilename(header, "x.jsonl"))
    }

    @Test
    fun `returns the fallback when the header is missing or useless`() {
        assertEquals("run-1.jsonl", attachmentFilename("", "run-1.jsonl"))
        assertEquals("run-1.jsonl", attachmentFilename("attachment", "run-1.jsonl"))
        assertEquals("run-1.jsonl", attachmentFilename("attachment; filename=\"\"", "run-1.jsonl"))
    }

    @Test
    fun `strips directory components from a hostile filename`() {
        assertEquals("passwd", attachmentFilename("attachment; filename=\"../../etc/passwd\"", "x.jsonl"))
        assertEquals("evil.jsonl", attachmentFilename("attachment; filename=\"C:\\\\temp\\\\evil.jsonl\"", "x.jsonl"))
    }
}
