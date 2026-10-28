# P4-D · 自动化（AUT-001…004）真机证据

- 日期：2026-10-29
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 `0.6.3-b`）
- 用例：`:app:testDebugUnitTest` **78 套件 / 572 用例 / 0 失败**（新增 `AutomationEditorTest` 14 例）；`lintDebug`、`assembleDebug` 通过

## 1. 实现

| 能力 | 实现位置 |
|---|---|
| 契约：Task / Target / Schedule / Trigger / RunRecord / InboxItem / Template / 请求体 | `core/network/Automations.kt` |
| 客户端端点（catalog / tasks / templates / create / update / delete / check / run / inbox 三动作） | `DenovaClient`（新增 11 个方法） |
| 纯逻辑：草稿往返、必填校验、调度摘要与 cron 复刻、触发器规范化、运行状态 | `core/automations/AutomationEditor.kt`（14 个 JVM 用例） |
| 页面状态机（目录 / 任务 / 每任务草稿 / 收件箱 / 运行 / 检查触发器） | `feature/automations/AutomationsViewModel.kt` |
| 页面（任务 / 收件箱两标签、项目作用域切换、基本区 + 触发条件 + 最近运行） | `feature/automations/AutomationsScreen.kt` |
| 导航接入 | `MainActivity`（Activity 作用域 `AutomationsViewModel`）、`ActivityNav`（`automations.implemented = true`） |

事实来源按固定基线 `85ec97c`：`web/src/lib/api-client/automations.ts`、
`web/src/features/automations/{AutomationsView,AutomationConfigPanel,AutomationInboxPanel,AutomationTriggerEditor}.tsx`、
`internal/automation/{types,schedule,store}.go`。要点：`PATCH` 必带 `base_revision`；
`POST /:id/run` 需客户端生成 `command_id` 幂等键；`cron` 由服务端按 kind 生成（客户端只读复刻）；
触发器类型白名单 `schedule / chapter_batch / semantic`，`notify_policy` 默认 `schedule → silent`。

## 2. AUT-001 创建与编辑（读回正确）

| 步骤 | 实测结果 |
|---|---|
| 新建面板 | 点「新建自动化」→ 打开编辑面板：项目 chips（目录里的 4 个项目）、名称（模板默认「续写章节」）、启用、模板 chips（`continue_writing` / `review`）、会话模式、模型档案、指令正文（模板默认长指令） |
| 项目必填校验 | 当前书籍为空时直接保存 → 本地拦截「有字段校验未通过」，**未发出 POST**；点项目 chip 后保存成功 |
| 创建 | 提示「自动化已创建」，日志 `Automations: created key=auto-50c93ebe040d36e8`；服务端 `GET /api/automations?project_id=project-efb2…` 读回该任务（`enabled=false` 来自模板默认值、`schedule.kind=manual`、`session_strategy=per_run`、`triggers=1`） |
| 编辑启停 | 打开任务 →「开」→ 保存 →「自动化已保存」，服务端读回 `enabled=True`、`revision=sha256:2ec1d18…` 前移；`triggers` 仍为 1（未被客户端清空） |
| 作用域 | 目录按项目分组；顶部项目 chips 切换后列表与收件箱随之切换；新建后作用域自动切到新任务所属项目（列表立即可见） |

**同轮修复**：①首次打开任务时没有播种草稿，编辑面板整块空白 → `select()` 用服务端任务初始化草稿；
②宿主传入的 `projectId` 为空（上次打开的项目已归档）会覆盖已在使用的项目作用域，导致「去 Agent 再回来」
变成 0 个任务 → `bindClient` 改用「非空覆盖」；③新建时项目为空 → 退到目录第一个项目。

## 3. AUT-002 启停 / 检查 / 手动运行

| 步骤 | 实测结果 |
|---|---|
| 立即运行 | 点「立即运行」→ 提示「已开始运行（running）」；服务端读回 `last_run.status=running`、`trigger=manual`、`session_id=automation-run-run-command-dd4a3c0d…`、`runtime_command_id=automation-run:run-command-dd4a3c0d…`（即客户端生成的 `command_id` 被用作幂等键）、`recent_runs=1` |
| 检查触发器 | 点「检查触发器」→ 提示「检查完成：0 条新通知」并切到收件箱标签（该任务的语义触发条件未命中，与基线「检查后再读收件箱并切面板」同序） |
| 最近运行 | 编辑面板底部展示 `recent_runs` 前 5 条（状态 / 开始时间 / 输出路径 / 摘要） |
| 删除 | 二次确认弹窗（「服务端按归档处理，历史运行记录会保留」）后 `DELETE`，列表与选中项清空（本任务保留以便复用证据，未执行删除） |

## 4. AUT-003 收件箱已读 / 确认 / 忽略

用另一个项目（`project-c76485…`）上既有的自动化（`auto-f825ad28` 自动 Review）产生的 2 条收件箱条目验证：

| 步骤 | 实测结果 |
|---|---|
| 列表 | 切到该项目 → 收件箱标签显示 2 条：「自动 Review reached chapter batch 2 / 1」，状态 `auto_run · 未读`，每条三个动作（已读 / 确认 / 忽略按门控渲染） |
| 已读 | 点「已读」→ 提示「通知已标记为已读」；服务端 `GET /api/automations/inbox` 读回该条 `read_at` 已写入，界面未读标记消失、标题「0 条未读」 |
| 确认 / 忽略 | 门控与基线一致：确认只在 `status=pending && action_policy=confirm` 出现，忽略只在 `status=pending` 出现。本部署的两条均为 `status=auto_run`（服务端 `EffectiveActionPolicy` 恒返回 `auto_run`，`internal/automation/store.go` + `automation_test.go:1077` 断言「trigger.action_policy=confirm 仍得到 auto_run」），因此**没有 pending 条目可构造**，这两个动作的真机往返缺失，只由门控代码 + 单测覆盖 |

## 5. AUT-004 窄屏标签与草稿

| 步骤 | 实测结果 |
|---|---|
| 320dp 布局 | `wm size 720x1600` + `wm density 360`（=320dp 宽）：标题、动作行（新建 / 保存 / 立即运行 / 检查触发器 / 删除，横向滚动）、项目 chips、任务/收件箱两标签、字段行均无重叠，全部可点；编辑面板字段单列排布 |
| 切标签保草稿 | 编辑名称 → 切「收件箱」→ 切回「任务」→ 名称仍为编辑后的值（每任务一份草稿） |
| 去 Agent 再返回 | 进「写作 Agent」再回「自动化」→ 项目作用域、选中任务与草稿全部保留（`AutomationsViewModel` 放在 Activity 作用域） |
| 进程重启 | `force-stop` 后重启 → 草稿丢弃、表单回到服务端值（内存草稿，与基线内存态一致）；**服务端没有被写成半截编辑** |
| 恢复屏幕 | 测试后 `wm size reset` / `wm density reset` |

## 6. 未覆盖 / 已知差异

- **自动保存**：基线用 `use-automation-autosave`（1s 防抖 + revision 冲突 rebase 重试）；Android 是显式「保存」+ 内存草稿 + 409 提示重新打开。差异已登记在本行 `gap_or_decision`。
- **pending 收件箱条目**：见 §4，当前部署产生不出 `status=pending`，确认/忽略的真实往返无法取证（属服务端 `EffectiveActionPolicy` 契约，不是客户端缺口）。
- 收件箱「查看来源运行 / 打开会话」入口未做（基线从收件箱跳到对应 AgentChat 会话）。
- 模板下拉的 `locale` 固定 `zh-CN`；运行记录只读 `recent_runs`（基线无运行记录列表端点）。
- 时区：服务端按服务器本地时区判定，界面不显示时区（与基线一致）。
- 仪器测试未执行；Web 端同场景对照随 [P5](../../../android-web-parity/05-roadmap.md#7-p5全量双端对照)。
