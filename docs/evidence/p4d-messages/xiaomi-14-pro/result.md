# P4-D · 消息中心（MSG-001…003）真机证据

- 日期：2026-10-29
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 `0.6.3-b`）
- 用例：`:app:testDebugUnitTest` **79 套件 / 582 用例 / 0 失败**（新增 `MessageCenterLogicTest` 11 例）；`lintDebug`、`assembleDebug` 通过

## 1. 实现

| 能力 | 实现位置 |
|---|---|
| 契约：`ProductMessage` / `ProductMessageList` / `ActivitySummary` | `core/network/MessageCenter.kt` |
| 客户端端点：`GET /api/messages`、`POST /api/messages/:id/read`、`POST /api/messages/read-all`、`GET /api/activity/summary` | `DenovaClient`（新增 4 个方法） |
| 纯逻辑：客户端四类筛选、类型标签、倒序、已读边界（列表不置读）、标记投影、窄屏不预选 | `core/messages/MessageCenterLogic.kt`（11 个 JVM 用例） |
| 页面状态机（列表 / 详情 / 已读 / 全部已读） | `feature/messages/MessageCenterViewModel.kt`（Activity 作用域，抽屉角标共用） |
| 页面：筛选 chips + 列表 + 详情（列表与详情互斥） | `feature/messages/MessageCenterScreen.kt` |
| 入口：抽屉底部「消息中心（N）」+ `messages` 路由 | `feature/shell/AppShell.kt`、`MainActivity` |

事实来源按固定基线 `85ec97c`：`web/src/features/messages/{api.ts,types.ts,MessageCenter.tsx}`、
`internal/messages/types.go`、`internal/app/activity/service.go`。要点：列表**无分页无服务端过滤**；
`message_unread_count` 来自 `/api/activity/summary`；`read` 幂等；基线**没有**详情 / 删除端点。

## 2. MSG-001 列表、详情与返回

| 步骤 | 实测结果 |
|---|---|
| 抽屉角标 | 抽屉底部显示「消息中心（6）」，与服务端 `unread_count` 一致 |
| 列表 | 打开后标题「13 条 · 6 条未读」，四类筛选 chips（全部 / 待办 / 自动化 / 产品）；每行显示类型标签（更新 / 自动化）、时间 `2026-10-23 00:00`、标题、摘要、未读圆点与「查看」 |
| 打开列表不置读 | 停留在列表期间服务端 `read_at` 未变化，未读数仍为 6（与基线「只有进入详情才 markRead」一致） |
| 详情 | 点「查看」→ 详情显示标题、`更新 · 2026-10-23 00:00 · 已读`、摘要、正文，以及 `project / run / inbox` 关联标识 |
| 详情置已读 | 进入未读条目后未读数 6 → 5，服务端该条 `read_at` 写入；已读条目再打开不会重复写（`shouldMarkRead` 单测） |
| 返回 | 「←」回列表，列表状态与选中 id 保留（不重新拉取、不丢滚动位置） |

## 3. MSG-002 已读范围与跳转

| 步骤 | 实测结果 |
|---|---|
| 单条已读 | 服务端读回该条 `read_at` 有值、其他条目不变（`read` 只作用指定 id） |
| 全部已读 | 点「全部已读」→ 提示「已全部标记为已读」、标题变「13 条 · 0 条未读」；服务端 `GET /api/messages` 读回 `unread_count=0`、13 条全部有 `read_at` |
| 跳转 | 自动化类消息详情出现「打开自动化」（带 `task_id` 才出现）→ 点击后落在自动化页且作用域切到该消息的 `project_id`（`project-c76485…`，显示「1 个任务」），即 `{taskId, projectId}` 的跳转目标正确 |

## 4. MSG-003 英文与空态

| 步骤 | 实测结果 |
|---|---|
| 切英文 | 抽屉切 English 后重进：标题 `Messages`、`1 items · 0 unread`、筛选 `All / Action / Automation / Product`、类型 `Automation`、详情里的 `read` 与 `Open automation` 均为单语，无中英混排 |
| 空态 | 选 `Action` 筛选（该部署没有待办消息）→ `0 items · 0 unread` + `No messages in this filter` |
| 全空列表 | **不可构造**：服务端 `GET /api/messages` 总会把 changelog 与动态消息合并下发（≥13 条），没有能清空列表的入口；`No messages`（列表为空）分支只有单测覆盖 |

## 5. 同轮修复

正文一开始用 `MarkdownDocument.parse(body).toPlainText()` 渲染，真机上 changelog 正文**整段消失**
（同一入参在 JVM 单测里能渲染出内容）。改为只还原 HTML 实体后按纯文本展示，正文恢复显示；
Markdown 富文本渲染登记为视觉差异（见 §6）。

## 6. 未覆盖 / 已知差异

- **正文是纯文本**：基线用 Markdown 富文本渲染消息正文，Android 展示原文（保留 `###` 等标记）。
- **入口形态不同**：基线是工作台顶栏的通知 pill 打开右侧 Sheet；Android 放在左抽屉底部并用独立路由承载整页。
- 返回列表只保留选中 id，没有像基线那样把焦点显式 `focus()` 回原行（`data-message-id` 等价物在 Compose 里未做）。
- 整页空态不可构造（见 §4）；仪器测试未执行；Web 端同场景对照随 [P5](../../../android-web-parity/05-roadmap.md#7-p5全量双端对照)。
