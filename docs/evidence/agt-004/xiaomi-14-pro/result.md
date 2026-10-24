# AGT-004 提问交互：选项答案、取消原因与恢复页面

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：写作 Agent `session_id=default`
- 对应矩阵：`AGT-004`（Agent 消息与任务 / 提问交互；steps「回答取消再恢复页面」，expected_ui「选项答案及已解决状态对应」，expected_server_effect「answers 和取消原因对应」）
- 实现：`app/src/main/java/com/denova/novara/core/stream/AgentAsk.kt`、`app/src/main/java/com/denova/novara/core/stream/AgentRuntime.kt`、`app/src/main/java/com/denova/novara/core/network/DenovaClient.kt`、`app/src/main/java/com/denova/novara/feature/agent/AgentViewModel.kt`、`AgentScreen.kt`
- 单测：`app/src/test/java/com/denova/novara/AgentAskTest.kt`（10 用例）

## 1. 契约与实现口径

| 环节 | 依据 | Android 实现 |
|---|---|---|
| 实时提问 | 流中 data 部件 `data-agent-ask`（`agentui.DataTypeAsk`），payload 为 `ask.pending.v1` | `AgentViewModel.handleAskChunk` 解析 `AgentUiChunk.data` |
| 恢复提问 | `GET /api/chat/active` 的 `pending_asks` | `refreshActive()` 同步 `pendingAsks`；`start()` 挂接前先刷新 |
| 回答 | `POST /api/session/asks/:ask_id/answer` body `{session_id, answers[{question_id, selected_option_ids}]}` | `DenovaClient.answerSessionAsk` |
| 取消 | `POST /api/session/asks/:ask_id/cancel` body `{session_id, reason}`（默认 `user_cancelled`） | `DenovaClient.cancelSessionAsk` |
| 选项语义 | `multi_select` 单选覆盖 / 多选切换，`recommended_option_id` 标注推荐 | `AgentAskLogic.toggle` |
| 提交前置 | 每个问题至少选中一项，否则不发请求 | `AgentAskLogic.canSubmit` |
| 已解决态 | 以接口返回的 resolution 为准（`status` / `answers` / `cancel_reason`） | `AgentAskLogic.applyResolution` + `statusText` |

## 2. 真机验证

### 2.1 实时下发与卡片渲染

发送 `call the ask tool with one question: which genre? options: fantasy or scifi. do not write anything else.` 后：

```text
I Agent: send session=default cmd=65f05c07… refs=1 selections=0
I Agent: ask pending id=ask-tool-ca5a6e02b04078534108b7afd6f4d1b9 questions=1
```

卡片（[ask-card.png](ask-card.png)）：问题 `Which genre?`、`Fantasy（推荐）` + 描述 `Fantasy genre`、`Sci-Fi` + `Science Fiction genre`、`提交` / `取消`。

### 2.2 回答

选 `Sci-Fi` → `提交`：

```text
I Agent: ask answered id=ask-tool-ca5a6e02b04078534108b7afd6f4d1b9 status=answered answers=1
```

服务端投影与历史读回：

```text
active=… phase=running pending_asks=None open_tools=[]
ASK ask-tool-ca5a6e02… status=answered
    answers=[{"question_id":"genre","question":"Which genre?",
              "selected_options":[{"id":"scifi","label":"Sci-Fi"}]}]
```

界面：卡片消失，出现 `已回答：Sci-Fi`。服务端 run 随之继续（提问不会挂住运行）。

### 2.3 取消

再触发一次提问（`ask pending id=ask-tool-c1c63bbf…`）→ 点 `取消`：

```text
I Agent: ask cancelled id=ask-tool-c1c63bbf594114ae27e67d97b967679e status=cancelled reason=user_cancelled
I Agent: ask resolved  id=ask-tool-c1c63bbf594114ae27e67d97b967679e status=cancelled
```

服务端历史：`status=cancelled`。界面（[cancelled-state.png](cancelled-state.png)）：`已取消（user_cancelled）`。

> 基线行为（记录，非缺陷）：`cancel_reason` 未落到服务的 ask 快照（`cancel_reason=null`），取消原因只用于运行时的 resolve 调用；
> Web 端的取消体同样是 `{reason:'user_cancelled'}`，落库表现一致。客户端保留自己提交的原因作为文案来源。

### 2.4 恢复页面

第一轮提问未作答时执行 `install -r` + `force-stop` 重启，再从编辑器进入写作 Agent：

```text
I Agent: start session=default
I Agent: canonical history loaded len=…
# 随后 refreshActive() 用 pending_asks 重新渲染卡片
```

卡片完整重现（同一 `ask-tool-ca5a6e02…` 的选项、描述、推荐标记、提交/取消），随后才被回答。这覆盖了「回答取消再恢复页面」的另一半：
未解决的提问不会因为离开页面或进程重建而丢失。

## 3. 开发中修复的两个缺陷

1. **部件类型写错**：初版按 `data-ask` 匹配，实际是 `data-agent-ask`（`agentui.DataTypeAsk`），导致流内提问完全不显示；修正后 2.1 的日志才出现。
2. **流内 resolved 部件覆盖本地详情**：`ask_resolved` 只带状态，直接替换会把本地已有的答案 / 取消原因抹掉（首轮显示为 `已取消` 而非 `已取消（user_cancelled）`）。
   改为 `AgentAskLogic.merge`：新值优先、缺失沿用旧值；单元测试覆盖，真机复验见 2.3。

## 4. 自动化覆盖

`AgentAskTest`（10 用例）：`ask.pending.v1` 解析（含 `recommended_option_id` / `multi_select` / `allow_other`）、单选覆盖与清除、多选切换、
逐题必答的提交门控、答案体顺序与空答案跳过、答案体字段名、取消原因字段名、resolution 落状态与文案、merge 保留本地答案与原因、错误码文案与 `needsRefresh`。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 44 suites / 271 tests / 0 failures。

## 5. 结论与未覆盖

**通过**：提问从流实时出现；选项与推荐项按服务端问句渲染；回答体的 `question_id` / `selected_option_ids` 与服务端记录一致；
取消带 `user_cancelled` 并如实显示；已解决卡片不再提交；离开页面 / 进程重建后未答提问从 `pending_asks` 恢复。

**未覆盖（登记缺口，不改变本条状态）**：

- `allow_other` 的自定义输入（`custom_input`）未做，目前只能选选项。
- 多问题、多选（`multi_select=true`）已实现逻辑与单测，但真机只覆盖了单问题单选。
- 工具审批类提问（`kind=tool_approval`、`approval` 字段、记忆规则）属 AGT-003，未做。
- 多端并发回答同一提问（`ask_stale` / `ask_conflict`）只在单测层面覆盖错误码文案，未真机构造。
- 提问历史卡片在消息流中的内联展示未做：已解决状态只在当前页以文字行展示（重启后不回溯展示历史问答）。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
