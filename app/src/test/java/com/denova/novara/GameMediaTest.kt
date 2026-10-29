package com.denova.novara

import com.denova.novara.core.game.AttachmentReject
import com.denova.novara.core.game.GameAttachmentLimits
import com.denova.novara.core.network.ChatAttachmentUpload
import com.denova.novara.core.network.ContextCompactionResult
import com.denova.novara.core.network.GenerateInteractiveImageRequest
import com.denova.novara.core.network.GenerateInteractiveImageResult
import com.denova.novara.core.network.RemoveCompactionResult
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.stream.GameActiveView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件 / 配图 / 上下文压缩（P4-A / GAM-016）与重连提问恢复（GAM-011）。
 *
 * 只覆盖线上契约与上限判定；真实生成与压缩走真机 + 真实服务器取证。
 */
class GameMediaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `attachment limits match the server budget`() {
        assertEquals(10, GameAttachmentLimits.MAX_FILES)
        assertEquals(20L * 1024 * 1024, GameAttachmentLimits.MAX_FILE_BYTES)
        assertEquals(50L * 1024 * 1024, GameAttachmentLimits.MAX_TOTAL_BYTES)

        // 第 11 个文件被拒。
        assertNull(GameAttachmentLimits.reject(existingCount = 8, existingBytes = 0, incomingBytes = 1))
        assertEquals(
            AttachmentReject.TOO_MANY,
            GameAttachmentLimits.reject(existingCount = 10, existingBytes = 0, incomingBytes = 1),
        )
        // 单文件 20MiB 是上限（等于仍允许），超出即拒。
        assertNull(
            GameAttachmentLimits.reject(
                existingCount = 0,
                existingBytes = 0,
                incomingBytes = GameAttachmentLimits.MAX_FILE_BYTES,
            ),
        )
        assertEquals(
            AttachmentReject.FILE_TOO_LARGE,
            GameAttachmentLimits.reject(
                existingCount = 0,
                existingBytes = 0,
                incomingBytes = GameAttachmentLimits.MAX_FILE_BYTES + 1,
            ),
        )
        // 合计 50MiB 上限。
        assertEquals(
            AttachmentReject.TOTAL_TOO_LARGE,
            GameAttachmentLimits.reject(
                existingCount = 2,
                existingBytes = 40L * 1024 * 1024,
                incomingBytes = 11L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun `data url keeps mime and round trips the payload`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val url = GameAttachmentLimits.dataUrl("image/png", bytes)

        assertTrue(url.startsWith("data:image/png;base64,"))
        val payload = url.substringAfter("base64,")
        assertArrayEqualsSafe(bytes, Base64.getDecoder().decode(payload))
    }

    @Test
    fun `data url falls back to octet stream when mime is missing`() {
        val url = GameAttachmentLimits.dataUrl("", byteArrayOf(9))
        assertTrue(url.startsWith("data:application/octet-stream;base64,"))
    }

    @Test
    fun `chat request carries attachments as data urls`() {
        val request = SendInteractiveChatRequest(
            commandId = "cmd-1",
            storyId = "st-1",
            branch = "main",
            message = "看看这张图",
            attachments = listOf(
                ChatAttachmentUpload(
                    name = "scene.png",
                    mediaType = "image/png",
                    dataUrl = GameAttachmentLimits.dataUrl("image/png", byteArrayOf(1)),
                ),
            ),
        )
        val encoded = json.encodeToString(SendInteractiveChatRequest.serializer(), request)
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("cmd-1", body["command_id"]?.jsonPrimitive?.content)
        val attachment = body["attachments"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("scene.png", attachment["name"]?.jsonPrimitive?.content)
        assertEquals("image/png", attachment["media_type"]?.jsonPrimitive?.content)
        assertTrue(attachment["data_url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `chat request without attachments omits the field`() {
        val request = SendInteractiveChatRequest(commandId = "cmd-1", storyId = "st-1", message = "hi")
        val body = json.parseToJsonElement(
            json.encodeToString(SendInteractiveChatRequest.serializer(), request),
        ).jsonObject

        assertFalse(body.containsKey("attachments"))
    }

    @Test
    fun `image generate request uses the documented field names`() {
        val request = GenerateInteractiveImageRequest(
            commandId = "img-cmd-1",
            branchId = "main",
            turnId = "turn-7",
            source = GenerateInteractiveImageRequest.SOURCE_AUTO,
            force = false,
        )
        val body = json.parseToJsonElement(
            json.encodeToString(GenerateInteractiveImageRequest.serializer(), request),
        ).jsonObject

        assertEquals("img-cmd-1", body["command_id"]?.jsonPrimitive?.content)
        assertEquals("main", body["branch_id"]?.jsonPrimitive?.content)
        assertEquals("turn-7", body["turn_id"]?.jsonPrimitive?.content)
        assertEquals("auto", body["source"]?.jsonPrimitive?.content)
        assertEquals("false", body["force"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image generate result keeps skip reasons and image identity`() {
        val payload = """
            {
              "enabled": true,
              "skipped": false,
              "image": {
                "schema": "interactive_image.v1",
                "story_id": "st-1",
                "branch_id": "main",
                "turn_id": "turn-7",
                "image_path": "assets/images/st-1/main/turn-7.png",
                "alt_text": "竹林练剑",
                "provider": "openai",
                "model": "gpt-image",
                "mime_type": "image/png",
                "size_bytes": 2048
              }
            }
        """.trimIndent()
        val result = json.decodeFromString(GenerateInteractiveImageResult.serializer(), payload)

        assertTrue(result.enabled)
        assertFalse(result.skipped)
        assertEquals("main", result.image?.branchId)
        assertEquals("turn-7", result.image?.turnId)
        assertEquals("assets/images/st-1/main/turn-7.png", result.image?.imagePath)
        assertEquals(2048L, result.image?.sizeBytes)

        val skipped = json.decodeFromString(
            GenerateInteractiveImageResult.serializer(),
            """{"enabled": false, "skipped": true, "skipped_reason": "manual_mode"}""",
        )
        assertTrue(skipped.skipped)
        assertEquals("manual_mode", skipped.skippedReason)
        assertNull(skipped.image)
    }

    @Test
    fun `compaction results decode both native and runtime managed shapes`() {
        val native = json.decodeFromString(
            ContextCompactionResult.serializer(),
            """{"triggered":true,"revision":42,"tokens_before":9000,"tokens_after":3000,"summary":"前情","source_message_count":18}""",
        )
        assertTrue(native.triggered)
        assertEquals(9000L, native.tokensBefore)
        assertEquals(3000L, native.tokensAfter)
        assertEquals("前情", native.summary)
        assertEquals(42L, native.revision)

        val external = json.decodeFromString(
            ContextCompactionResult.serializer(),
            """{"triggered":true,"runtime_managed":true}""",
        )
        assertTrue(external.runtimeManaged)
        assertNull(external.tokensBefore)

        val skipped = json.decodeFromString(
            ContextCompactionResult.serializer(),
            """{"triggered":false,"skipped_reason":"nothing_to_compact"}""",
        )
        assertFalse(skipped.triggered)
        assertEquals("nothing_to_compact", skipped.skippedReason)

        assertTrue(
            json.decodeFromString(RemoveCompactionResult.serializer(), """{"removed":true}""").removed,
        )
        assertFalse(
            json.decodeFromString(RemoveCompactionResult.serializer(), """{"removed":false}""").removed,
        )
    }

    @Test
    fun `active projection exposes a pending ask for reconnects`() {
        val payload = """
            {
              "active": false,
              "phase": "idle",
              "pending_interruption_id": "",
              "pending_ask": {
                "schema": "ask.pending.v1",
                "id": "ask-9",
                "kind": "tool_approval",
                "tool_call_id": "call-9",
                "status": "pending",
                "questions": [],
                "approval": {
                  "mode": "ask",
                  "tool_name": "web_fetch",
                  "command": "https://example.com",
                  "risk": "network",
                  "can_remember": false
                }
              }
            }
        """.trimIndent()
        val active = json.decodeFromString(GameActiveView.serializer(), payload)

        val ask = active.pendingAsk
        assertEquals("ask-9", ask?.id)
        assertTrue(ask!!.isPending)
        assertTrue(ask.isToolApproval)
        assertEquals("web_fetch", ask.approval?.toolName)
    }

    @Test
    fun `active projection without a pending ask stays null`() {
        val active = json.decodeFromString(
            GameActiveView.serializer(),
            """{"active":true,"phase":"running","task_id":"task-1"}""",
        )
        assertNull(active.pendingAsk)
        assertEquals("task-1", active.taskId)
    }

    private fun assertArrayEqualsSafe(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it]) }
    }

    @Test
    fun `send guard keeps attachment turns with text and blocks empty bodies`() {
        // 正文与附件都空：不发空回合。
        assertTrue(GameAttachmentLimits.isEmptyInput("", 0))
        assertTrue(GameAttachmentLimits.isEmptyInput("   ", 0))
        assertFalse(GameAttachmentLimits.isEmptyInput("hi", 0))
        assertFalse(GameAttachmentLimits.isEmptyInput("", 1))

        // 只有附件没有正文：服务端判 `player input is empty`（2026-10-29 实测），本地先拦。
        assertTrue(GameAttachmentLimits.requiresText("", 1))
        assertTrue(GameAttachmentLimits.requiresText("  ", 2))
        assertFalse(GameAttachmentLimits.requiresText("hi", 2))
        assertFalse(GameAttachmentLimits.requiresText("", 0))
    }
}
