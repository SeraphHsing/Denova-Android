# AGT-012 上下文与 Todo · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug；服务器 `http://192.168.2.101:18098`，会话 `default`
- 时间：2026-10-25 23:48–23:50

## 1. 入口

Agent 页操作行新增「上下文」按钮（暂停 / 停止 / 上下文 / 阶段标签）。点击即请求
`POST /api/chat/context-analysis`，body 为 `{session_id, message}`，`message` 用与 Web 一致的探针文本
`[Denova context analysis probe]`（服务端 `agentChatTurnRequest` 要求非空），探针不写历史、不触发运行。

## 2. 用量与压缩计划（真机）

会话空闲时点击，日志与面板一致：

```
context analysis tokens=212458 window=1000000 usage=27% compaction=NONE messages=75
```

面板（`context-panel.png`）：

- 用量：`212458 / 1000000 tokens（27%）` —— `token_estimate` / `context_window_tokens` / `context_usage_ratio`
- 消息：`75 条` —— `message_count`
- 压缩：`未触发` —— `compaction_active=false` 且 `would_compact=false`；有压缩时追加
  「压缩前 N → 压缩后 M tokens」与服务端 `compaction.summary`（截断到 6 行）

`compaction_active` / `would_compact` 两个布尔在客户端固化成三态（已生效 / 下一轮会压缩 / 未触发），
避免界面各处各写一套判断。

## 3. 运行占用时的错误路径（真机）

会话处于 `phase=suspended`（服务端仍持有该会话的运行）时点击：

```
context analysis failed
com.denova.novara.core.network.DenovaApiException: agent session is busy
```

面板显示「会话正被当前运行占用，暂停结束或空闲后再看上下文」（`context-busy.png`），不重试、不反复打接口。
把服务端 409 的英文原因换成可照做的提示，是客户端唯一加工。

## 4. Todo 与子 Agent

- Todo 只读展示：从消息流里最后一个 `todo`（兼容 `todo_write` / `todowrite`）工具调用的入参取
  `todos[{step,status}]`，面板底部显示 `待办：3/5 完成` 与逐项 `✓ / ▶ / ○`。客户端**不**维护第二份可写待办，
  写回仍由 Agent 调工具完成（矩阵明确要求）。
- 流式期间入参可能是半截 JSON：解析失败返回空表，取「最后一个非空清单」保证清单不闪空。
- 子 Agent 调用在消息流里按工具部件呈现（工具名 + 状态 + 结果展开），本轮不新增单独的「子 Agent」面板。

本次会话没有 `todo` 工具调用，面板不显示待办区；解析与取用由 `AgentContextTest` 6 例覆盖
（三态压缩、用量百分比、`step`/`content` 两种字段、半截 JSON、工具名变体、最后一份非空清单）。

## 5. 验证

- 主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → BUILD SUCCESSFUL，
  51 suites / 349 tests / 0 failures。
- 真机：§1–§3 在本机完成（含错误路径）；仪器测试未执行。

## 6. 残留

- 压缩计划的「移除压缩」入口未做：Web 有 `removable` 时的删除按钮，对应后端能力本轮未接。
- `system_prompt_parts` / `context_parts` / `context_messages` 未展示（DTO 故意不解析，避免一次拉几万 token 正文）；
  如果后续要做「查看完整上下文」，需要单独接口口径与分页。
