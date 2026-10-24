# AGT-005 暂停与停止：命令目标操作身份与最终状态

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：写作 Agent `session_id=default`
- 对应矩阵：`AGT-005`（Agent 消息与任务 / 暂停与停止；steps「对当前操作暂停或停止」，expected_ui「按钮状态和最终状态对应」，expected_server_effect「target_operation_id 准确」）
- 实现：`app/src/main/java/com/denova/novara/feature/agent/AgentCommands.kt`、`AgentViewModel.kt`、`AgentScreen.kt`、`app/src/main/java/com/denova/novara/core/network/DenovaClient.kt`、`app/src/main/java/com/denova/novara/core/stream/AgentRuntime.kt`
- 单测：`app/src/test/java/com/denova/novara/AgentCommandsTest.kt`（8 用例）

## 1. 协议与实现口径

| 环节 | 依据（后端 `HandleChatCommand` / Web `useAgentChat`） | Android 实现 |
|---|---|---|
| 端点 | `POST /api/chat/commands` | `DenovaClient.submitAgentCommand` |
| 必填 | `session_id` / `type` / `command_id` / `target_operation_id`（空目标 400 `invalid_command`） | 目标为空时**不发命令**，直接提示「当前没有运行中的 Agent」 |
| 目标来源 | `GET /api/chat/active` 的 `active_operation_id` | `AgentActiveView.activeOperationId`（此前 DTO 未声明该字段，等于拿不到目标） |
| 暂停 | `type=suspend` | `AgentCommands.suspend`（不带 reason） |
| 停止 | `type=abort` + `reason=user_requested` | `AgentCommands.abort` |
| 门控 | Web 只在有 `active_operation_id` 时下发 | 按钮 `enabled = activeOperationId.isNotBlank() && !commandSubmitting` |
| 最终状态 | 以 active 投影的 `phase` / `last_operation.status` 为准 | 命令后与流结束后各刷新一次 `refreshActive()`；`abortPending` 在操作消失时清空 |
| 中止不是错误 | — | 流结束时若 `abortPending`，终态记为「完成」而不是「错误」 |

命令同样走「先落本地事务（`CommandStatus.SUBMITTING`）再发送」，失败按 4xx/5xx 记为 `REJECTED` / `UNCERTAIN` 且**不自动重发**（AGENTS §4）。

## 2. 真机验证

### 2.1 空闲时不可暂停

点按「暂停」：无 `control` 日志、无网络请求，按钮处于禁用态（门控生效）。

### 2.2 运行中暂停

发送长任务 `write a 600 word story about a lighthouse keeper` 后，`GET /api/chat/active`：

```text
active=True phase=running active_operation_id=run-20261024T170505.376501103-8ab5a9e15505
```

点「暂停」（`adb logcat -s Agent:*`）：

```text
I Agent: control type=suspend session=default cmd=6e6fdbc8-c1fa-45ec-91e4-eea42422e562 \
         target=run-20261024T170505.376501103-8ab5a9e15505
I Agent: control accepted type=suspend op=run-20261024T170505.376501103-8ab5a9e15505 cursor=20
```

`target` 与 active 投影的 `active_operation_id` 逐字符相同。随后服务端投影：

```text
active=False phase=suspended active_operation_id=run-20261024T170505.376501103-8ab5a9e15505 recovery_paused=True cursor=21
```

界面：`状态：完成`、`暂停`、`已暂停`（`suspended` 标签来自服务端 phase）。

### 2.3 暂停后停止

点「停止」：

```text
I Agent: control type=abort session=default cmd=c09f325c-76ba-48be-931f-dacc3959c311 \
         target=run-20261024T170505.376501103-8ab5a9e15505
I Agent: control accepted type=abort op=run-20261024T170505.376501103-8ab5a9e15505 cursor=22
```

服务端最终状态：

```text
active=False phase=idle active_operation_id=''
last_operation={operation_id: run-20261024T170505…, status: aborted, reason: "Agent Session closed"}
```

### 2.4 运行中直接停止

新任务 `write a 600 word essay about winter` 运行中点「停止」：

```text
I Agent: control type=abort session=default cmd=9a5523d0-c0cc-4d12-9018-8840f805e4d7 \
         target=run-20261024T170644.435988761-58aa3233f043
I Agent: control accepted type=abort op=run-20261024T170644.435988761-58aa3233f043 cursor=28
```

服务端：`active=False phase=idle active_operation_id=''`、`last_operation.status=aborted`；界面 `状态：完成`，按钮回到「停止」（无残留「正在停止…」，见 [stopped-state.png](stopped-state.png)）。

> 修复记录：第一轮 2.3 之后 `abortPending` 不会清空，按钮停在「正在停止…」。改为每次 `refreshActive()` 时 `abortPending = abortPending && 操作身份仍存在`，重新安装后 2.4 复验通过。

### 2.5 负向探针（确认真实错误码）

直接向服务端发命令，验证映射表里的码确实会出现：

| 请求 | 结果 |
|---|---|
| `type=abort` + `target_operation_id=bogus-op`（当前空闲） | `409 agent_runtime.invalid_phase`（`error: 当前没有运行中的 Agent`） |
| `type=abort` + 空 `target_operation_id` | `400 agent_runtime.invalid_command`（三个必填项提示） |

`agent_runtime.target_operation_mismatch` 只在「有活动操作但身份不符」的竞态下出现；客户端始终回传 active 投影里的原值，因此该分支由 `AgentCommandsTest` 覆盖映射。

## 3. 自动化覆盖

`AgentCommandsTest`（8 用例）：active 投影解析 `active_operation_id` / `cursor` / `last_operation`；缺失或空白目标不可控制；
suspend 不带 reason、abort 带 `user_requested`；请求 JSON 的字段名 `session_id` / `type` / `command_id` / `target_operation_id` / `reason`；
错误码文案（`invalid_phase` / `target_operation_mismatch` / `commit_won` / 未知码回退）；`isTargetGone` 只覆盖前两者。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 43 suites / 261 tests / 0 failures。

## 4. 结论与未覆盖

**通过**：暂停 / 停止按钮只在拿到当前操作身份时可用；命令携带的 `target_operation_id` 与 active 投影一致；暂停后服务端 `phase=suspended`、
`recovery_paused=true`，停止后 `phase=idle` 且 `last_operation.status=aborted`；中止不会被显示成错误；本地事务与不自动重发规则一致。

**未覆盖（登记缺口，不改变本条状态）**：

- 暂停后没有「继续」入口（Web `resumeTask` / `recovery_actions`）；当前需重新发消息才能推进。
- `recovery_actions` 驱动的恢复式停止（Web `abortRecovery`）未接，暂停态下直接 abort 已验证可用，但恢复动作本身的语义未验证。
- 停止时的本地 SSE 流不主动断开，等服务器终态自然结束；超时兜底依赖既有 idle guard。
- 服务端记录的 `reason` 为 `Agent Session closed`（非客户端传入的 `user_requested`），客户端不展示 reason 文本，未做差异处理。
- 队列命令（`steer_queued` / `cancel_queued`）属 AGT-006；审批与提问属 AGT-003/004。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
