# WRI-011 手动保存与切文件（含 G3-b / G3-c 回补）

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `g3d-merge.md`（5 段测试夹具，`tools/g3_dual_editor.py` 可重建）
- 对应矩阵：`WRI-011`（写作编辑 / 手动保存与切文件；steps「保存等待时继续编辑并切文件」，expected_ui「各文件最新草稿保留」，expected_server_effect「旧快照回执不清新 dirty」）
- 同时回补 P1 台账：**G3-b（重叠冲突真实双端用例）**、**G3-c（迟到回包端到端）**
- 实现：`app/src/main/java/com/denova/novara/feature/editor/EditorSession.kt`、`EditorScreen.kt`、`app/src/main/java/com/denova/novara/core/save/FileSaveCoordinator.kt`
- 单测：`app/src/test/java/com/denova/novara/EditorSessionTest.kt`（23 用例，本次新增 5 个）

## 1. 变更要点

| 变更 | 原因 |
|---|---|
| 「保存」按钮：未打开 / 保存中 / 冲突未解决时禁用 | 避免空保存与重复 PUT；冲突态只有「已解决，保存」一条放行路径 |
| 冲突态新增「已解决，保存」按钮 + 提示文案 | 此前冲突只能看不能保存（`performSave()` 在 CONFLICT 直接返回 false），用户无法从 App 内解决 |
| `SaveOutcome.Conflict` 增加 `serverContent`；`applyConflict` 的 base 改为服务器内容 | 之前把「带冲突标记的合并结果」当 base：`content == baseContent` 使「已解决，保存」被当成无改动而**静默跳过**，真机验证时发现服务器根本没写入 |
| 保存串行化（`Mutex`）：新编辑不再取消在途保存，只排下一轮 | 之前每次按键都会中断在途 PUT，回执永远到不了，「旧快照回执不清新 dirty」无从生效；现在回执照常返回并按快照判断是否 dirty |
| `flush()` 最多连做 3 轮直到内容与 base 一致 | 保存期间用户仍在输入时，切换/关闭不再丢掉最后几次按键 |
| `open()` 递增 `contentEpoch` | 重新打开同一个文件时正文可能变（例如服务器被别处改回），界面必须重建编辑态；否则输入框留着旧文本，下一次保存会把旧内容写回服务器 |

## 2. 真机验证

### 2.1 手动保存入口

在文末输入一个字符后**立刻**点「保存」：

```text
I Editor: saved path=g3d-merge.md revision=sha256:a19201566b9dc64e…
I EditorPerf: perf editor-save kind=saved ms=162 draftWindow=111ms
```

`draftWindow=111ms` 远小于 800ms 防抖 —— 这次保存由手动入口触发，不是自动保存（自动保存的 `draftWindow` ≥ 800ms，见 2.3 的 805ms）。

### 2.2 重叠冲突（G3-b）

另一端（`g3_dual_editor.py --heading`）改写标题行，本端在标题行开头插入字符，然后点「保存」：

```text
W Editor: conflict path=g3d-merge.md count=1
```

界面（[overlap-conflict.png](overlap-conflict.png)）正文为：

```text
<<<<<<< 本地修改
l# G3 并发测试文件
=======
# G3 并发测试文件（脚本改）
>>>>>>> 服务器修改
```

状态行同时出现 `冲突`、`发现冲突，请手动解决后保存`、`发现冲突：在下方编辑冲突标记后点「已解决，保存」`，右侧是「已解决，保存」按钮；「保存」按钮在冲突态禁用。

点「已解决，保存」后真正落盘：

```text
I Editor: saved path=g3d-merge.md revision=sha256:a18a57e97255bdcd…
```

服务端读回：`len=159`、`has_markers=True`，内容即界面上的合并文本。
（说明：adb 难以精确删改多行标记，本次按「接受合并文本」处理；「先改标记再保存」的语义由单测 `conflict rebases onto server content so resolved save writes` 覆盖。）

### 2.3 迟到回包（G3-c）：旧快照不清新 dirty

用 `tools/g3d_slow_proxy.py`（只把 `PUT …/files/file` 的转发延后 3s）把 App 指向代理，然后在同一文件上：

1. 输入 `1` → 800ms 后自动保存发出，PUT 在代理处等待 3s；
2. 保存仍在途时输入 `2`（此时正文是 `…12`，快照是 `…1`）；
3. 旧快照回执返回 → 因为 `content != snapshot`，状态回 IDLE 并**重新排队**一次保存。

```text
I Editor: saved path=g3d-merge.md revision=sha256:152695d63d09183d…   ← 快照 1
I EditorPerf: perf editor-save kind=saved ms=3209 draftWindow=805ms
I Editor: saved path=g3d-merge.md revision=sha256:8d1daf29ea8d067b…   ← 快照 12
I EditorPerf: perf editor-save kind=saved ms=3468 draftWindow=0ms
```

第二次保存的 `draftWindow=0ms` 说明它不是由新按键触发的防抖，而是旧回执「仍 dirty」后立即补写。
服务端读回：`ends_with='。\n12'`，两次输入都在，旧回执没有把新编辑当作已保存。

### 2.4 各文件最新草稿保留

断网（`svc wifi disable` + `svc data disable`）后编辑并点「保存」：

```text
状态：错误
保存失败：Failed to connect to /192.168.2.102:18081
```

恢复网络后重新打开同一文件（`打开`）：

```text
I Editor: opened … path=g3d-merge.md revision=sha256:bae1546d…
界面：已恢复未保存的编辑
正文末尾：… 第 5 行：原始内容（不可改）。\nz      ← 断网期间的编辑仍在
```

再次点「保存」→ `saved … c3ab27ea…`，服务端读回 `ends_with_z=True`、`len=103`。未提交编辑没有因为保存失败而丢失。

## 3. 过程中发现并修复的两个缺陷

1. **冲突解决后保存是空操作**：`applyConflict` 把 base 记成带标记的合并结果，`saveNow()` 判定 `content == baseContent` 后直接置「已保存」而不发 PUT（真机首轮验证时服务端 revision 未变，问题暴露）。改为 base = 服务器内容，并给 `flush()` 加了错误/冲突短路。
2. **重开同一文件正文不刷新**：`open()` 之前不推进 `contentEpoch`，界面重置 effect 的 key 没变，输入框留着旧文本（当时是冲突标记），此时保存会把旧内容写回服务器。改为 `open()` 每次推进 epoch，单测 `reopening same file bumps content epoch…` 覆盖。

## 4. 自动化覆盖

`EditorSessionTest` 新增 5 个用例：

- `manual save bypasses debounce and does not save twice`（手动保存不等防抖，且后续不再重复 PUT）
- `manual save on clean file does not write`（无改动只给反馈）
- `edits during in-flight save stay dirty and the latest text is saved`（G3-c 的 JVM 版：两次保存，最终 `A12`）
- `conflict rebases onto server content so resolved save writes`（冲突后 base 是服务器内容，解决后真正写入）
- `reopening same file bumps content epoch so the editor rebuilds its text`（重开刷新编辑态）
- 另有 `manual save during conflict waits for resolution` 覆盖冲突态手动保存被拦。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 45 suites / 285 tests / 0 failures。

## 5. 结论与未覆盖

**通过**：手动保存立即生效（`draftWindow=111ms`）；无改动不发空 PUT；重叠冲突真实双端可检出并展示标记与作用域提示，解决后可写入；
保存等待中继续编辑时旧快照回执不清新 dirty 并自动补写（G3-c）；保存失败后未提交编辑在重开文件时恢复（各文件草稿保留）；冲突态与保存中的按钮状态互相排斥。

**未覆盖（登记缺口）**：

- 冲突解决只验证了「接受合并文本」；用软键盘精确删改 `<<<<<<<` 多行标记的交互未真机演练（单测覆盖语义）。
- 切文件时的草稿保留用的是「断网保存失败 → 重开同一文件」构造；未做「A 保存失败 → 切到 B → 切回 A」的连续切换（离线时读不到 B，切换本身会失败）。
- 关闭标签时的 flush 失败中止路径未真机演练（仅单测）。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
