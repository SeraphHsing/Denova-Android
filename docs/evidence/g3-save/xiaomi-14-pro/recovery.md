# G3 草稿事务提交前后终止进程：真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建，serial `135babde`）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 测试文件：`project-b47902c162fffb7983a95ab73cae37d0` / `g3-recovery.md`（测完已从服务端删除）
- 对应：G3 退出条件「草稿事务提交前后终止进程」；实现为 `EditorSession`（`feature/editor/EditorSession.kt`）+ `EditorOpen.decide`

## 前置

- 持久化登录 Cookie 已生效：`force-stop` 重启后 `status ok authenticated=true` 免登录，直接进编辑器（G6 决策 C）。
- 草稿落盘时机：编辑 → 800ms 防抖 → `performSave` → `FileSaveCoordinator.save` 先 `saveDraft`（落草稿）→ `saveFile`（网络）。`saveFile` 成功才 `clearDraft`；失败（非 409）保留草稿。

## 场景 A：草稿提交前终止（编辑后 <800ms force-stop）

步骤：打开 `g3-recovery.md`（66 字符，rev `0d7fa2cf…`）→ 先编辑 `XX` 并保存（服务器 68 字符，rev `a9fbfaf9…`）→ 编辑 `LOST` 后立即 `force-stop`（防抖 800ms 未到期，save 未开始、草稿未落盘）→ 重启重连重开同一文件。

结果：`opened … chars=68`（rev `a9fbfaf9…`），`LOST` 编辑丢失，恢复到服务器已确认内容。

```text
I Editor  : opened project=project-b47902… path=g3-recovery.md revision=sha256:0d7fa2cf…   # 首次打开
I EditorPerf: perf editor-save kind=saved draftWindow=800ms                                  # 编辑 XX 保存
I Editor  : opened project=project-b47902… path=g3-recovery.md revision=sha256:a9fbfaf9…   # 重启后，chars=68
I EditorPerf: perf editor-open chars=68 ms=99
```

结论：草稿提交前终止，未提交编辑窗口（`LOST`）不进入草稿，恢复到服务器内容。**通过。**

## 场景 B：草稿提交后终止（编辑 → 断网让保存失败 → force-stop）

步骤：编辑 `KEEP` → 立即 `svc wifi disable; svc data disable`（让 `saveFile` 失败，但 `saveDraft` 已落盘）→ 800ms 后 `editor-save kind=error`（草稿保留）→ `force-stop` → 恢复网络 → 重启重连重开同一文件。

结果：`EditorPerf` 记录的 `chars=68` 是**服务器内容长度**（`EditorPerf.endOpen` 记录的是 `doc.content.length`，非恢复后内容）；实际界面显示 **「已恢复未保存的编辑」**，Room `drafts` 表落盘记录 `content` 长度 **72**（含 `KEEP`）。

```text
I EditorPerf: perf editor-save kind=error ms=52 draftWindow=802ms    # 保存失败，草稿保留
I Editor  : opened project=project-b47902… path=g3-recovery.md revision=sha256:a9fbfaf9…
I EditorPerf: perf editor-open chars=68 ms=87                          # 服务器长度，非恢复结果
```

UI dump：`text="已恢复未保存的编辑"`。数据库查询：

```text
drafts: fileKey='project-b47902…\x00g3-recovery.md', length(content)=72, content='# G3 并发测试文件\n\n第 1 行…'
```

结论：草稿提交后终止，草稿已持久化，重启后恢复未提交编辑（`KEEP`）。**通过。**

## 场景 C：保存链路回归

- 编辑后自动保存：`perf editor-save kind=saved ms=70 draftWindow=802ms`（正常）；无重叠并发 409 → `kind=merged`（三方合并正常，回归通过）。
- 恢复的草稿点「保存」后：`perf editor-save kind=saved ms=257 draftWindow=0ms`（手动保存，`draftWindow=0` 符合「无待提交编辑」语义）。

## 未覆盖 / 限制

- **重叠冲突（WRI-013）真实双端用例**：`adb shell input` 无法在 `BasicTextField` 内做精确选区替换（DEL/输入焦点易落到其他输入框），本轮重复尝试仍只能构造「插入 vs 修改」被 diff3 判为可合并（`kind=merged`）。该分支由 `ThreeWayMergeTest` / `FileSaveCoordinatorTest` / `EditorSessionTest`（`applyMerged re-applies concurrent edit to conflict`、`conflict pauses autosave`）单测覆盖，真实双端证据留待后续（Web 端操作或输入法辅助）。
- **迟到回包端到端**：需在「保存进行中」注入第二次编辑，真机无法稳定构造；由 `ReceiptPolicy.applySaved/applyMerged` 单测覆盖。
