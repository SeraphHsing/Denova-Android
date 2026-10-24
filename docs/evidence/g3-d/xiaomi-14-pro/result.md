# G3-d 切文件：在途保存取消与回执作用域

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`），验证期间客户端经延迟注入代理转发，后端未做任何改动
- 目标文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `g3d-a.md`、`g3d-b.md`
- 对应台账：P1 [G3-d](../../../android-web-parity/05-roadmap.md#31-p1-证据台账2026-10-24-核对)（切文件用例）；矩阵 `WRI-011`（手动保存与切文件）的并发子集
- 相关代码：`app/src/main/java/com/denova/novara/feature/editor/EditorSession.kt`、`app/src/main/java/com/denova/novara/core/save/FileSaveCoordinator.kt`
- 单测：`app/src/test/java/com/denova/novara/EditorSessionTest.kt`

## 1. 缺陷

`EditorSession.open()` 换文件时不取消在途 `saveJob`，`performSave()` 完成后无条件把回执写进 `_state.value`：
旧文件的 `revision` / `baseRevision` / 冲突材料会落到新文件上，导致新文件后续保存用错误的 base（409 或静默覆盖风险）。

修复（两处）：

1. `open()` 开头 `saveJob?.cancel()`、`saveJob = null`、`openEpoch++`：换文件即取消在途保存。
2. `performSave()` 记录发起保存时的 `epoch` 与文件 key，回执返回后校验 `openEpoch` 与 `projectId\u0000path`；
   不匹配则记 `save receipt discarded path=… reason=file changed` 并返回 false，不写任何状态。
   `flush()` 用 `performSave()` 的返回值判定，避免「回执被丢弃却报已保存」。
3. `FileSaveCoordinator` 显式 `catch (e: CancellationException) { throw e }`，取消不会被包装成 `SaveOutcome.Error`
   而在 UI 上显示「保存错误」。

## 2. 为什么需要延迟注入代理

LAN 真实写文件 30–60ms（本次实测 84KB 的 PUT 往返 31ms），而 `adb shell input` 一次点击要约 100ms，
真机无法在保存返回前完成「点打开」的切换。因此新增 `tools/g3d_slow_proxy.py`：只把
`PUT /api/projects/:id/files/file` 的转发延后 3s，其余请求（读文件、鉴权、books）原样立即转发。
代理只做转发，不改请求/响应内容，不属于后端改动。

```powershell
python tools/g3d_slow_proxy.py --listen 0.0.0.0:18099 --target http://192.168.2.102:18081 --put-delay-ms 3000
# App 内把服务器地址改为 192.168.2.101:18099 后连接、登录
```

## 3. 真机步骤

1. 打开 `g3d-a.md`（231 字符，revision `7fa9f5d9…`）。
2. 在「文件路径」框预填 `g3d-b.md`（只改输入框，不点打开）。
3. 点正文插入 `x` 触发自动保存（800ms 防抖）；1s 后保存已发出且被代理挂着。
4. 点「打开」→ 读 `g3d-b.md`（走代理不延迟）→ `open()` 执行，取消在途保存。

## 4. 时间线与证据

客户端日志（`adb logcat -s Editor:* EditorPerf:*`）：

```text
I Editor  : opened project=project-efb2ed8ad64c839df4b72c3a69772350 path=g3d-a.md revision=sha256:7fa9f5d9d0ecfb64b75f7b5573d11acb4e5f3ab3374013d1db7fd6c894df5c77
I EditorPerf: perf editor-open chars=231 ms=110
# —— 此处编辑并点「打开」，保存仍在途 ——
I Editor  : opened project=project-efb2ed8ad64c839df4b72c3a69772350 path=g3d-b.md revision=sha256:70cc2c65e76307952d0378ec768eedadb3dd671d87c8338958836de49630b2c6
I EditorPerf: perf editor-open chars=156 ms=71
# 没有任何 saved path=g3d-a.md   ← 在途保存被取消，回执未应用
I Editor  : saved path=g3d-b.md revision=sha256:441a5cbd02a5e41537ff021507cbe58c6793f9aa328eb17f742eda15fd144b15
I EditorPerf: perf editor-save kind=saved ms=3240 draftWindow=803ms
```

代理日志（体现「保存仍在途时切文件」）：

```text
[proxy] "GET /api/projects/…/files/file?path=g3d-a.md HTTP/1.1" 200 -
[proxy] delaying PUT /api/projects/…/files/file?path=g3d-a.md by 3.0s
[proxy] "GET /api/projects/…/files/file?path=g3d-b.md HTTP/1.1" 200 -      ← 切文件在读发生时完成
[proxy] "PUT /api/projects/…/files/file?path=g3d-a.md HTTP/1.1" 200 -       ← 客户端已取消后请求才被转发
```

服务端读回（`tools/g3_dual_editor.py show`）：

| 文件 | 切文件后即时读回 | 后续在 B 继续编辑保存后 |
|---|---|---|
| `g3d-a.md` | `len=232`、`rev=sha256:080373e9…`、插入的 `x` 位于偏移 193 | 未再变动 |
| `g3d-b.md` | `len=156`、`rev=sha256:70cc2c65…`（与打开时一致，未串扰） | `len=157`、`rev=sha256:441a5cbd…`，`x` 位于末尾 |

界面证据：[switch-file-b-loaded.png](switch-file-b-loaded.png)（切到 B 后正文为 B 的 8 行、顶部无「已保存/错误/冲突」提示）。

## 5. 结论与覆盖范围

- 通过：换文件时在途保存被取消，回执不再落到新文件；新文件的 `content` / `revision` / `baseRevision` 保持自身；
  之后在 B 的保存无 409，`saved path=g3d-b.md` 用的是 B 自己的 base revision；服务端两侧内容各自正确。
- 行为说明（不是缺陷，需按 AGENTS §4 理解）：取消**不保证服务端未收到写入**。本例服务端最终仍应用了 A 的 PUT（`len=232`），
  客户端只是丢弃了回执；重新打开 A 会读到含 `x` 的正文（服务端 canonical 为准），不会出现「本地认为保存了但服务端没有」
  或反向的归属错乱。
- 未在真机触发 `save receipt discarded` 日志分支：`saveJob` 路径在回执到达前就被取消，该分支由
  `EditorSessionTest.late receipt from previous file is discarded`（gated remote 延迟回执 + `flush()` 路径）覆盖。

## 6. 自动化覆盖

`EditorSessionTest` 新增 3 个用例（`GatedRemote` 用 `CompletableDeferred` 卡住回执）：

- `open new file cancels in-flight save`：A 保存挂起时 `open(b.md)` → B 状态干净、`saveStatus=IDLE`、远程未收到第二次写。
- `late receipt from previous file is discarded`：`flush()` 在独立协程中、切文件后放行 → 服务端写成功但回执被丢弃，
  新文件 content / revision 不被污染，`flush()` 返回 false。
- `save after switching file applies to new file`：切到 B 后保存只写 `b.md`，base 为 B 的 revision。

RED 验证：只撤下两个生产文件的改动（`git stash push` 仅暂存 `EditorSession.kt` / `FileSaveCoordinator.kt`）后运行
`./gradlew --no-daemon :app:testDebugUnitTest --tests "*EditorSessionTest*"` → `19 tests completed, 2 failed`
（前两个用例失败）；恢复后全绿。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 汇总 40 suites / 235 tests / 0 failures。

## 7. 未执行

- 仪器测试（`connectedDebugAndroidTest`）未执行；本次为真机手工 + JVM 单测组合。
- 反向路径（切文件后不取消、只靠回执校验）未在真机单独构造，仅 JVM 用例覆盖。
