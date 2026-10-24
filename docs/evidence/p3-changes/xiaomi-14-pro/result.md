# P3 变更审阅：真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建，serial `135babde`）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 项目：`project-efb2ed8ad64c839df4b72c3a69772350`（灵渊劫-问道）
- 对应：P3 链路「变更审阅」；契约 `03 §4.1` 的 `changes/`
- 实现：`core/network/WorkspaceChanges.kt`（`WorkspaceChangeGroupSummary` 等 DTO）、`DenovaClient.changeGroups/reviewChangeGroup/undoChangeGroup/redoChangeGroup`、`feature/changes/ChangesViewModel.kt`、`RightDrawer.ChangesContent`（右抽屉「项目文件 / 变更」分段）

## 验证步骤与结果

1. 选「灵渊劫-问道」后打开右抽屉，显示「项目目录 / 变更」分段。
2. 点「变更」：`Changes: loaded project=project-efb2… groups=0`（空列表，UI 显示「暂无变更」）。

## 附带修复

- **顶栏溢出回归**：第三切片给编辑器加的「写作」按钮用了 `R.string.agent_title`（"Novara 写作"，约 304px），把顶栏挤出屏幕、右抽屉 `»` 不可见（误点会触发退出）。已改用短文本 `R.string.writing`（"写作"/"Write"），右抽屉按钮恢复可见（`[1307,149][1401,279]`）。

## 结论

变更审阅的数据层与 UI 骨架、空状态：**真机通过**。

## 未覆盖 / 缺口

- **有变更数据的审阅闭环**（accept / reject、undo / redo 的真实服务端往返）：当前项目 `groups=0`，未构造出真实变更分组（需 Agent 编辑文件产生）。接口与 DTO 已按 `03 §4.1` 与 Web `features/changes/api.ts` 对齐，由代码审查确认。
- 变更分组详情（`GET changes/groups/:id` 的 hydrated group、文件 diff、行级评论）：未做，当前仅列表摘要 + 决策按钮。
- 审阅线程（`changes/review-threads/:id`）、评论增删改（`changes/comments`）：未做。
