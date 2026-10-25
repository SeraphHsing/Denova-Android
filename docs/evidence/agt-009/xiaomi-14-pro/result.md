# AGT-009 流二次中断：附接断线重连后再次断线仍能恢复

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：写作 Agent `session_id=default`
- 对应矩阵：`AGT-009`（Agent 消息与任务 / 流二次中断；steps「附接断线重连后再次断线」，expected_ui「文本不重复且能恢复」，expected_server_effect「重读 canonical 后选择 handoff」）
- 实现：`core/stream/AgentStreamRecoveryCoordinator.kt`、`core/stream/AgentRuntime.kt`、`core/network/DenovaClient.kt`、`core/network/AgentStreamUrls.kt`、`feature/agent/AgentViewModel.kt`
- 单测：`app/src/test/java/com/denova/novara/AgentRecoveryTest.kt`、`AgentStreamUrlTest.kt`
- 断流注入：`tools/stream_cut_proxy.py`

## 1. 断流注入方式

代理监听 `0.0.0.0:18098`，转发到真实服务器 `http://192.168.2.102:18081`，App 的服务地址即 `192.168.2.101:18098`，因此 `/chat` 族请求全部经过代理。

```text
python tools/stream_cut_proxy.py --listen 0.0.0.0:18098 --target http://192.168.2.102:18081 \
    --path /chat --cut-after-bytes 1200 --cuts 2 --cut-mode reset --log %TEMP%\cut-proxy.log
```

`--path /chat` 同时命中 `POST /api/chat`（首响应流）与 `GET /api/chat/stream`（重挂流）；`/api/chat/active`、`/api/chat/commands` 等 JSON 端点因非 `text/event-stream` 不截断。`--cut-mode` 的两种断开方式客户端表现不同，本次两种都测了：

| 模式 | 断开方式 | 客户端读到的结果 |
|---|---|---|
| `close` | 写完半帧后 FIN | 干净 EOF（无异常） |
| `reset` | `SO_LINGER=0` 强制 RST | 读流抛异常（真断网形态） |

## 2. 协议与实现口径

| 环节 | 依据（后端 `HandleChatStream` / Web `AgentChatTransport.reconnectToStream`） | Android 实现 |
|---|---|---|
| 重挂端点 | `GET /api/chat/stream` | `writingAgentStreamUrl` |
| 必需参数 | `session_id`（缺失 400 `agent_runtime.invalid_binding`）+ `task_id`（缺失 400） | 两者必带（**本次修复点**） |
| 任务匹配 | `ActiveTaskForSession(session) != task_id` → 409 `agent_runtime.rehydrate_required` | 走 rehydrate 分支：重读 canonical 后重挂 |
| 游标 | `after`（十进制 uint64，取自 active 的 `stream_cursor`）；Web 只在「规范历史恢复后的首个连接」带 | 每次重挂前 `reloadCanonical()`，再带最新 `stream_cursor` |
| 断线判定 | 读流异常 → 重读 canonical + 重挂，最多 8 次 | `transferFailures` 与 active 探测共用一个预算，超限 `stream retry exhausted` |
| 文本不重复 | Web 按 part 稳定身份合并 | `reloadCanonical` 重建消息部件并清空流式正文（`ui.content` / `ui.thinking`），重挂后的增量接在历史之后 |

## 3. 实测结果

### 3.1 发送回包完全丢失（连接不可达）：判 uncertain 且不重发

代理进程被杀后发送长任务，`POST /api/chat` 直接连接失败：

```text
W Agent: send failed code=null status=0
I Agent: reconcile not landed cmd=4bfa5d90-6f26-47d8-9355-681792ad1610
```

界面：

```text
状态：完成
发送结果不明：核对失败：failed to connect to /192.168.2.101 (port 18098) ... after 10000ms   [重新核对]
```

代理恢复后点「重新核对」，结论正确落到「未落地」并拒绝自动重发：

```text
发送结果不明：服务端未收到该命令；为避免重复回答不会自动重发，请手动重发   [重新核对]
```

### 3.2 修复前：重挂地址缺 `session_id`，恢复链死在第一步

`clean close` 断流（`POST /api/chat` 截断 1200 字节后 FIN），客户端把干净 EOF 当作正常结束：没有重挂，`finishStream` 把仍为 RUNNING 的阶段置成「错误」，而服务端任务实际跑完（`agent-execution-summary · completed duration_ms=45938`）。手动点「挂接」后：

```text
20:23:09 GET /api/chat/stream?task_id=task-bb2660c3de54acbb0d39a661d49d9494-20&after=131 -> 400
```

后端 `handler_chat.go::HandleChatStream` 第一步就是 `requiredWritingSessionID(c, c.Query("session_id"))`，缺参直接 400 `agent_runtime.invalid_binding`；客户端 `agentStreamUrl` 只拼了 `task_id`/`after`。coordinator 又把该 400 归入「非 rehydrate」并静默 `return@flow`，于是界面从「错误」静默回到「空闲」、正文尾部丢失、无任何失败提示——恢复能力等于全程不可用。

### 3.3 修复后：两次断流后仍恢复并完成

修复 `agentStreamUrl` → `writingAgentStreamUrl(baseUrl, sessionId, taskId, after)`（`AgentStreamTransport.agentStream` 增加 `sessionId`，coordinator 用 `recover(sessionId)` 的值），重新安装后同一场景：

```text
20:30:26 POST /api/chat -> 200                                     ← 首响应
20:30:26 cut stream /api/chat after 1200 bytes (cuts left=1)        ← 第一次断流
20:30:27 stream cut after 1200 bytes mode=reset
20:30:28 GET /api/session/messages?session_id=default&limit=20      ← 核对回包
20:30:28 GET /api/session/messages?session_id=default&limit=50      ← 重读 canonical
20:30:29 GET /api/chat/stream?session_id=default&task_id=...-21&after=171 -> 200   ← 重挂（带 session_id）
20:30:29 cut stream ... after 1200 bytes (cuts left=0)              ← 第二次断流
20:30:29 GET /api/session/messages?session_id=default&limit=50      ← 再次重读 canonical
20:30:31 GET /api/chat/stream?session_id=default&task_id=...-21&after=411 -> 200   ← 再次重挂，透传至结束
```

客户端日志：

```text
I Agent: send session=default cmd=0c8dbb5f-47b6-4851-9877-839cc2020eb5 refs=0 selections=0
I Agent: reconcile landed cmd=0c8dbb5f-47b6-4851-9877-839cc2020eb5
I Agent: canonical parts loaded messages=27 tools=5 session=default
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-21 cursor=171 attempt=0
W Agent: stream interrupted (attempt 1/8); rehydrate + reattach
I Agent: canonical parts loaded messages=27 tools=5 session=default
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-21 cursor=411 attempt=1
```

终态：`状态：完成`，正文完整显示，`agent-execution-summary · completed`，无重复段落（每次重挂前 `reloadCanonical` 清空流式正文，历史由 canonical 重建）。

## 4. 结论与剩余缺口

- 通过：附接 → 断流 → 重读 canonical → 重挂 → 再断流 → 再重挂 → 完成，全程无重复正文，命令不重复下发；发送回包丢失时不自动重发，核对结论以服务端 canonical 历史为准。
- 缺口（本次未改，需单独定案）：
  1. **干净 EOF 不判为中断**：`collectWithIdleGuard` 正常返回后，只要没有 terminal 帧就只看 `finishStream` 的 RUNNING→ERROR 兜底，既不重挂也不提示「可恢复」；`reset` 形态（抛异常）才会进入恢复链。要覆盖需按「未收到 `finish`/`abort` 帧」判定截断。
  2. **流级错误原因不显示**：`AgentUiState.errorText`（`error` 帧的 `errorText`）没有任何渲染点，用户只看得到「状态：错误」。
  3. 发送路径的断流恢复依赖「核对 → `start()` → 重挂」二次跳转，`sendJob` 本身不会重挂；本场景验证通过，但多一次 canonical 全量拉取。
