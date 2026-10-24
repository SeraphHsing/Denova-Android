# WRI-003 文件标签：打开 / 关闭 / 重排 / 返回恢复

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目：`project-efb2ed8ad64c839df4b72c3a69772350`（灵渊劫-问道）；对照项目 `project-c76485beb8745fbe8b986f5ee493b81e`（霓光巡演-性感舞团，无标签）
- 对应矩阵：`WRI-003`（写作编辑 / 文件标签；steps「打开关闭重排文件后返回」，expected_ui「标签选中和滚动对应」，expected_server_effect「每文件草稿正确保留」）
- 实现：`app/src/main/java/com/denova/novara/feature/editor/EditorTabs.kt`、`EditorViewModel.kt`、`EditorScreen.kt`、`app/src/main/java/com/denova/novara/core/persistence/EditorTabsStore.kt`
- 单测：`app/src/test/java/com/denova/novara/EditorTabsTest.kt`（11 用例）

## 1. 对齐 Web 基线的口径

| 行为 | Web 基线 | Android 实现 |
|---|---|---|
| 标签身份 | `tabKey = file:<path>` | 项目内文件路径（`EditorTabsState.paths`） |
| 显示名 | 路径末段 `tabLabel` | `EditorTabs.label`（同规则） |
| 打开 | 已存在不重复、不挪位 | `EditorTabs.open` |
| 切换 | 先 `flushEditorDraft()` 再选文件 | `activateTab` → `flush()` → `openFile` |
| 关闭当前 | flush 失败则中止；成功后优先右邻居 → 左邻居 → 首个；最后一个关闭清空 | `closeTab` 同顺序 |
| 关闭非当前 | 只移除，不切文件 | 同 |
| 重排 | 桌面拖拽；**移动端为「⋮」菜单的左移 / 右移** | 采用移动端形态：「⋮」菜单左移 / 右移 |
| 持久化 | 按 workspace 分桶写 `localStorage`（tabs + activeTab） | 按 `服务器 origin + 项目 ID` 分桶写 DataStore（`EditorTabsStore`），另存最近项目用于进程重建 |

## 2. 真机步骤与结果

日志均取自 `adb logcat -s Editor:*`。

### 2.1 打开建立标签

```text
I Editor: tabs loaded project=project-efb2…  tabs=0 active=
I Editor: opened project=project-efb2… path=g3d-a.md revision=sha256:080373e9…
（在「文件路径」框改成 g3d-b.md 后点打开）
I Editor: opened project=project-efb2… path=g3d-b.md revision=sha256:441a5cbd…
```

界面（[two-tabs.png](two-tabs.png)）：`g3d-a.md`、`g3d-b.md` 两个标签，选中项底色与下划线高亮，末位有 `×`，右侧 `⋮`。项目目录点击文件同样建标签（CREATOR.md / ideas.md 均如此），见 [four-tabs.png](four-tabs.png)。

### 2.2 切标签前 flush，草稿不丢

在 B 正文输入字符后**立即**点 A 标签（800ms 防抖尚未触发）：

```text
I Editor  : saved path=g3d-b.md revision=sha256:849051fe…      ← 切换前 flush 保存 B
I EditorPerf: perf editor-save kind=saved ms=135 draftWindow=378ms
I Editor  : opened project=project-efb2… path=g3d-a.md revision=sha256:080373e9…
```

切回 A 后正文为 A 的内容（12 行 + 先前插入的 `x`），未混入 B 的编辑；路径框、标签选中态、正文三者一致。

### 2.3 重排（⋮ → 右移）

激活 A（index 0）→ `⋮` → 右移：标签顺序变为 `g3d-b.md, g3d-a.md`，激活项仍是 A，正文未重载。顺序随后写盘（见 2.5 重启后仍为该顺序）。

### 2.4 关闭

- 关闭**非激活**标签（B）：只剩 A，无 `opened` 日志 —— 只移除、不切文件。
- 关闭**激活**标签：`opened … path=g3d-a.md`，按「右邻居 → 左邻居 → 首个」落到 A。
- 关闭**最后一个**标签：先 `saved path=g3d-a.md revision=sha256:7e04d363…`（flush 后才关），随后标签条消失、正文清空并回到「输入项目 ID 与文件路径后打开」。之后从路径框重开 A，读到 `revision=sha256:7e04d363…`，即刚才那次编辑已落库。

### 2.5 返回：页内返回 / 进程重启 / 切换书籍

- **页内返回**（编辑器 → 写作 Agent → 返回）：标签 `[g3d-a.md, g3d-b.md]` 与激活项、正文保持不变。
- **进程重启**（`am force-stop` 后重连）：

```text
I Editor: tabs loaded project=project-efb2… tabs=2 active=g3d-b.md
I Editor: tabs restored project=project-efb2… tabs=2 active=g3d-b.md
I Editor: opened project=project-efb2… path=g3d-b.md revision=sha256:849051fe…
```

  标签从 DataStore 恢复，并自动重开上次激活文件（副本 revision 与关闭前一致）。

- **切换书籍**：切到无标签的 `霓光巡演-性感舞团` → `tabs loaded project=project-c76485… tabs=0`，标签条消失、正文清空（不残留上一本书内容）；切回 `灵渊劫-问道` → `tabs loaded … tabs=1 active=g3d-a.md` + `opened … g3d-a.md`，标签与文件都回来。

### 2.6 选中标签滚入可见区

4 个标签在 1440 宽屏放得下，用 `adb shell wm size 720x1600` 缩小逻辑分辨率制造溢出（验证后 `wm size reset` 复原）：
标签条自动右滚，首标签 `g3d-a.md` 移出可见区，激活的 `ideas.md` 完整可见（[active-tab-scrolled-into-view.png](active-tab-scrolled-into-view.png)）；恢复分辨率后 4 个标签重新全部可见。

## 3. 自动化覆盖

`EditorTabsTest`（11 用例，全部通过）：打开去重 / 已存在不挪位 / 空路径忽略；关闭后右邻居 → 左邻居 → 首个 → 无剩余；重排（含同下标与越界保持原列表）；标签显示名；`activeIndex` / `isActive` / `isEmpty`。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；`testDebugUnitTest` 合计 41 suites / 246 tests / 0 failures。

## 4. 结论与未覆盖

**通过**：打开 / 关闭（三种情形）/ 重排（移动端菜单）/ 页内返回 / 进程重启恢复 / 切换书籍隔离 / 激活标签滚入可见区；切换与关闭前 flush，编辑不丢，服务端 revision 递增且读回一致。

**未覆盖（登记为缺口，不改变本条状态）**：

- 桌面端的拖拽重排未做（Android 采用移动端 Web 的「⋮ → 左移 / 右移」形态）。
- 标签固定（pin）、按 LRU 的标签数量上限、`lore` / `subagent` 两类标签未做（属其他矩阵条目）。
- 每个标签独立的滚动位置与光标位置未保留（Web 由编辑器实例保留）。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
