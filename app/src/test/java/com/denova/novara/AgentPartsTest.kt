package com.denova.novara

import com.denova.novara.core.stream.AgentDataPart
import com.denova.novara.core.stream.AgentImagePart
import com.denova.novara.core.stream.AgentMessage
import com.denova.novara.core.stream.AgentPartLogic
import com.denova.novara.core.stream.AgentReasoningPart
import com.denova.novara.core.stream.AgentTextPart
import com.denova.novara.core.stream.AgentToolPart
import com.denova.novara.core.stream.AgentUnknownPart
import com.denova.novara.core.stream.ToolCallStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-001 完整消息部件：解析、稳定身份、工具状态与摘要、图片分类、JSON 截断。 */
class AgentPartsTest {

    private fun part(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun message(id: String = "m1", role: String = "assistant", vararg parts: String) =
        AgentMessage(id = id, role = role, parts = parts.map(::part))

    @Test
    fun `text and reasoning parts keep stable ids`() {
        val view = AgentPartLogic.parseMessage(
            message(
                id = "m1",
                parts = arrayOf(
                    """{"type":"text","text":"第一段"}""",
                    """{"type":"reasoning","text":"先想一想"}""",
                    """{"type":"text","text":"","state":"streaming"}""",
                ),
            ),
        )
        assertEquals("m1", view.id)
        assertEquals(3, view.parts.size)
        assertEquals(listOf("m1#0", "m1#1", "m1#2"), view.parts.map { it.id })
        assertEquals("第一段", (view.parts[0] as AgentTextPart).text)
        assertEquals("先想一想", (view.parts[1] as AgentReasoningPart).text)
        assertTrue((view.parts[2] as AgentTextPart).streaming)
    }

    @Test
    fun `dynamic tool exposes input output and status`() {
        val view = AgentPartLogic.parseMessage(
            message(
                id = "tool-1",
                parts = arrayOf(
                    """{"type":"dynamic-tool","toolName":"bash","toolCallId":"call-1",
                        "state":"output-available",
                        "input":{"command":"ls -la","description":"List files"},
                        "output":"{\"schema\":\"process.result.v1\",\"status\":\"success\",\"exit_code\":0}"}""",
                ),
            ),
        )
        val tool = view.parts.single() as AgentToolPart
        assertEquals("bash", tool.name)
        assertEquals("call-1", tool.callId)
        assertEquals(ToolCallStatus.OK, tool.status)
        assertEquals("List files", tool.summary)
        assertTrue(tool.inputJson.contains("ls -la"))
        assertTrue(tool.outputText.contains("process.result.v1"))
    }

    @Test
    fun `tool failure wins over state`() {
        val failed = AgentPartLogic.parseMessage(
            message(parts = arrayOf("""{"type":"dynamic-tool","toolName":"read","state":"output-error","errorText":"permission denied"}""")),
        ).parts.single() as AgentToolPart
        assertEquals(ToolCallStatus.FAIL, failed.status)

        val denied = AgentPartLogic.statusOf("output-denied", "", "")
        assertEquals(ToolCallStatus.FAIL, denied)

        // 输出信封里的 status 比 AI SDK state 细：running 不能当成功
        assertEquals(ToolCallStatus.RUNNING, AgentPartLogic.statusOf("output-available", """{"status":"running"}""", ""))
        assertEquals(ToolCallStatus.FAIL, AgentPartLogic.statusOf("output-available", """{"status":"error"}""", ""))
        // 没有信封时按 state 判断
        assertEquals(ToolCallStatus.OK, AgentPartLogic.statusOf("output-available", "plain text", ""))
        assertEquals(ToolCallStatus.RUNNING, AgentPartLogic.statusOf("input-available", "", ""))
    }

    @Test
    fun `image part classifies url kinds`() {
        val view = AgentPartLogic.parseMessage(
            message(
                parts = arrayOf(
                    """{"type":"file","mediaType":"image/png","url":"assets/cover.png"}""",
                    """{"type":"file","mediaType":"image/jpeg","url":"https://example.com/a.jpg"}""",
                    """{"type":"file","mediaType":"image/png","url":"data:image/png;base64,AAA"}""",
                ),
            ),
        )
        val images = view.parts.filterIsInstance<AgentImagePart>()
        assertEquals(3, images.size)
        assertTrue(images[0].isWorkspacePath)
        assertTrue(images[1].isRemote)
        assertTrue(images[2].isInline)
        assertEquals("assets/cover.png", images[0].url)
    }

    @Test
    fun `data parts summarise usage and execution`() {
        val usage = AgentPartLogic.parseMessage(
            message(
                parts = arrayOf(
                    """{"type":"data-agent-token-usage","data":{"total_tokens":60610,"model_calls":2}}""",
                ),
            ),
        ).parts.single() as AgentDataPart
        assertTrue(usage.summary.contains("total_tokens=60610"))
        assertTrue(usage.summary.contains("model_calls=2"))

        val summary = AgentPartLogic.parseMessage(
            message(
                parts = arrayOf(
                    """{"type":"data-agent-execution-summary","data":{"run_status":"completed","duration_ms":30541}}""",
                ),
            ),
        ).parts.single() as AgentDataPart
        assertTrue(summary.summary.contains("completed"))
        assertTrue(summary.summary.contains("duration_ms=30541"))

        val ask = AgentPartLogic.parseMessage(
            message(parts = arrayOf("""{"type":"data-agent-ask","data":{"kind":"tool_approval","status":"answered"}}""")),
        ).parts.single() as AgentDataPart
        assertTrue(ask.summary.contains("tool_approval"))
        assertTrue(ask.summary.contains("answered"))
    }

    @Test
    fun `unknown part is preserved instead of dropped`() {
        val view = AgentPartLogic.parseMessage(
            message(parts = arrayOf("""{"type":"data-agent-browser","data":{"url":"https://x"}}""", """{"type":"something-new","x":1}""")),
        )
        // data-* 一律按事件保留，未知前缀也不丢
        assertTrue(view.parts[0] is AgentDataPart || view.parts[0] is AgentUnknownPart)
        assertTrue(view.parts[1] is AgentUnknownPart)
        assertTrue((view.parts[1] as AgentUnknownPart).rawJson.contains("something-new"))
    }

    @Test
    fun `messages with only unknown parts still render`() {
        val views = AgentPartLogic.parseMessages(
            listOf(
                message(id = "a", parts = arrayOf("""{"type":"text","text":"hi"}""")),
                message(id = "b", parts = arrayOf("""{"type":"something-new"}""")),
            ),
        )
        assertEquals(2, views.size)
        assertEquals("b@1#0", views[1].parts.single().id)
    }

    @Test
    fun `duplicate message ids get unique stable keys`() {
        // 真机里 token_usage 与 execution_summary 共用 run id 作消息 id
        val views = AgentPartLogic.parseMessages(
            listOf(
                message(id = "run-1", parts = arrayOf("""{"type":"data-agent-token-usage","data":{"total_tokens":1}}""")),
                message(id = "run-1", parts = arrayOf("""{"type":"data-agent-execution-summary","data":{"run_status":"completed"}}""")),
            ),
        )
        assertEquals(2, views.size)
        assertEquals(views.map { it.id }.distinct().size, views.size)
        assertEquals("run-1@0", views[0].id)
        assertEquals("run-1@1", views[1].id)
        assertTrue(views.flatMap { it.parts }.map { it.id }.distinct().size == 2)
    }

    @Test
    fun `pretty text truncates with original length`() {
        val long = """{"k":"${"x".repeat(200)}"}"""
        val text = AgentPartLogic.prettyText(long, maxChars = 50)
        assertTrue(text.length > 50)
        assertTrue(text.contains("已截断"))
        assertEquals("", AgentPartLogic.prettyText(""))
        // 非 JSON 原样返回
        assertEquals("plain text", AgentPartLogic.prettyText("plain text", maxChars = 100))
    }

    @Test
    fun `image paths are read from tool output`() {
        val generated = """
            {"schema":"generated_image.v1","status":"success","target_path":"assets/redpanda.png",
             "images":[{"path":"assets/writing/a.jpeg"},{"path":"assets/writing/b.jpeg"}]}
        """.trimIndent()
        assertEquals(
            listOf("assets/writing/a.jpeg", "assets/writing/b.jpeg", "assets/redpanda.png"),
            AgentPartLogic.imagePaths(generated),
        )
        assertEquals(listOf("assets/x.png"), AgentPartLogic.imagePaths("""{"image_path":"assets/x.png"}"""))
        assertTrue(AgentPartLogic.imagePaths("not json").isEmpty())
        assertTrue(AgentPartLogic.imagePaths("").isEmpty())

        val tool = AgentPartLogic.parseMessage(
            message(parts = arrayOf("""{"type":"dynamic-tool","toolName":"generate_image","state":"output-available",
                "output":"{\"schema\":\"generated_image.v1\",\"images\":[{\"path\":\"assets/writing/a.jpeg\"}]}"}""")),
        ).parts.single() as AgentToolPart
        assertEquals(listOf("assets/writing/a.jpeg"), tool.imagePaths)
    }

    @Test
    fun `tool input summary prefers description`() {
        assertEquals(
            "Remove probe file",
            AgentPartLogic.summarizeToolInput("""{"command":"rm -f x","description":"Remove probe file"}"""),
        )
        assertEquals("ls -la", AgentPartLogic.summarizeToolInput("""{"command":"ls -la"}"""))
        assertEquals("a.md", AgentPartLogic.summarizeToolInput("""{"path":"a.md"}"""))
        assertEquals(null, AgentPartLogic.summarizeToolInput("{}"))
        assertFalse(AgentPartLogic.summarizeToolInput("not json").let { it != null })
    }
}
