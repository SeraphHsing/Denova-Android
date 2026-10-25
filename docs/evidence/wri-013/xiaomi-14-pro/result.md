# WRI-013 重叠冲突：冲突材料归档 + 保留本地 / 加载外部

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `wri013.md`（5 段测试夹具，`tools/g3_dual_editor.py reset` 可重建）
- 对应矩阵：`WRI-013`（写作编辑 / 重叠冲突；steps「双方编辑相同段落」，expected_ui「暂停自动保存并显示冲突选择」，expected_server_effect「冲突材料保留且不盲目覆盖」）
- 实现：
  - `app/src/main/java/com/denova/novara/core/save/ConflictArchive.kt`（新增：请求体契约、`ConflictArchive` 端口、服务端归档、本地兜底、`encodeDefaults=true` 编解码）
  - `app/src/main/java/com/denova/novara/core/save/FileSaveCoordinator.kt`（冲突时先归档四份正文再返回冲突选择）
  - `app/src/main/java/com/denova/novara/core/network/DenovaClient.kt`（`POST /api/autosave-conflicts`）
  - `app/src/main/java/com/denova/novara/feature/editor/EditorSession.kt` / `EditorViewModel.kt` / `EditorScreen.kt`（`ExternalConflict` 状态、`keepLocalVersion` / `loadExternalVersion`、两个按钮 + 归档 id）
- 单测：`app/src/test/java/com/denova/novara/ConflictArchiveTest.kt`（5 用例，新增）、`EditorSessionTest.kt`（27 用例，本次新增 4 个）

## 1. 变更要点

| 变更 | 原因 |
|---|---|
| 冲突材料归档到 `POST /api/autosave-conflicts` | 基线 `useEditorDraftPersistence.archiveConflict` → `preserveEditorConflict` 在给出「保留本地 / 加载外部」选择前先落盘 base / local / external / merged；此前 Android 冲突只提示不归档，「保留本地」等于不可追溯覆盖 |
| 归档请求用 `Json { encodeDefaults = true }` 单独编码 | `resource` / `strategy` / `conflict_paths` 都有默认值，默认编解码器会省略它们，而后端 `validateInput` 要求非空（`resource is required`）→ 直接 400 |
| 归档失败落本地兜底（`LocalConflictArchive`） | 对齐 Web 在服务端日志不可用时写 `localStorage` 的分支；冲突选择与界面提示不因日志故障中断 |
| 归档失败不阻断冲突选择 | 正文仍在本地草稿与界面里，用户仍可保留本地或加载外部；只记 `conflict archive failed` 日志，`recoveryId` 为空 |
| 冲突态从「已解决，保存」一个按钮改为「保留本地 / 加载外部」二选一 | 基线 `EditorPersistenceNotices` 是二元选择；「加载外部」此前完全缺失（WRI-014 缺口） |
| `loadExternalVersion()` 推进 `openEpoch` 并清草稿 | 正文被替换后旧回执不得改 revision；草稿若留着，下次打开会被当成未提交编辑又恢复回来 |

## 2. 真机验证

冲突构造方式：本端先写入一次（服务端成为本端 base）→ 第二客户端 `g3_dual_editor.py edit --prepend` 改写同一标题行 → 本端在同一位置继续输入 → PUT 带上过期 revision → 409。

### 2.1 冲突界面（[conflict-choice.png](conflict-choice.png)）

```text
W Editor: conflict path=wri013.md count=1
I EditorPerf: perf editor-save kind=conflict ms=195 draftWindow=804ms
```

界面同时具备：

- 状态「冲突」+ 原因「发现冲突，请手动解决后保存」
- 基线文案「发现冲突：服务器已改动此文件。可在下方编辑冲突标记后保留本地，或直接加载外部版本。」
- **归档回执**「冲突材料已归档：autosave-conflict-183519cc06257da3134a25c27875c72d」
- 「保留本地」「加载外部」两个按钮
- 正文三段冲突标记（`<<<<<<< 本地修改` / `=======` / `>>>>>>> 服务器修改`）

### 2.2 冲突材料真的落在服务端（不是本地兜底）

三次冲突分别得到 `autosave-conflict-37f03dc2…`、`…baefc81f…`、`…183519cc…`。用 NAS SSH 进入后端容器核对第一条：

```text
$ docker exec denova ls -l /data/.denova/conflicts | tail -1
-rw------- 1 root root 32116 Oct 26 02:44 autosave-conflict-37f03dc2baecc08a684f629821f8cfa9.json

$ head -c 300 /data/.denova/conflicts/autosave-conflict-37f03dc2baecc08a684f629821f8cfa9.json
{
  "version": 1,
  "record_id": "autosave-conflict-37f03dc2baecc08a684f629821f8cfa9",
  "created_at": "2026-10-25T18:44:24.644680574Z",
  "resource": "workspace_file",
  "scope": "project-efb2ed8ad64c839df4b72c3a69772350",
  "id": "wri013.md",
  "base": {
    "revision": "sha256:10765c6a62255f3ace8
```

`scope` / `id` / `base.revision` 与冲突发生时的作用域逐字一致；`resource=workspace_file`、`strategy`、`conflict_paths` 均按后端 `autosave.Input` 落盘（本地兜底路径本次未触发，由单测覆盖）。

### 2.3 保留本地

冲突态点「保留本地」：`I Editor: saved path=wri013.md revision=sha256:95857300…`，服务端读回 160 字节正文，首段即本地合并结果：

```text
<<<<<<< 本地修改
# efghG3 并发测试文件
=======
脚本改3：# efG3 并发测试文件
>>>>>>> 服务器修改
```

即：保留本地走的是「服务器 revision + 合并结果」，不是拿旧 base 盲覆盖（`base.revision` 为服务端当时版本，单测 `keep local writes merged text against the server revision` 断言同一行为）。点完按钮后状态回到「已保存」，冲突条消失（[keep-local-saved.png](keep-local-saved.png)）。

### 2.4 加载外部

重置文件 → App 重新「打开」→ 写入 `ab`（服务端 `eb67d46c`）→ 另一端 `--prepend`（服务端 `36f652bd`）→ App 再写入 `cd` → 冲突（归档 `…baefc81f…`）→ 点「加载外部」：

- 界面正文替换为服务器版本 `脚本改2：# abG3 并发测试文件`，状态「已加载外部版本」，冲突条消失
- **服务端 revision 保持 `36f652bd` 未变**（`tools/g3_dual_editor.py show` 读回），日志在 `conflict` 之后没有任何 `saved` —— 本地改动确实被丢弃，没有反向写回
- 之后继续编辑仍走正常自动保存（单测 `load external replaces the body and never pushes the local edit` 覆盖同路径，并断言草稿已清除）

## 3. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL
suites=55 tests=381 failures=0
```

新增用例：

| 用例 | 覆盖点 |
|---|---|
| `encoded record keeps defaulted fields required by the server` | `resource` / `strategy` / `conflict_paths` 不被省略，`value` 按 JSON 字符串编码 |
| `encode then decode round trips every snapshot` | 四份快照 + 作用域往返 |
| `local archive writes a recoverable copy named after the returned id` | 本地兜底文件名 = 回执 id，内容可再解析 |
| `fallback archive uses the local copy when the server journal fails` | 服务端日志失败 → 本地兜底 |
| `fallback archive propagates cancellation instead of masking it` | 取消不被包装成归档成功 |
| `conflict archives all three sides and exposes recovery id` | 冲突时归档四份正文 + 界面拿到 `recoveryId` |
| `archive failure still leaves the conflict choice available` | 归档失败仍可选保留本地/加载外部 |
| `keep local writes merged text against the server revision` | 保留本地用服务器 revision 落盘 |
| `load external replaces the body and never pushes the local edit` | 加载外部不发 PUT、清草稿、后续编辑恢复自动保存 |

## 4. 未覆盖 / 已知边界

- 冲突标记的手工编辑仍靠用户改正文（无「逐块选本地/选远端」的合并 UI），与基线一致但未做行内高亮。
- `conflict_paths` 固定为 `[[]]`（与 Web 默认一致）：Android 侧三方合并按行产出冲突块，没有 JSON path 概念，服务端只做记录不做解析。
- 本地兜底归档只在单测覆盖过，真机未制造「服务端日志不可写」场景。
- 仪器测试未执行（`connectedDebugAndroidTest` 仍受 UTP 插件下载 TLS 失败阻塞，见 `docs/STATUS.md`）。
