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

## 2.5 版本入口（读 `/versions` 列表 + 状态）

右抽屉新增「版本」分段（第三个），只读 `GET /versions/status` + `GET /versions?limit=20`：

```text
I Versions: loaded project=project-efb2… versions=20 clean=true has=true
```

界面（[versions-entry.png](versions-entry.png)）：

```text
版本                                          刷新
自上个版本以来工作区无改动
自动版本：2026-10-25 02:34
每 10 分钟自动创建
自动版本：2026-10-25 02:34
2026-10-25T02:35:04+08:00 · timer · 51 个文件 · 161801499 字节
…（共 20 条）
本页只读入口，diff 与恢复随 VER-001/002 提供
```

`clean` / 改动数、`latest.message`、`auto.timed_enabled` 与 `timed_interval_minutes` 都按服务端值展示；
创建版本、diff、恢复计划与恢复**故意不做**，属 `VER-001` / `VER-002`，避免出现语义未核对的写按钮。

## 2.6 局部审阅：只接受一个文件

让 Agent 在一轮里改两个文件，得到一个含 **2 个变更集** 的 pending 分组
（`run-20261024T183608…`，`paths=[g3d-a.md, g3d-b.md]`，`pending_edit_count=2`）。

详情里每个文件头都带「仅接受此文件 / 仅拒绝此文件」，点第一个文件的「仅接受此文件」：

```text
I Changes: mutated group=run-20261024T183608… paths=0
界面：2 个文件 · 1 待处理编辑；第一个文件「已接受」，第二个仍「待审阅」并保留按钮
```

服务端读回：

```text
group mixed / applied  pending=1  paths=['g3d-a.md', 'g3d-b.md']
  cs g3d-a.md  accepted / applied
  cs g3d-b.md  pending  / applied
```

即只裁决了 `g3d-a.md` 的变更集，另一个仍是 pending，分组状态变为 `mixed`。
（[partial-accept.png](partial-accept.png) 是混合态截图；随后接受第二个文件，分组回到 `accepted`、`pending_edit_count=0`。）

## 2.7 评论增删改与 diff 行锚点

点 diff 的 `+review-line-C` 行 → 编辑器标题显示「评论 第 7 行」并回显原文 → 输入正文 → 保存：

```text
I Changes: comment created id=comment_1792866878954… anchored=true
```

服务端读回锚点：

```text
side=after  revision=sha256:6f2f5…  start=93  end=106  quote=review-line-C
```

`end - start = 13` 与 `review-line-C` 的 UTF-8 字节数一致，`quote` 与 `content[93:106]` 逐字节相等
（后端 `validateCommentAnchor` 会校验这三项，任一不符即 409）。

编辑 → `comment updated`，服务端 `body='anchor-edited'` 且 `anchor` 保留；
删除 → `comment deleted`，服务端 `deleted=true`、`comment_count=0`（软删除），界面回到「暂无评论」。
评论行带「行内」标记（有锚点时才显示）。

## 3. 真机发现并修复的缺陷

1. **同一路径多个变更集导致 Compose 崩溃**：撤销/拒绝会给分组追加逆向变更集，详情里于是出现两条同 `path` 的 diff 头。
   首版用 `path` 作 LazyColumn key，点「撤销」后直接 `FATAL EXCEPTION: IllegalArgumentException: Key "h-g3d-review.md" was already used`（连崩三次）。
   修复：key 改为 `文件序号 + 行序号`；同时顶部「N 个文件」改为**去重路径数**、头部补 `review_status`、评论作者为空时只显示正文。
2. **新增 `VersionsViewModel` 未注册到 ViewModelFactory**：装上新包后启动即崩
   `IllegalArgumentException: No initializer set for given class com.denova.novara.feature.versions.VersionsViewModel`。
   `AppContainer` 的 `viewModelFactory` 是白名单式注册，新 ViewModel 必须显式 `initializer { … }`。
3. **评论锚点的 `encoding` 会被省略**：`ChangeCommentAnchor.encoding` 初始带默认值 `utf8-bytes-v1`，
   而客户端 `Json` 未开 `encodeDefaults`，默认值字段不会出现在请求体里，服务端 `validateCommentAnchor` 会以
   「comment anchor encoding is invalid」整条拒绝。改为**无默认值**（由 `ChangeDiff.anchor` 显式传入常量），
   单测断言请求体含 `"encoding":"utf8-bytes-v1"`。

## 4. 自动化覆盖

- `ChangeDiffTest`（9 用例）：CONTEXT/ADD/REMOVE 行号归属、替换行双侧、新增/删除文件分类、超长截断、`splitLines`、详情 JSON 解析、空评论。
- `ReviewContractsTest`（10 用例）：整组与单变更集的 `ReviewChangeRequest` 序列化；`ChangeDiff.anchor` 的 side/revision/字节区间与
  `quote` 逐字节一致（含中文多字节）；空行与缺 revision 返回 null；评论请求（带/不带锚点）字段名；版本状态与历史解析、缺省字段容错。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 47 suites / 304 tests / 0 failures。

## 5. 仪器测试（新增，执行被环境阻塞）

- `app/src/androidTest/java/com/denova/novara/AppLaunchSmokeTest.kt`：`ActivityScenario` 冷启动 `MainActivity` 断言 `RESUMED`，并覆盖 `recreate()`。
- `app/build.gradle.kts` 增加 `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` 与 `androidx.test.ext:junit` / `androidx.test:runner` 依赖。
- `:app:assembleDebugAndroidTest` 通过，两个 APK 都装进设备，`pm list instrumentation` 可见
  `instrumentation:com.denova.novara.test/androidx.test.runner.AndroidJUnitRunner (target=com.denova.novara)`。
- **执行未完成**：`:app:connectedDebugAndroidTest` 在下载 UTP 插件时失败
  `Could not GET …/android-test-plugin-host-apk-installer-32.3.0.jar … (bad_record_mac) Tag mismatch`（本机网络的 TLS 拦截，非代码问题）；
  改用 `adb shell am instrument -w …` 直连执行时被本机命令执行策略跳过，未取到这次运行结果。
  待网络/策略恢复后按上面两条命令之一补跑即可。

## 6. 结论与未覆盖

**通过**：变更列表展示真实 agent 变更组；详情按服务端回填的 before/after 渲染行级差异（行号 + `+n/-n`）；
整组接受 / 拒绝 / 撤销 / 重做四条路径与文件状态一致；**单文件局部接受**在多变更集分组上验证（组变 `mixed`，另一个文件仍 pending）；
评论新增（带 diff 行锚点，服务端逐字节校验通过）/ 编辑（锚点保留）/ 删除（软删除，计数归零）；
版本入口读取状态与 20 条历史，`clean` / `latest` / 自动版本配置按服务端展示。

**未覆盖（登记缺口）**：

- 版本功能本身：创建 / diff / 恢复计划 / 恢复属 `VER-001`、`VER-002`，本轮只做入口。
- 按 `edit_ids`（单条编辑）粒度裁决未接；**hunk 级**审阅后端没有对应字段（`hunk_id` 只用于评论锚点）。
- 无 unified / split 视图切换与折叠；审阅线程 `review_threads/:id` 的连续性（continuous/conflicted）未展示。
- 评论回复（thread）与行内锚点的「跳到原文」未做。
- 仪器测试已落地但本次执行被环境阻塞（见 §5）。
