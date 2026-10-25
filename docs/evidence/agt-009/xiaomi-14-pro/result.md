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
| 断线判定 | 读流异常 → 重读 canonical + 重挂，最多 8 次；**干净 EOF 只要缺完成标记（`[DONE]` / finish / abort / error）同样按断线处理** | `transferFailures` 与 active 探测共用一个预算，超限 `stream retry exhausted`；`AgentUiFrame.completesTurn()` 统一判定 |
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

> 上表的 `limit=20` + `limit=50` 两次拉取是当时的实现；2026-10-25 已合并为一次，见 3.4。

### 3.4 发送路径就地挂接：核对与重建共用一次历史

原先发送被截断后的恢复是一次「二次跳转」：`reconcileSend()`（20 条判定）→ `start()`（重置页面状态）→ 50 条重建 → 挂接。改法：

- 核对改为按 canonical 页大小（`CANONICAL_LIMIT = 50`）拉**一次**，落地判定与正文重建共用这次响应（`applyCanonical`）；
- 抽 `attachLoop()`（`refreshActive` + coordinator `recover`）供 `start()` 与「核对已落地」共用，发送路径在**同一个 job 内**核对 → 重建 → 挂接，不再重置页面状态、不再二次全量拉取；
- 落地判定抽成 `AgentPartLogic.hasLandedMessage` 并补单测（只看基线之后的用户消息；基线不在本页时退回最后一条）。

`--cuts 2 --cut-mode close`（FIN 首响应 + FIN 重挂各一次）实测请求序列：

```text
21:12:08 POST /api/chat -> 200
21:12:08 cut stream /api/chat after 1200 bytes (cuts left=1)
21:12:10 GET /api/session/messages?session_id=default&limit=50     ← 核对+重建（仅一次）
21:12:11 GET /api/chat/active?session_id=default                   ← refreshActive
21:12:11 GET /api/chat/stream?...&after=90 -> 200                  ← 就地挂接
21:12:11 cut stream ... (cuts left=0)                              ← 第二次断流
21:12:11 GET /api/session/messages?...&limit=50                    ← coordinator 重读
21:12:13 GET /api/chat/stream?...&after=302 -> 200                 ← 再次重挂
I Agent: reconcile landed cmd=...; resume without refetch
```

「重新核对」按钮走同一套 `reconcile` + `attachLoop`：代理断开时发送得到 `核对失败`，恢复后点按钮只发一次 `GET /api/session/messages?limit=50`，结论为 `reconcile not landed` → 横幅「服务端未收到该命令；为避免重复回答不会自动重发，请手动重发」，且不误触发挂接。


终态：`状态：完成`，正文完整显示，`agent-execution-summary · completed`，无重复段落（每次重挂前 `reloadCanonical` 清空流式正文，历史由 canonical 重建）。

## 4. 干净 EOF 判定（`--cut-mode close`）

FIN 断开不会抛异常，客户端读到的是一次「正常结束」。判定依据是完成标记而不是异常：服务端收尾固定写 `finish`（中止 `abort`、失败 `error`）再写 `data: [DONE]`，所以「`[DONE]` 与 finish/abort/error 都没出现就结束」等于被切断。实现为 `AgentUiFrame.completesTurn()`（`Done` 或终态 chunk），coordinator 与发送路径共用。

`--cuts 2 --cut-mode close`（首响应 + 重挂各截断一次）实测：

```text
20:46:20 POST /api/chat -> 200
20:46:20 cut stream /api/chat after 1200 bytes (cuts left=1)
20:46:22 stream cut after 1200 bytes mode=close
20:46:22 GET /api/session/messages?session_id=default&limit=20     ← 核对
20:46:23 GET /api/chat/stream?session_id=default&task_id=...-22&after=154
20:46:23 cut stream ... after 1200 bytes (cuts left=0)
20:46:24 GET /api/chat/stream?session_id=default&task_id=...-22&after=351 -> 200
I Agent: stream ended without completion marker; reconcile instead of resend
I Agent: reconcile landed cmd=d2daa4eb-7e50-4110-96b9-3967704458de
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-22 cursor=154 attempt=0
W Agent: stream interrupted (attempt 1/8); rehydrate + reattach
W Agent: ...Coordinator$TruncatedAgentStream: agent stream ended without completion marker
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-22 cursor=351 attempt=1
```

终态 `状态：完成`。即：干净 FIN 现在与 RST 走同一条恢复路径，而不是「静默停在半截 + 状态：错误」。

## 4.5 冷恢复动作（`recovery_actions`）与 active 去重

服务端投影在 `suspended` 时给 `resume` + `abort`，`running` 时给 `start_turn`。`start_turn` 由挂接路径自动消费、不做按钮（重复提交会与当前 display owner 冲突，与 Web `recoveryActionsToSubmit` 同口径）；`resume` / `abort` 只能由用户点按。客户端只把投影原样给出的 `action_id` / `command_id` / `operation_id` 回传 `POST /api/chat/recovery`，不自己拼身份、不另发新命令。

暂停后的界面文本：

```text
状态：完成
已暂停
这次运行在服务端已暂停   [继续] [中止]
```

「继续」（`kind=resume`）：

```text
21:45:39 POST /api/chat/recovery -> 202
21:45:39 GET /api/chat/stream?session_id=default&task_id=...-2e&after=0 -> 200
I Agent: recovery resume ok task=task-bb2660c3de54acbb0d39a661d49d9494-2e
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-2e cursor=0 attempt=0
```

「中止」（`kind=abort`）：

```text
21:44:58 POST /api/chat/recovery -> 202
21:44:59 GET /api/chat/stream?session_id=default&task_id=...-2c&after=0 -> 200
21:44:59 GET /api/chat/active          ← finishStream 的按钮门控刷新
I Agent: recovery abort ok task=task-bb2660c3de54acbb0d39a661d49d9494-2c
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-2c cursor=0 attempt=0
```

中止后直接用回执的 `task_id` 挂接，不再先探测 active：中止一旦落地，服务端常常已清空 active，探测会判成 settled，导致「已停止」的收尾内容读不到。

active 去重：`refreshActive()` 拿到的投影作为 `recover(sessionId, seed = …)` 的首轮输入，挂接前只探测一次（改动前同一时刻会连续发两次 `GET /api/chat/active`）；恢复动作则有回执身份可用，探测次数为 0。

观察（未改）：暂停 / 中止后状态行显示「完成」（终端帧为 `abort` → DONE），与旁边的「已暂停」并列时略显歧义，属阶段标签口径问题，留待 AGT-005 复核。

## 5. 流级错误原因与放弃恢复后的兜底文案

`--inject-error`（在截断点改注入一条 `error` 帧并以 `[DONE]` 收尾）：

```text
20:49:24 inject error frame at 1200 bytes: model-overloaded-injected
```

界面（`uiautomator` 文本）：`状态：错误` + `model-overloaded-injected`，且**没有**触发核对/重挂（`error` 属终态帧，客户端不会在明确的错误上打转）。

恢复预算耗尽（`--cuts 20`，连续失败超过 8 次）时：

```text
W Agent: stream retry exhausted after 9 failures session=default
```

界面：`状态：错误` + `流在完成前中断，结果可能不完整`（无服务端 `errorText` 时的兜底说明）。

## 6. 结论与剩余缺口

- 通过：附接 → 断流 → 重读 canonical → 重挂 → 再断流 → 再重挂 → 完成，全程无重复正文，命令不重复下发；发送回包丢失时不自动重发，核对结论以服务端 canonical 历史为准。
- 通过：干净 EOF（FIN）按「缺完成标记」判为截断，发送路径与挂接路径都进入恢复链；流级错误原因与「放弃恢复」的兜底文案都渲染在状态行下方。
- 缺口：
  1. 终态判定把 `error` 帧当完成：若服务端在 `error` 之后仍继续产出内容，客户端不会重挂（按当前后端收尾行为这是正确取舍，协议变化时需复核）。
  2. 暂停 / 中止后状态行仍显示「完成」（终端帧 `abort` → DONE），阶段标签口径待 AGT-005 复核。
  3. 中止后的收尾内容依赖回执 `task_id` 挂接；若回执不带 `task_id` 则退回 active 探测，此时可能判成 settled（当前后端 202 必带 `task_id`）。
