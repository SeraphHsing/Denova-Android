# G3 双端并发：真实服务器 409 与三方合并真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 第二客户端：`tools/g3_dual_editor.py`（真实服务器 REST 读写，凭证走环境变量；**不是浏览器**）
- 测试文件：`project-b479…/d03-writing/g3-conflict.md`（测完已删除）
- 对应矩阵：`WRI-012`（无重叠并发）、`WRI-013`（重叠冲突）

## 场景 A：无重叠并发（真机 + 真实服务器，通过）

步骤：App 打开文件（rev `bae1546d…`）→ 第二客户端改「第 2 行」并提交（rev `e8a0354a…`）→ App 在标题处输入 → 800ms 后保存。

### A-1 修复前（发现数据丢失缺陷）

```text
I Editor  : merged+saved path=d03-writing/g3-conflict.md revision=sha256:e8a0354a…
I EditorPerf: perf editor-save kind=merged ms=184 draftWindow=801ms
I Editor  : saved path=d03-writing/g3-conflict.md revision=sha256:07aaa92f…
```

随后服务端读回：**第 2 行被还原为原始内容**——合并结果只写进了服务端，本地没有采纳，紧接着的一次保存用本地旧正文覆盖了对端修改。

根因：`FileSaveCoordinator` 的 `SaveOutcome.MergedAndSaved` 只回传 `revision`，`EditorViewModel` 仍把本地文本当作 `baseContent`，下一次保存即以新 revision 覆盖。

### A-2 修复后（重跑同场景，通过）

修复：`MergedAndSaved(revision, mergedText)` 回传合并结果，ViewModel 采纳 `mergedText` 作为 `content` 与 `baseContent`，并递增 `contentEpoch` 让编辑区重建。

```text
I Editor  : merged+saved path=d03-writing/g3-conflict.md revision=sha256:e8a0354a…
I EditorPerf: perf editor-save kind=merged ms=228 draftWindow=800ms
I Editor  : saved path=d03-writing/g3-conflict.md revision=sha256:812c679d…
```

服务端读回（rev `812c679d…`，108 字符）：

```text
APP2# G3 并发测试文件
…
第 2 行：脚本端已改（WEB-2）
```

两端修改同时保留，**后续保存不再覆盖对端内容**。

回归测试：`FileSaveCoordinatorTest` 新增两个断言——合并结果必须回传且等于写回服务端的内容；采纳合并结果后的再次保存不得丢掉对端修改。`testDebugUnitTest` 全绿（200 个测试）。

## 场景 B：重叠冲突（未执行，仅单测覆盖）

尝试三种制造重叠的方式，实际都被 diff3 判为可合并插入，未触发冲突分支：

| 尝试 | 结果 |
|---|---|
| 第二客户端替换整行 + App 在同一行插入 | `kind=merged`（插入与替换被视为不重叠） |
| 第二客户端在标题前插入 + App 在起始位置插入 | `kind=merged`（同位置插入被合并） |
| 第二客户端替换标题 + App 删除首字符再输入 | `kind=merged`（adb 注入未形成同区替换） |

原因：`adb shell input` 无法在 `BasicTextField` 内做「选区替换」，光标落点只能按行近似，因此难以构造真正的同区替换编辑。该分支目前只有 `ThreeWayMerge` / `FileSaveCoordinator` 的单测覆盖（`conflict with overlapping changes returns conflict material`，断言生成 `<<<<<<< 本地修改` 材料）。

**结论：`WRI-013` 不回写**（缺少真实双端冲突证据）；`WRI-012` 具备真机 + 真实服务器证据。

## 限制

- 第二客户端是 REST 脚本，不是 Web 浏览器；浏览器端是否有额外保存节流/合并策略未验证（G6 已证明浏览器存在自有清理逻辑，写作保存路径未做同样核对）。
- 未覆盖「迟到回包」与「草稿事务提交前后终止进程」两个用例，它们同属 G3 退出条件，仍未执行。
- 未测冲突后选择外部版本或保留合并（`WRI-014`，尚未实现入口）。
