# WRI-015 章节状态与搜索替换

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-c15785a0e466a3915ed01d7ee52a89a2`（临时书 `writestbook`）/ `chapters/ch001.md`、`chapters/ch002.md`（测完书籍已移除）
- 对应矩阵：`WRI-015`（写作编辑 / 章节状态搜索替换；steps「改章节状态并搜索替换」，expected_ui「结果定位数量和状态对应」，expected_server_effect「只修改目标范围」）
- 契约来源：`GET /api/projects/:id/book/summary`（`ChapterSummary.status` 由后端算，`internal/book/summary.go:135`）、`PATCH /api/projects/:id/book/chapter-status` body `{path, confirmed}`（`handler_project_book.go:57-84`）、Web 编辑器内查找/替换口径 `editorDecorations.ts`
- 实现：
  - `core/book/ChapterRules.kt`（新增：空章不可确认、确认/标回初稿、状态文案、摘要文案）
  - `feature/chapters/ChaptersViewModel.kt`（新增：summary 加载 + 只写 `confirmed` 后重新拉摘要；回包做项目作用域校验）
  - `feature/shell/RightDrawer.kt`（新增 `ChaptersContent` + 行内状态按钮）+ `MainActivity` 右抽屉第 4 段「章节」
  - `core/network/BookShelf.kt` + `DenovaClient.bookSummary / setChapterConfirmed`（两个契约）
  - `core/editor/EditorSearch.kt`（新增：字面量/正则都大小写不敏感、逐个定位、单次替换、从后往前的全部替换）
  - `feature/editor/EditorScreen.kt`（查找条：`当前/总数`、上一个/下一个、替换为、替换、全部替换、正则开关；输入即定位首处）
- 单测：`ChapterRulesTest`（5 例）、`EditorSearchTest`（11 例）

## 1. 章节状态（服务端算，客户端只写 confirmed）

`chapters/ch001.md` 写入 13 字后，右抽屉「章节」：

```text
I Chapters: summary loaded project=project-c15785a0e466a3915ed01d7ee52a89a2 chapters=1 words=13
1 章 · 共 13 字
第001章            初稿 · 13 字        [确认成章]
```

点「确认成章」：

```text
I Chapters: status path=chapters/ch001.md confirmed=true
I Chapters: summary loaded … chapters=1 words=13
第001章            成章 · 13 字        [标回初稿]
```

再点「标回初稿」：

```text
I Chapters: status path=chapters/ch001.md confirmed=false
第001章            初稿 · 13 字        [确认成章]
```

- 只提交 `{path, confirmed}`，**状态字符串由服务端重算**（`初稿`/`成章`），客户端不本地改字符串；
- 「只修改目标范围」：同一本书里的 `chapters/ch002.md` 仍是 `初稿`，且 `ch001.md` 的正文与 revision 未变（只动了 `chapter_statuses.json` 里的确认位）；
- 空章（`words == 0`）按钮不可用 —— `ChapterRules.canConfirm` 单测覆盖（服务端同样有 `words > 0` 前置）。

## 2. 查找与替换（`chapters/ch002.md`，正文 `rain rain on stone`）

查找 `rain` → 查找条显示 **1/2** 并选中第一处（输入即定位，对齐 Web「当前/总数」）；点「下一个」→ **2/2**。

「替换为」填 `snow` → 点「全部替换」：

```text
I Editor: saved path=chapters/ch002.md revision=sha256:800fb9b0…
服务端读回：snow snow on stone（len=18）
```

一次「撤销」整体回退（全部替换是一次编辑 → 一步撤销）：

```text
I Editor: history 已撤销 len=18 caret=0
I Editor: saved path=chapters/ch002.md revision=sha256:f7947d4f…
服务端读回：rain rain on stone
```

- 「只修改目标范围」：替换只作用于当前文档的命中项，未触碰同书其他章节；
- 匹配口径对齐基线编辑器内实现（字面量与正则都大小写不敏感；与后端 `/workspace/search` 的 RE2 大小写敏感不同，这是基线自身的不一致）；
- 正则模式与分组引用（`$1`）、无效正则、可匹配空串的正则均按基线拒绝，由 `EditorSearchTest` 覆盖。

## 3. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL   suites=60 tests=426 failures=0
```

## 4. 未覆盖 / 已知边界

- 「替换」（逐个替换）只做了单测与代码路径，真机只验了「全部替换」；逐个替换的光标推进在 adb 注入下不易断言。
- 工作区级「全部替换」（`POST /api/projects/:id/workspace/replace`，跨文件、带 `skipped` 与自动版本快照）未实现——本行口径是编辑器内搜索替换。
- 章节列表只读展示：未做章节重命名/拖拽排序/分组（卷）、字数统计刷新节流。
- 状态变更后靠重新拉 summary 反映，没有本地乐观更新（避免与「状态由服务端算」冲突）。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
