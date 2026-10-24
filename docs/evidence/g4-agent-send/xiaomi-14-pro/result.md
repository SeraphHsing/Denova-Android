# G4 Agent 写作流：发送与回执真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 会话：`default`
- 对应矩阵：`AGT-002`（发送与回执）

## 目的

`AGT-002` 此前只有 `STATUS.md` 的文字记录；本轮补独立证据文件，覆盖「发送 → 接受 → 流式 → 终态」。

## 步骤与结果

| 步骤 | 观察 |
|---|---|
| 输入 session ID `default` 并点「挂接」 | `Agent: start session=default`，`canonical history loaded len=18`，状态「空闲」 |
| 输入一条最小消息并点「发送」 | `Agent: send session=default cmd=24265bc4-552d-4df7-85f6-14300d2205dd`（先落本地命令事务再发送） |
| 流式阶段 | 界面「状态：运行中」，回复文本随 `text-delta` 逐步出现 |
| 终态 | 界面「状态：完成」 |
| 服务端核对 | `GET /api/chat/active?session_id=default` → `phase=idle`、`status=done`、`stream_cursor=6`、`recovery_actions=null` |

界面文本（`uiautomator dump` 导出）：

```text
状态：完成
> 重新恢复默认creator.md
pong！我在，随时可以为您提供帮助。请问有什么需要处理的吗？
```

## 覆盖范围与限制

- 覆盖：发送前落本地命令事务（`command_id` 可见于日志）、首帧接受、流式累积、`done` 终态与服务端状态一致。
- **未覆盖**：审批（`AGT-003`）、提问（`AGT-004`）、暂停/停止与队列控制（`AGT-005`–`AGT-007`）、结果不明后的对账恢复（`AGT-008`）。
- 截图 `g4-agent-send.png` 含会话正文，仅本地保留，不入库。
