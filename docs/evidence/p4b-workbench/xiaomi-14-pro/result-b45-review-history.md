# P4-B 工作台 · 第四/五切片真机证据（WB-005 变更审阅页签、WB-006 历史搜索与导出）

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 用例：`:app:testDebugUnitTest` 69 套件 / **500 用例** 0 失败（新增 5 条历史分页/计数用例）；`lintDebug`、`assembleDebug` 通过

## 1. 本轮实现

| 能力 | 实现位置 |
|---|---|
| 变更审阅页签（分组列表 + 文件差异 + 评论 + 接受/拒绝/撤销/重做） | `WorkbenchTabKind.Changes`、`WorkbenchScreen`（复用 `RightDrawer` 的 `ChangesContent`/`ChangeGroupDetail`） |
| 工作台历史面板（项目筛选 + 搜索 + 分页 + 行内操作） | `feature/workbench/WorkbenchHistorySheet.kt`、`WorkbenchHistoryViewModel.kt` |
| 分页/排序/导出文件名纯逻辑 | `core/workbench/HistoryList.kt`（5 个 JVM 用例） |
| Markdown 导出落盘 | `core/export/MarkdownExporter.kt`（10+ 走 MediaStore `Download/Denova/`，8/9 退回应用外部目录） |

### 契约依据

- 审阅：`/api/projects/:id/workspace/changes/*`（分组、详情、评论 CRUD、accept/reject/undo/redo），
  与写作页右抽屉同一套 UI 与 ViewModel，不复制实现。
- 历史：`GET /api/agent-chat/history?project_id&query&offset&limit`，响应 `items[].{project_id,project_name,session,excerpt}`
  + `total/offset/has_more`；导出 `GET /api/projects/:id/agent-chat/sessions/:sid/export` 返回 `text/markdown`。
- Web 端 `comment_count` 只在类型里声明、界面不展示；我们此前把服务端 `comment_count`（含软删除行）直接显示成
  「评论（n）」，删掉一条后仍显示 2，与列表条数不符 —— 详情页改为按未删除条数统计。

## 2. WB-005 变更审阅（真机）

| 步骤 | 实测结果 |
|---|---|
| ＋ → 变更审阅 | 新页签列出 5 个变更分组，带 `reviewStatus/applyState`、文件数、待处理编辑数、评论数（`shots/133-review.png`） |
| 查看差异 | 打开 `run-20261026` 详情：逐文件 diff、行级增删（红/绿）、行号（`shots/134-diff.png`） |
| 评论整组 | 输入并保存 → `评论（1）`；再次评论 → `评论（2）`；列表可回读（`shots/135-comment.png`、`145-comment-list.png`） |
| 删除评论 | 行内「删除」→ 计数变 `评论（0）`、评论消失（`shots/146-after-delete.png`） |
| 撤销（目标分组准确） | 点「撤销」→ 日志 `Changes: mutated group=run-20261026… paths=1`，详情变 `待审阅 · 已回退`、待处理编辑 0，按钮切换为「重做」（以 `uiautomator` 读回的文本为准：`待审阅 · 已回退` / `0 待处理编辑` / `重做`） |
| 重做 | 点「重做」→ 回到 `待审阅 · 已应用`、待处理编辑 1、按钮切回「撤销」（同上读回 `待审阅 · 已应用` / `1 待处理编辑` / `撤销`） |

验收后把测试用评论全部删除、撤销/重做各回到原状态，未在服务端留下残留改动。

## 3. WB-006 历史搜索与导出（真机）

| 步骤 | 实测结果 |
|---|---|
| 顶栏「历史」 | 底部面板：项目 chips（「我的异世界生活 当前打开」等 5 个）+ 搜索框 + 行列表（`shots/150-history.png`） |
| 搜索 | 输入 `lore` → 日志 `History: loaded project=project-4e55 items=3 total=3`，3 条结果带标题、时间、消息数、命中片段（关键片段高亮）、页脚「已显示 3 / 3 条」（`shots/152-search-result.png`） |
| 清空查询 | 回到项目全量 19 条（`shots/154-sheet.png`） |
| 导出 Markdown | 行菜单「导出 Markdown」→ 日志 `Export: saved name=现有架构ntr情节如何设计-sA5LkCf4.md bytes=5255 scope=downloads`；界面提示「已导出到 Download/Denova/…」，`adb ls /sdcard/Download/Denova` 可见同名文件（15190 字节，UTF-8）（`shots/156-exported.png`） |
| 重命名 | 行菜单「重命名」→ 改名 `新会话B5-test` → 日志 `History: renamed session=s-2026102715…`，列表重载后标题更新（`shots/161-renamed.png`） |
| 删除 | 行菜单「删除」→ 二次确认「删除「新会话B5-test」？已保存的记录会被移除。」→ `History: deleted session=…`，列表从 19 条降到 18 条（`shots/162-deleted.png`） |

导出文件名 = 标题清洗（去掉文件系统非法字符）+ 会话 id 前 8 位，避免同名覆盖；落盘位置固定 `Download/Denova/`。

## 4. 同轮修复

1. 详情页 `评论（n）` 改用未删除条数（原先服务端 `comment_count` 含软删除行，删一条仍显示 2）。
2. 评论锚点解码失败：服务端把 `anchor` 存成 JSON 字符串再套一层（`"{\"kind\":...}"`），
   我们的 `ChangeCommentAnchor` 反序列化直接抛错，导致「新建评论成功后整页报错、评论也读不回来」。
   改为宽松解析（字符串则二次解码，坏数据降级为无锚点）。
3. `MarkdownExporter` 的 MediaStore 分支补 `@RequiresApi(29)`，修掉 lint `NewApi` 错误（minSdk 26）。

## 5. 未覆盖 / 说明

- **「加载更多」真机未触发**：分页参数与 Web 一致（`offset/limit`，页大小 80），当前测试项目共 18–19 条会话，
  不足一页；`HistoryList.append`（追加、去重、has_more、total）有 5 个 JVM 用例覆盖。
- 审阅页签的「工作台侧变更树」只显示分组，文件级操作仍在详情内完成（对齐写作页右抽屉）；
  Web 工作台的变更页签同样是分组 + 详情两级，没有再拆一层。
- 导出只做 Markdown（与基线 `/export` 一致）；其它格式不在契约内。
- 仪器测试未执行（沿用 WRI-016 登记的环境阻塞）。
- 测试账号的服务端会话约 20 分钟过期：过期后写操作会返回「请先输入 Denova 远程访问用户名和密码」，
  重新登录即可（不是客户端缺陷，已在证据中记录该次 401）。
