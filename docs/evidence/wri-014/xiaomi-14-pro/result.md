# WRI-014 外部版本加载：选区与撤销按基线更新

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `wri013.md`（测完已复位到 5 段夹具）
- 对应矩阵：`WRI-014`（写作编辑 / 外部版本加载；steps「冲突后选择外部版本或保留合并」，expected_ui「选区和撤销按基线更新」，expected_server_effect「最终读回符合所选动作」）
- 实现：
  - `app/src/main/java/com/denova/novara/core/editor/EditHistory.kt`（新增：撤销 / 重做栈，记录改动前快照，连续输入按 700ms 窗口合并）
  - `app/src/main/java/com/denova/novara/feature/editor/EditorSession.kt`（`undo` / `redo` / `applySnapshot`；`open`、`clear`、`loadExternalVersion`、`keepLocalVersion`、`MergedAndSaved` 五处重设基线）
  - `app/src/main/java/com/denova/novara/feature/editor/EditorScreen.kt`（模式行新增撤销 / 重做，光标按 `uiState.caret` 恢复）
  - `app/src/main/res/values{,-zh}/strings.xml`（`editor_undo` / `editor_redo` 中英文）
- 单测：`EditHistoryTest`（8 例，新增）、`EditorSessionTest`（新增 3 例：撤销 / 重做、连续输入合并、加载外部清空历史）

## 1. 本轮补的缺口

此前该行保持 `未开始` 的唯一原因是「选区和撤销按基线更新」没有验证条件——编辑器根本没有撤销栈，基线 `loadExternalVersion` 的 `resetHistory` 语义无从断言。本轮补上撤销 / 重做后，这条语义才可验证：

| 触发点 | 基线语义 | 实现 |
|---|---|---|
| 打开文件 / 关闭标签 | 新基线 | `open()` / `clear()` → `history.reset()` |
| 加载外部版本（WRI-014） | resetHistory + 选区归零 | `loadExternalVersion()` → `history.reset()` + `caret = 0` |
| 保留本地（冲突选择） | 冲突材料不参与撤销 | `keepLocalVersion()` → `history.reset()` |
| 采纳合并结果（MergedAndSaved） | 合并结果即新基线 | `history.reset()`（撤销不得退回合并前文本再覆盖服务端） |

## 2. 真机验证：撤销 / 重做

在 `wri013.md` 末尾追加一个 `Z`（`saved … revision=sha256:8634522…`，长度 102 → 103），随后：

```text
13:07:37.259 I Editor  : history 已撤销 len=102 caret=84
13:07:38.254 I Editor  : saved path=wri013.md revision=sha256:bae1546…   ← 服务端读回 len=102
13:07:55.265 I Editor  : history 已重做 len=103 caret=85
13:07:56.218 I Editor  : saved path=wri013.md revision=sha256:8634522…   ← 服务端读回 len=103
```

- 撤销后服务端读回确实是**原始内容**（revision 回到 `bae1546d…`，与服务端重置值一致），不是只改本地；
- 重做后读回恢复到编辑后的内容（`8634522…`）；
- 光标按快照恢复（84 / 85），即「选区」随撤销重做一起更新，而不是每次都跳到文首。

界面（[undo-redo-row.png](undo-redo-row.png)）：撤销 / 重做放在「源码 / 富文本 / 预览」行右侧。**并入顶栏会把「写作」「»」挤出屏幕**（本轮真机复现过一次顶栏溢出，与 P3 第四切片同类问题），故不放在顶行。

## 3. 真机验证：合并采纳后基线重设

在真实合并路径上触发 `merged+saved`（App 编辑与第二客户端 `/api/workspace` 写入同一文件后 409 → 三方合并 → 重存）：

```text
13:09:33.597 I Editor  : merged+saved path=wri013.md revision=sha256:f4907e2…
```

合并落盘后点「撤销」：**没有 `history 已撤销` 日志，也没有新的 PUT** —— 撤销栈已按基线清空，用户无法把合并前的本地正文退回来覆盖服务端结果（这正是 WRI-013 里 `MergedAndSaved` 回传 `mergedText` 的同类风险）。

## 4. 加载外部版本的基线重置（自动化断言）

冲突 → 「加载外部」这条路径需要真实冲突态；WRI-013 已用双端并发真机构造并验证「加载外部替换正文、零 PUT、草稿清除」（`docs/evidence/wri-013/xiaomi-14-pro/result.md` §2.3/§2.4）。本轮把撤销 / 选区随基线更新的断言补成单测：

```kotlin
@Test fun `load external clears undo history and resets caret`() = runTest {
    // 冲突 → loadExternalVersion()
    assertFalse(session.state.value.undoAvailable)
    assertFalse(session.state.value.redoAvailable)
    assertEquals(0, session.state.value.caret)
    assertFalse(session.undo())                       // 撤销不再生效
    assertEquals("标题\n段落一\n段落二服务器\n段落三\n结尾", session.state.value.content)
}
```

即「加载外部之后不能靠撤销把被丢弃的本地改动拉回来，选区也回到新正文开头」。

## 5. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL   suites=57 tests=404 failures=0
```

## 6. 未覆盖 / 已知边界

- 「加载外部后撤销按钮置灰」的**界面**断言只有单测覆盖：本轮未在真机上重新构造冲突（服务端同一行改写与 App 插入连续三次都被三方合并判为 `merged`，属 `ThreeWayMerge` 行级合并的正常结果）。
- 撤销 / 重做只作用于正文，不覆盖标签顺序、草稿状态等非文本状态。
- 撤销栈只存内存（进程被杀后清空），不持久化——与「草稿负责崩溃恢复、历史只服务当前会话」的分工一致。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
