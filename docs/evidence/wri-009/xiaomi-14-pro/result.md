# WRI-009 选区引用：从编辑器选段到 Agent 提交

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `g3d-a.md`（26 行，233 字符）
- 对应矩阵：`WRI-009`（写作编辑 / 选区引用；steps「选段引用到 Agent 并提交」，expected_ui「引用卡和定位对应」，expected_server_effect「file_name 行号内容映射准确」）
- 实现：`app/src/main/java/com/denova/novara/feature/editor/EditorScreen.kt`、`app/src/main/java/com/denova/novara/feature/editor/TextSelectionQuote.kt`、`app/src/main/java/com/denova/novara/feature/agent/AgentViewModel.kt`、`app/src/main/java/com/denova/novara/feature/agent/AgentScreen.kt`、`app/src/main/java/com/denova/novara/core/network/DenovaClient.kt`
- 单测：`app/src/test/java/com/denova/novara/TextSelectionQuoteTest.kt`（7 用例）

## 1. 对齐 Web 基线的口径

| 环节 | Web 基线 | Android 实现 |
|---|---|---|
| 触发 | 选区浮动工具栏「引用到 AI」（`editor.quoteSelection`） | 选区非空时在模式行下方出现「引用到 AI」按钮 + `file:Lx-Ly` 定位 |
| 结构 | `TextSelection { fileName, startLine, endLine, content }` | `TextSelectionRef`（同四字段，1 基行号） |
| 行号 | `getLineNumber(doc, pos)`，块序号 1 基 | `TextSelectionQuote.lineAt`（按 `\n` 计数，1 基） |
| 空内容 | `text.trim()` 为空则不产生引用 | `content.isBlank()` 返回 null，不显示按钮 |
| 线上字段 | `selections: [{file_name,start_line,end_line,content}]` | `SendChatRequest.selections`（同名字段） |
| 引用卡 | `file:Lx[-Ly] 内容前 30 字…`，可 `×` 移除 | 同款文案（`TextSelectionQuote.label` / `excerpt`），点击卡片移除 |
| 清空时机 | 发送时清空 composer | 服务端接受（首帧）后清空；失败时保留以便原样重发 |

## 2. 真机步骤与结果

### 2.1 全选引用（定位 1-26 行）

编辑器选中全文 → 出现「引用到 AI」与 `g3d-a.md:L1-L26`（[editor-quote-action.png](editor-quote-action.png)）→ 点击进入写作 Agent，引用卡为
`g3d-a.md:L1-L26 # G3 并发测试文件… ×`（[agent-selection-chip.png](agent-selection-chip.png)）：

```text
I Agent: selection added file=g3d-a.md lines=1-26 chars=233
I Agent: send session=default cmd=357ee832… refs=1 selections=1
```

服务端读回（`GET /api/session/messages?limit=20`，用户消息 `metadata.user_references`）：

```json
{"kind":"file","label":"g3d-a.md"}
{"kind":"selection","label":"g3d-a.md","start_line":1,"end_line":26,"detail":"# G3 并发测试文件\n\n第 1 行……第 12 行：原始内容（不可改）。"}
```

- `start_line` / `end_line` = 1 / 26，与文件 26 行完全一致；
- `detail` 与「文件正文去掉末尾换行」逐字节相等（文件以 `\n` 结尾，选中不含末尾换行）。

### 2.2 局部选区引用（定位 2-4 行）

光标落在第 2 行开头，`Shift+↓` 两次 → 「引用到 AI」显示 `g3d-a.md:L2-L4`，引用卡为
`g3d-a.md:L2-L4 ⏎第 1 行：原始内容（不可改）。⏎ ×`；发送后服务端读回：

```json
{"kind":"selection","label":"g3d-a.md","start_line":2,"end_line":4,"detail":"第 1 行：原始内容（不可改）。"}
```

文件第 2-4 行原文为 `"" / "第 1 行：原始内容（不可改）。" / ""`，与 `detail` 一致。

### 2.3 引用卡移除

点击引用卡：`I Agent: selection removed index=0 left=0`，输入区引用卡消失，未发送。

### 2.4 基线的 detail 规范化（记录，不是缺陷）

服务端 `sanitizeUserMessageReferences`（`internal/agents/session/session.go`）对每条引用做
`strings.TrimSpace(detail)` 与 2048 字节截断，标签 1024 字节截断，单条引用按总字节上限裁剪。
因此 `detail` 会去掉首尾空白（本次两条引用都是这样），Web 端同样如此；`start_line` / `end_line` 保持原值。
超长选区（>2048 字节）的 `detail` 会被截断，行号仍准确——属于固定基线行为。

## 3. 自动化覆盖

`TextSelectionQuoteTest`（7 用例）：行号 1 基与越界归位、单行选区、跨行选区、反向选区归一、
空选区/纯换行选区不产生引用、`file:Lx` 与 `file:Lx-Ly` 文案、30 字摘要截断。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 42 suites / 253 tests / 0 failures。

## 4. 结论与未覆盖

**通过**：编辑器选段 → 「引用到 AI」→ Agent 引用卡（文案与行号对应）→ 随消息提交 → 服务端 `user_references`
文件名 / 行号 / 内容映射与文件一致；引用卡可移除且不发送；服务端接受后自动清空。

**未覆盖（登记缺口，不改变本条状态）**：

- 选区浮动工具栏（Web 在选区上浮出小工具栏）；Android 暂为固定位置的「引用到 AI」按钮。
- 富文本模式（`MarkdownVisualTransformation`）下的选区与源码模式共用同一 `TextFieldValue`，但只在源码/富文本模式验证，预览模式不支持选段。
- 多引用同时提交、引用去重（同一区间重复引用会被忽略）之外的冲突处理、引用卡编辑（改行号范围）未做。
- 超长选区（>2048 字节）在服务端 detail 被截断后的表现未在 UI 侧提示。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
