package com.denova.novara.core.network

import com.denova.novara.core.terminal.TerminalControl
import com.denova.novara.core.terminal.TerminalFrame
import com.denova.novara.core.terminal.TerminalPerf
import com.denova.novara.core.terminal.TerminalSessionInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/**
 * 一次终端 attach 连接。
 *
 * 帧契约（见后端 `serveTerminalSocket`）：
 * - 服务端 → 客户端：binary = 原始 PTY 字节；text = `ready` / `exit` / `error` / `pong`
 * - 客户端 → 服务端：binary = 原始输入；text = `input` / `resize` / `ping`
 *
 * 用 [Channel] 把回调式 WebSocket 桥接成冷流；容量有界，避免高速输出时无界堆积。
 */
class TerminalConnection internal constructor(private val json: Json) {

    private val channel = Channel<TerminalFrame>(capacity = 256)

    /** 帧流（单消费者：由 ViewModel 收集后喂给 ANSI 解析器）。 */
    val frames: Flow<TerminalFrame> = channel.receiveAsFlow()

    private var webSocket: WebSocket? = null

    internal fun bind(socket: WebSocket) {
        webSocket = socket
        // 用户可能在握手完成前就离开页面：此时 channel 已关闭，socket 必须立刻释放，
        // 否则会留下一个没人读的 WebSocket 与后端 relay goroutine。
        if (channel.isClosedForSend) socket.close(NORMAL_CLOSURE, "client closed")
    }

    internal fun onTextMessage(text: String) {
        val frame = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "ready" -> TerminalFrame.Ready(
                    obj["session"]?.let {
                        runCatching {
                            json.decodeFromJsonElement(TerminalSessionInfo.serializer(), it)
                        }.getOrNull()
                    },
                )
                "exit" -> TerminalFrame.Exit(
                    obj["code"]?.jsonPrimitive?.intOrNull ?: 0,
                    obj["error"]?.jsonPrimitive?.contentOrNull,
                )
                "error" -> TerminalFrame.Error(
                    obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown terminal error",
                )
                "pong" -> TerminalFrame.Pong
                else -> null
            }
        }.getOrNull()
        frame?.let { channel.trySend(it) }
    }

    internal fun onBinaryMessage(bytes: ByteArray) {
        // 通道满时丢弃是最坏情况：必须计入积压指标，否则「看起来没延迟」只是丢帧掩盖
        val queued = channel.trySend(TerminalFrame.Output(bytes)).isSuccess
        TerminalPerf.onFrame(bytes.size, queued)
    }

    internal fun onFailure(message: String) {
        channel.trySend(TerminalFrame.Error(message))
        channel.close()
    }

    /** 服务端 / 网络关闭：把 close code 交给上层判定是否重连（1000 也可能意味着会话被回收）。 */
    internal fun onClosed(code: Int, reason: String) {
        channel.trySend(TerminalFrame.Closed(code, reason))
        channel.close()
    }

    fun input(bytes: ByteArray) {
        webSocket?.send(bytes.toByteString())
    }

    fun input(text: String) = input(text.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int) {
        sendControl(TerminalControl(type = "resize", cols = cols, rows = rows))
    }

    fun ping() {
        sendControl(TerminalControl(type = "ping"))
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSURE, "client closed")
        channel.close()
    }

    private fun sendControl(control: TerminalControl) {
        webSocket?.send(json.encodeToString(TerminalControl.serializer(), control))
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
