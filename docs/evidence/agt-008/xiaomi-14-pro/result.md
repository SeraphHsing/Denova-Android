# AGT-008 发送结果不明：显示 uncertain 并核对结果，不以新 command 重复发送

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：写作 Agent `session_id=default`
- 对应矩阵：`AGT-008`（Agent 消息与任务 / 发送结果不明；steps「提交后丢失回包再恢复」，expected_ui「显示 uncertain 并查询结果」，expected_server_effect「不以新 command 重复发送」）
- 实现：`feature/agent/AgentViewModel.kt`（`markUncertain` / `reconcileSend`）、`feature/agent/AgentScreen.kt`（uncertain 横幅 + 「重新核对」）、`core/stream/AgentMessageParts.kt`（`messageText`）、`core/persistence/PreferencesStore.kt`（上次会话锚点）
- 断流/断网注入：`tools/stream_cut_proxy.py`（断流）与直接终止代理进程（连接不可达）

## 1. 口径

| 环节 | 规则 |
|---|---|
| 何时判 uncertain | 发送抛异常或 5xx / 无错误码；明确 4xx（除 `agent_runtime.stream_attached`）判 `REJECTED` 直接报错 |
| 核对手段 | 只用只读接口：`GET /api/session/messages?limit=20`（找发送基线之后的用户消息）+ `GET /api/chat/active` |
| 已落地 | `reconcile landed` → 记录 ACCEPTED → 不重发，走 `start()` 重读 canonical 并挂接补结果 |
| 未落地 | `reconcile not landed` → 保留 UNCERTAIN 记录 → 明确告知「不会自动重发」 |
| 重发 | 只有用户手动点「发送」才会产生新 `command_id`，核对路径绝不创建新命令 |

## 2. 未落地分支（连接不可达）

终止代理进程使 `192.168.2.101:18098` 拒绝连接，然后发送长任务：

```text
W Agent: send failed code=null status=0
I Agent: reconcile not landed cmd=4bfa5d90-6f26-47d8-9355-681792ad1610
```

界面：

```text
发送结果不明：核对失败：failed to connect to /192.168.2.101 (port 18098) from /192.168.2.204 (port 46808) after 10000ms   [重新核对]
```

代理恢复后点「重新核对」，横幅更新为结论（无新 POST `/api/chat`，代理日志仅有 `GET /api/session/messages` 与 `/api/chat/active`）：

```text
发送结果不明：服务端未收到该命令；为避免重复回答不会自动重发，请手动重发   [重新核对]
```

## 3. 已落地分支（回包丢失但服务端已收到）

代理对 `POST /api/chat` 截断 1200 字节后 RST，命令实际已落服务端：

```text
W Agent: send uncertain (java.net.SocketException: ...)
I Agent: reconcile landed cmd=0c8dbb5f-47b6-4851-9877-839cc2020eb5
I Agent: start session=default project=project-efb2
I Agent: attach task=... cursor=171 attempt=0
```

核对判定「已收到」后只挂接补结果，未重发；后续流两次中断的恢复见 `docs/evidence/agt-009/xiaomi-14-pro/result.md`。最终 `状态：完成`，正文无重复。

## 4. 结论与缺口

- 通过：两个分支（未落地 / 已落地）真机均按口径表现；核对只读、不重发新命令；横幅文案与「重新核对」入口存在且会随核对结论更新。
- 缺口：核对以「用户消息正文与本次文本相同」判定落地（命令 id 不回显于历史），正文被服务端改写或同一文本重复发送时判定会偏松；进程重启后 `CommandStore` 里的 UNCERTAIN 记录没有恢复入口；不确定性横幅没有「忽略并清除」操作。
