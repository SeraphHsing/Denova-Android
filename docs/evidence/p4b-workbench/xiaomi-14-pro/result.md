# P4-B 工作台 · 第一切片真机证据（WB-001 / WB-002 / WB-003 / WB-010）

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 构建：`./gradlew :app:assembleDebug`，`adb install -r`
- 用例：`:app:testDebugUnitTest` 69 套件 / 490 用例 0 失败；`lintDebug`、`assembleDebug` 通过

## 1. 本轮实现范围

| 能力 | 实现位置 |
|---|---|
| 工作台宿主（页签条 / 主副区 / 会话内容） | `feature/workbench/WorkbenchScreen.kt` |
| 项目·会话树（新建 / 重命名 / 排序 / 移除 / 会话增删改） | `feature/workbench/WorkbenchTreeContent.kt`、`WorkbenchViewModel.kt` |
| 页签模型（去重 / 关闭后激活 / 区内重排 / 跨区转移） | `core/workbench/WorkbenchTabs.kt`（11 个 JVM 用例） |
| 项目作用域 Agent 通道（`/api/projects/:id/agent-chat/…`） | `core/network/AgentGateway.kt`、`DenovaClient`（10 个新端点） |
| 工作台项目 / 会话 / 历史 DTO | `core/network/AgentChatProjects.kt`（3 个 JVM 解析用例） |
| 复用同一套会话界面（写作 / 工作台） | `AgentViewModel.bindGateway` + `AgentScreen(inline=true)` |

后端契约逐条核对自固定基线 `internal/api/routes.go` 与 `handler_agent_chat.go`：工作台走
`/api/agent-chat/projects|activity|history` 与 `/api/projects/:id/agent-chat/*`，**不切换前台写作工作区**。

## 2. WB-001 项目管理（创建 / 重命名 / 排序 / 归档）

| 步骤 | 实测结果 |
|---|---|
| 添加项目 → 新建项目目录 `wb-tmp-20261027` | `POST /api/agent-chat/projects/directory` 成功，日志 `directory project created=project-c183`，随后 `projects=7 selected=project-c183` |
| 重命名项目 → `wb-tmp-renamed` | `PATCH /api/agent-chat/projects/:id` 成功，日志 `renamed project=project-c183`，刷新后树中显示新名 |
| 上移一项 | 本地立即换位 + `POST /api/agent-chat/projects/reorder`，日志 `reordered projects=project-c183… delta=-1`；点「刷新」重读服务端后顺序保持（截图 `29`、`30`） |
| 从工作台移除 | 二次确认对话框（说明「目录与会话仍保留在服务端」）→ `DELETE /api/agent-chat/projects/:id`，日志 `archived project=project-c183`、`projects=6` |

截图：`shots/19-add-menu.png`、`21-create-dir-dialog.png`、`26-rename-project.png`、`27-project-renamed.png`、
`29-project-moved-up.png`、`30-order-persist.png`、`32-archive-confirm.png`、`33-archived.png`。

## 3. WB-002 会话管理（创建 / 重命名 / 删除）

| 步骤 | 实测结果 |
|---|---|
| 项目行 `＋` 新建会话 | `POST /api/agent-chat/sessions` 成功，新会话出现在项目首行、自动开页签（`主区 (2)`，截图 `12`） |
| 重命名会话 | 行内菜单 → 重命名对话框 → `POST …/sessions/rename`，树中显示 `wb-rename`（截图 `16`） |
| 删除会话 | 行内菜单 → 二次确认 → `POST …/sessions/delete`；行消失、`显示更多` 计数回到 12、对应页签自动关闭并切到相邻页签（截图 `17`、`18`） |

会话列表按项目展开，默认显示最近 5 条 + 「显示更多」（截图 `05-project-tree.png`）。

## 4. WB-003 主副会话（各自全屏 / 草稿保留 / 不串流）

1. 主区打开会话 A，页签菜单「送到副区」→ 顶部计数变为 `副区 (1)`、主区空（截图 `44`、`45`）。
2. 回到主区打开会话 B（另一个会话）→ `主区 (1)` + `副区 (1)` 并存，两区各自持有页签。
3. 在主区输入框输入 `draftA` → 切到副区：副区显示的是会话 A、输入框为空（截图 `49`、`50`）。
4. 切回主区：`draftA` 仍在输入框、会话 B 的正文与滚动位置未重建（截图 `54`）。

**同轮修复一个真机缺陷**：切换主区/副区会重新进入组合，页签宿主原先无条件 `start()`，
导致切回时页面状态被重置（草稿丢失、历史重新拉取）。现改为「同一会话已挂接则不重复 start」
（`WorkbenchScreen.WorkbenchTabHost`），复测步骤 4 草稿保留。

## 5. 项目作用域 Agent 会话（发送与历史）

- 打开「我的异世界生活 / 有没有更有张力生动的性描写方案」→ canonical 历史按消息部件渲染
  （工具卡、用量、执行摘要），状态 `空闲`（截图 `06-conversation.png`）。
- 输入 `ping` 发送 → `Agent: send session=s-vSBEnLxLTmH7mh2jUeSJk cmd=5fb45509…`（项目作用域会话 id），
  流式返回 `pong! …`、`duration_ms=5751`，状态 `完成`（截图 `07`~`10`、`43`）。
- 进程重启后重新进入该会话，`ping/pong` 与工具事件从服务端历史完整重建（截图 `43`）。

## 6. WB-010 宿主目录选择（远程限制）

点「添加项目 → 选择宿主目录」：请求 `POST /api/host/dialogs/directory` 被 `localHostEffectMiddleware`
以 403 拒绝，界面按服务端原因显示「此系统操作只能从运行 Denova 的本机触发」，
**未使用手机本地目录**，也未把手机路径当成宿主路径（截图 `20-host-dir-remote.png`）。

## 7. 顺带修复（导航返回分级）

真机发现：右抽屉打开时按返回键会**先退出当前页面**，并把上一页的右抽屉重新显示出来。
根因是用 `BackHandler` 注册的返回回调排在 `NavHost` 的导航回调之后注册顺序里被抢先。
改为在抽屉打开期间用 `OnBackPressedDispatcher.addCallback`（不带 LifecycleOwner，后注册优先）
追加回调；另外在路由变化时收起右抽屉。

复测：工作台打开项目抽屉 → 返回 → 抽屉收起、仍停在工作台（截图 `41`、`42`）。

## 8. 未覆盖 / 缺口

- 页签跨进程恢复未实现（Web 持久化 `tab-state`）：进程被杀后工作台页签为空，会话内容需重新打开。
- 页签种类只做了 Agent 会话：终端页签（WB-008）与文件页签/嵌入编辑（WB-004 的工作台宿主形态）在 B2/B3。
- 项目树未自动滚动到新选中项目（新项目追加在列表末尾，需手动滚动）。
- 工作台历史搜索与导出（WB-006）、变更审阅页签（WB-005）未接入。
- 仪器测试未执行（沿用 WRI-016 登记的环境阻塞）。
