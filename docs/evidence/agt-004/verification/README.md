# AGT-004 补验：工具副作用验证（verification）

日期：2026-10-30
状态：**契约与界面已实现并经 JVM 单测锁定；真机端到端未执行（环境阻塞，见第 4 节）**

## 1. 这是什么

后端在「有副作用的工具已经开始执行、但结果没有落盘（进程中断）」时，运行恢复会补一个交互，
让用户确认这次调用到底有没有生效。它既不是模型提问，也不是工具审批：

- 触发：`agent/lifecycle/lifecycle_tool_facts.go` 的 `restoreEffectInteractions()`
  —— `fact.Started && fact.Result == nil && Descriptor.MutationScope != none`
- 标识：交互 id = `verify-<callId>`，问题 id = `effect`，选项 = `executed` / `not_executed` / `unknown`
- 证据：`verification{execution_id, tool, arguments}`（`agent/lifecycle/interaction/interaction.go`）
- 落定：`internal/agents/execution/public_interaction.go` 只接受 `executed` / `not_executed`；
  `unknown`、取消或多答案返回 `status=pending`，任务继续等待

## 2. 客户端实现（对齐 Web `AskInteractionCard`）

| 项 | 实现 |
|---|---|
| 契约 | `core/stream/AgentAsk.kt`：`AgentToolEffectVerification(executionId, tool, arguments: JsonElement?)`，`AgentAskInteraction.verification` 与 `isVerification` |
| 文案 | `AgentVerificationLogic`：标题/问题改为本地文案，选项 `executed` / `not_executed` / `unknown` 本地化，未知 id 回落到服务端 label |
| 证据展示 | 待答态显示 `tool` 与缩进后的 `arguments`（超 2000 字符截断） |
| 推荐项与自由输入 | 验证场景不显示推荐标记、选项描述；`allow_other` 不生效（与 Web 强制 false 一致） |
| 取消 | `AgentVerificationLogic.cancelReason`：验证场景 `task_aborted`，普通提问仍 `user_cancelled` |
| `unknown` 提交 | `Agent` / `Game` 两侧都在 `status=pending` 时保留待答卡片并提示 `verify_still_pending`，不移入已解决 |
| 界面 | `AgentScreen.AgentAskCard` 与 `GameScreen.AskCard` 共用同一逻辑与字符串（中英各 8 条） |

## 3. 自动化验证

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "*AgentVerificationTest*"
BUILD SUCCESSFUL
```

`AgentVerificationTest` 覆盖：带证据的解码、JSON 参数 pretty/截断、选项本地化映射、
取消原因分流、答案体 `question_id=effect` + `selected=unknown`、`pending` 判定。

同时执行（本次改动）：

```
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL（2026-10-30）
```

## 4. 真机端到端（未执行）

构造该交互需要：让一个副作用工具（如 `bash sleep`）开始执行后强杀服务端，使其停在
`started && result==nil`，再触发运行恢复。2026-10-30 在测试服务器上按此路径尝试时被环境阻塞：

- 尝试对象：隔离写作会话（`full_access`），指令 `Run bash sleep 240 now`
- 结果：模型调用在工具执行前即失败，多次（gemini-3.8-flash-high / claude-sonnet-4-6 / gpt-5.6-luna）一致返回
  `400 Bad Request`：
  `provider API error (status 400): POST "http://192.168.2.102:8317/v1/chat/completions": ... "User location is not supported for the API use." (FAILED_PRECONDITION)`
- 结论：测试服务器的模型聚合代理当前对所有 profile 拒绝调用，任何 Agent 都无法产生工具调用，
  因而无法进入「工具执行中断」状态。这是外部依赖故障，与本次客户端改动无关。

补验步骤（代理恢复后）：

1. 隔离写作会话，`approval_mode=full_access`，发送要求 `bash sleep 240` 的指令；
2. 检测到 bash 工具开始（SSE `data-agent-tool` / 服务端 `tool` 日志）后 3 秒内重启容器；
3. App 重新挂接该会话，确认 `pending_ask.id` 以 `verify-` 开头、卡片显示工具与参数；
4. 选「暂时无法确定」→ 服务端回 `pending`、卡片留在待答并提示；再选「已经生效」→ 落定；
5. 记录截图与 `GET /api/chat/active` 的 `pending_asks` 前后对比。

注意：第 2 步会中断服务器上其他会话，需在维护窗口执行，并在矩阵中登记影响范围。

## 5. 不涉及

- 工具审批（`kind=tool_approval`）属 AGT-003；本项只在 `questions` 非空 + `verification` 时生效。
- 自由文本「其他」输入（`allow_other`）仍是 AGT-004 的已知缺口，与 verification 无关。
