# P3 写作 Agent 链路（从编辑器进入 + 自动会话）：真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建，serial `135babde`）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 对应：P3 链路「进入 Agent → 发送」；矩阵参考 `AGT-002`（创建会话与发送，已完成）
- 实现：`EditorScreen` 顶栏「写作」按钮（带 `projectId` + `filePath`）、`MainActivity` 导航 `agent?projectId=&filePath=`、`AgentScreen` 接收参数 + `LaunchedEffect` 自动 `ensureSession`、`AgentViewModel.ensureSession()`（`GET /api/sessions` 取 active / 首个 / 创建）、`DenovaClient.sessionList/createSession`、`SendChatRequest.references`

## 验证步骤与结果

1. 左抽屉选「灵渊劫-问道」：`BookShelf: switched name=灵渊劫-问道 project=project-efb2… workspace=/data/.denova/projects/1`。
2. 编辑器点「写作」按钮 → 进入 Agent：
   - `Agent: ensureSession picked=default active=true`（自动选中服务端当前 active 会话）
   - `Agent: start session=default`
   - `Agent: canonical history loaded len=926`（自动恢复历史）
   - session 输入框自动回填 `default`（无需手动输入）
3. 输入消息并点「发送」：`Agent: send session=default cmd=f559990e-… refs=0`（命令身份生成、发送链路正常）。

## 结论

从编辑器进入写作 Agent、自动选取当前会话、恢复历史、发送：**真机通过**。

## 未覆盖 / 缺口

- **`refs=1`（带文件引用）**：需先打开文件再进 Agent；本次 `adb input text` 长串项目 ID 出现字符错乱、右抽屉按钮定位偏差（误触退出），未能稳定构造。代码已支持（`SendChatRequest.references` + `AgentScreen` 传 `initialFile`），由代码审查确认。
- **工具结果结构化展示**：当前仍为 `text-delta` 累积，`data-agent-*` 的 tool result / workspace change 未解析成独立卡片。
- **变更审阅**（`changes/*`）、**版本入口**未做。
- **会话列表 / 新建 / 切换 UI** 未做（当前仅自动 `ensureSession` 选 active/首个/创建）。
