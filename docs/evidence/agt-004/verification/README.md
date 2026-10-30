# AGT-004 补验：工具副作用验证（verification）

日期：2026-10-30
状态：**契约、界面与真机端到端均已通过**

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
| 已解决态 | `AgentAskLogic.statusText` 按提交选项 id 换成本地文案（「已验证：操作已生效 / 未生效 / 仍待确认」），不再回显服务端英文 label |
| 界面 | `AgentScreen.AgentAskCard` 与 `GameScreen.AskCard` 共用同一逻辑与字符串 |

## 3. 自动化验证

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "*AgentVerificationTest*"
BUILD SUCCESSFUL
```

`AgentVerificationTest` 7 例：带证据的解码、JSON 参数 pretty/截断、选项本地化映射、
取消原因分流、答案体 `question_id=effect` + `selected=unknown`、`pending` 判定、
已解决态按选项本地化（并确认普通提问仍按服务端 label 列出）。

同时执行：

```
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL（2026-10-30）
```

## 4. 真机端到端（2026-10-30，小米 14 Pro / `135babde`）

构造方法（可复现）：

1. 测试服务器前台项目切到含 63 个文件的工作区（`projects/1`），新建写作会话并设 `approval_mode=full_access`；
2. 让 Agent 用 bash 执行 `find . -type f -exec sha256sum {} +`（实测 3.3s，`mutation_scope=external`）；
3. SSE 出现 `tool-input-available` 后 0.2s 执行 `docker kill -s KILL denova`
   （SIGKILL，避免 SIGTERM 优雅落盘把 `tool_result` 补上）；
4. `docker start denova` 就绪后 `GET /api/chat/active` 显示 `open_tools=[{name:bash}]`、`phase=suspended`、
   `recovery_actions[kind=resume]`；
5. 用该 resume 动作调 `POST /api/chat/recovery` → 恢复入口执行 `restoreEffectInteractions()`，补出 ask。

观测结果：

- ask：`id=verify-tool-…`、`kind=question`、`questions=[{id:effect, options: executed/not_executed/unknown}]`，
  与后端 `verify-<callId>` 约定一致；
- App 挂接后显示「核实中断的操作」+ 说明文案 + 工具名 `bash` + 三个本地化选项 + 「提交 / 取消任务」（`shot-1-pending.png`）；
- 选「暂时无法确定」提交：服务端返回 `status=pending`（`ask answered … status=pending`），
  卡片保留待答并提示「尚未确认操作结果，任务将继续等待。」（`shot-2-unknown-pending.png`）；
- 再选「已经生效」提交：`status=answered`，状态行显示「已验证：操作已生效」，
  `GET /api/chat/active` 变为 `active=false / phase=idle`、`pending_asks` 清空（`shot-4-verified-zh.png`）；
- 修复前已解决态曾回显服务端英文 label（`shot-3-answered.png`，It took effect），
  本轮已按选项 id 本地化，并补了单测防止回退。

影响与恢复：

- 构造期间对测试服务器执行过一次 `docker kill -s KILL`（容器 `unless-stopped`，实测需手动 `docker start`），
  另有一次普通 `docker restart` 用于清理活动会话路径错乱；两次都只影响测试环境。
- 验证完成后前台项目已切回原书籍（`Wuthering Waves`），探测会话保留（`verify-hash-1030` / `verify-slow-1030`）。

## 5. 不涉及

- 工具审批（`kind=tool_approval`）属 AGT-003；本项只在 `questions` 非空 + `verification` 时生效。
- 自由文本「其他」输入（`allow_other`）仍是 AGT-004 的已知缺口，与 verification 无关。
