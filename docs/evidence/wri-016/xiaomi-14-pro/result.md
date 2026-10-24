# WRI-016 有变更数据的审阅（diff / 评论 / 接受 / 撤销 / 重做）

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `g3d-review.md`
- 对应矩阵：`WRI-016`（写作编辑 / 审阅与版本入口；steps「查看 diff 评论接受或撤销并读回」，expected_ui「差异与确认范围对应」，expected_server_effect「提交结果和文件状态对应」）
- 实现：`app/src/main/java/com/denova/novara/feature/changes/ChangeDiff.kt`、`ChangesViewModel.kt`、`app/src/main/java/com/denova/novara/feature/shell/RightDrawer.kt`、`app/src/main/java/com/denova/novara/core/network/WorkspaceChanges.kt`、`DenovaClient.kt`
- 单测：`app/src/test/java/com/denova/novara/ChangeDiffTest.kt`（9 用例）

## 1. 变更要点

| 变更 | 说明 |
|---|---|
| 详情契约 | `GET /api/projects/:id/changes/groups/:gid` → `WorkspaceChangeGroup`（`change_sets[]` 带 `before_content`/`after_content`、`review_status`、`apply_state`；`comments[]`） |
| 行级统一 diff | `ChangeDiff` 用 `before_content`/`after_content` 本地做 diff3 风格行对比，输出 CONTEXT/ADD/REMOVE + 双侧行号 + `+n/-n` 统计；>2000 行标记 `truncated`，不假装展示全部 |
| 详情界面 | 文件头（路径 + 新增/删除标记 + 变更集状态 + 增删统计）、逐行 diff、评论列表、接受/拒绝/撤销/重做（`can_undo`/`can_redo` 门控） |
| 操作后刷新 | review/undo/redo 成功后同时刷新列表与当前详情（`review_status`/`apply_state`/`can_undo` 都会变） |

## 2. 真机验证（真实 agent 变更数据）

变更数据来自 Agent 的文件写入工具（`origin=agent`，`change_group_id=tool call id`），编辑器手动保存按设计不产生审阅组（`change.SaveFile` 注释明确「不记录可审阅变更」）。

### 2.1 接受

Agent 追加一行后：

```text
API  groups=1  run-20261024T175324.64…  pending/applied  paths=['g3d-review.md']  edits=1
文件 g3d-review.md 末尾出现 review-line-A
```

App：右抽屉 → 变更 → 列表出现该项（`查看差异` / `接受` / `拒绝`），`Changes: loaded project=… groups=1`；
点「查看差异」→ `Changes: detail group=… files=1 comments=0`，头部 `待审阅 · 已应用`，正文：

```text
g3d-review.md                        已接受 +1 -0
1  # 审阅测试文件
2
3  第一段：原始内容。
4
5  第二段：原始内容。
6 +review-line-A
评论（0） 暂无评论
```

点「接受」：

```text
I Changes: mutated group=run-20261024T175324… paths=0
```

头部变 `已接受 · 已应用`，接受/拒绝收起、出现「撤销」；服务端 `accepted/applied`、`can_undo=true`，文件仍含 `review-line-A`（接受不改变已应用的正文）。

### 2.2 拒绝（文件回退）

第二次 Agent 写入生成 `run-20261024T175804…`（pending），详情 diff 以该组自己的 base 对比：

```text
6  review-line-A
7 +review-line-B
```

点「拒绝」：

```text
I Changes: mutated group=run-20261024T175804… paths=1
```

服务端：`rejected / reverted`；文件读回 `has_A=True, has_B=False`（拒绝把该组回退掉，A 保留）。

### 2.3 撤销 / 重做

对已接受组点「撤销」：

```text
I Changes: mutated group=run-20261024T175324… paths=1
```

服务端 `accepted / reverted`，文件 `has_A=False`，头部 `已接受 · 已回退`，按钮换成「重做」。

点「重做」：

```text
I Changes: mutated group=run-20261024T175324… paths=1
```

服务端 `accepted / applied`，文件 `has_A=True`，`can_undo=true`。

### 2.4 评论

通过 `POST /changes/comments`（`{group_id, body}`）写入一条评论后重开详情：

```text
1 个文件 · 0 待处理编辑 · 评论（1）
评论（1）
审阅备注：这一行保留，措辞后续再改
```

[review-detail.png](review-detail.png) 是撤销+重做后同一分组的详情：三个变更集（原始 +1、撤销产生的 +0 -1、重做的 +1）都带状态标记，diff 与评论同屏。

## 3. 真机发现并修复的缺陷

**同一路径多个变更集导致 Compose 崩溃**：撤销/拒绝会给分组追加逆向变更集，详情里于是出现两条同 `path` 的 diff 头。
首版用 `path` 作 LazyColumn key，点「撤销」后直接 `FATAL EXCEPTION: IllegalArgumentException: Key "h-g3d-review.md" was already used`（连崩三次）。

修复：key 改为 `文件序号 + 行序号`；同时
- 顶部「N 个文件」改为**去重路径数**（原来显示变更集数量，撤销后从 1 变成 3，误导）；
- 头部补 `review_status` 标记，让同一路径的原始 / 逆向 / 重放变更集可区分；
- 评论作者为空时只显示正文（不再出现 `-：`）。

## 4. 自动化覆盖

`ChangeDiffTest`（9 用例）：相同内容只有 CONTEXT；插入/删除行的 `beforeLine`/`afterLine` 归属；替换行双侧都出现；新增/删除文件分类；
超长文件截断（`truncated` 且行数封顶）；`splitLines` 末尾换行处理；分组详情 JSON 解析（`change_sets`、`comments`、`can_undo/can_redo`、`apply_state`）；空评论列表。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 46 suites / 294 tests / 0 failures。

## 5. 结论与未覆盖

**通过**：变更列表能展示真实 agent 变更组；详情按服务端回填的 before/after 渲染行级差异（含行号与 +n/-n）；评论可读；
接受 / 拒绝 / 撤销 / 重做四条路径与「提交结果和文件状态对应」一致（接受保持正文、拒绝回退、撤销回退、重做重放），并且操作后列表与详情同步刷新。

**未覆盖（登记缺口）**：

- **版本历史入口未做**：本行标题含「版本入口」（Web `components/Versions/*`、`/api/projects/:id/versions`），本轮只做变更审阅。
- 评论只读：新建 / 编辑 / 删除评论、行内锚点评论（`anchor`）与回复未做。
- 无差异视图切换（Web 有 unified/split）与折叠；不接受单文件 / 单编辑（`edit_ids`）/ 单 hunk 的局部审阅，只有整组接受或拒绝。
- 审阅线程（`review_threads`）的连续性（continuous/conflicted）未展示。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
