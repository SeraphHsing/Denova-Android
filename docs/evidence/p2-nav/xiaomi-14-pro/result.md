# P2 导航收尾（NAV-001~010）：左抽屉分组 / 图标 / 状态对齐

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 基线来源：`web/src/components/layout/workspace-mobile-layout.tsx`（分组 creation/tools/settings、尾部 `Check`/`ChevronRight`、`navigationMenu` 标题、抽屉左滑打开）、`web/src/components/workbench/workbench-activity-order.ts`（默认顺序、保存顺序优先、新增项按默认位插入、`hiddenActivityIDs` 仅桌面生效）
- 实现：
  - `app/src/main/java/com/denova/novara/feature/shell/ActivityNav.kt`（新增：`DEFAULT_ORDER`、`insertMissing`、`sort`、`grouped`、`move`、13 个目的地的标签/分组/图标）
  - `app/src/main/java/com/denova/novara/feature/shell/AppShell.kt`（抽屉改为「固定头 + 固定底部设置区 + 中间滚动列表」；分组标题「创作 / 工具」、设置组无标题；每行图标 + 尾部 `✓`/`›`；`WIP` 标记；排序模式 ↑/↓；搜索动作过滤；新增文案全部走 `stringResource`）
  - `app/src/main/java/com/denova/novara/core/persistence/PreferencesStore.kt`（`activity_order` 持久化，作用域对齐基线的 workspace）
  - `app/src/main/java/com/denova/novara/MainActivity.kt`（顺序加载/保存、左右抽屉互斥、`BackHandler` 逐层收起）
  - `gradle/libs.versions.toml` + `app/build.gradle.kts`（新增 `material-icons-core`，图标不额外引 extended 包）
- 单测：`ActivityNavTest`（6 例：默认顺序、缺项插入、保存顺序优先、条目不丢失、`move` 边界、分组归属）

## 1. 已验证（真机）

抽屉结构（截图 [drawer-en.png](drawer-en.png)，英文界面）：

- 分组：`Creation`（`Writing` / `Story`）、`Tools`（`Writing Agent` 起其余 10 条）、无标题的 `Settings` 单条；
- 每行左侧图标、右侧尾部状态：当前页 `✓`（仅一行有），其余 `›`；
- 未实现目的地标 `WIP`，但**仍可进入占位页**（不再用 disabled 阻断导航）；
- 顶部 `Reorder`（排序）与 `Search actions`（搜索动作），底部固定 `Dark mode` / `Language` / `Debug log`；
- 语言切换「中文 / English」即时生效且抽屉内无中英混排（新增文案已全部走字符串资源）；
- 顺序表 13 条与基线一致，`settings` 不在顺序表内（固定末组）。

单测口径（`ActivityNavTest`）：

```text
DEFAULT_ORDER = editor, game, agent, workbench, lore, teller, versions, books, skills, agents, automations, market
insertMissing(stored)  → 缺项插到「默认顺序里前一个已存在项」之后，条目数不变
sort(order=custom)     → 保存顺序优先；未列出的条目仍在结果里（手机端不隐藏）
move(order, id, ±1)    → 仅移动一位，首尾越界保持原样
grouped(sorted)        → creation=[editor,game]，tools=其余 10 条，settings=单条且末位
```

## 2. 阻塞：抽屉下半部分条目在 adb 注入下点不到（未解决）

现象：抽屉打开后，**y ≈ 1700 px 以下的条目点击无任何反应**（不触发导航、抽屉也不关闭），上方条目正常：

| 条目 | y（dump） | 结果 |
|---|---|---|
| Writing / Story / Writing Agent | 598 / 754 / 997 | 正常进入 |
| Workbench / Lore / Teller / Versions | 1153 / 1309 / 1465 / 1621 | 正常进入占位页 |
| Books / Skills / Agents / Automations / Market / Settings | ≥ 1777 | 点击无响应 |

已排除的方向：

- **不是全局触摸问题**：同一屏 y=2500 处点击编辑器正文，`EditText focused=true`；
- **不是 disabled**：这些行的 `TextButton` 已去掉 `enabled` 限制；
- **不是内容被裁掉**：`uiautomator dump` 报告了完整坐标，截图也渲染出来了；
- **不是滚动位置错位**：把抽屉滚到「`Navigation` 在 y=362、`Books` 在 y=1992」的状态后按该坐标点击，仍无响应；
- **换成两段式布局（滚动区 `weight(1f)` + 固定底部）后现象不变**，说明与旧的整体 `verticalScroll` 无关。

仍待排查：`ModalDrawerSheet` 内部滚动/裁剪与 Compose 点击分发；也可能与该机 `wm density 521` 覆盖值有关（本机 `Physical density 600 / Override density 521`，是此前视口实验留下的设置）。

## 3. 本轮未完成的行

| 行 | 状态 | 说明 |
|---|---|---|
| NAV-001 完整一级入口 | 未开始 | 顺序与唯一高亮已证；**13 个目的地只逐项进入 7 个**（3 实现 + 4 占位），其余 6 个受 §2 阻塞 |
| NAV-002 正文Agent切换 | 未开始 | 未验证草稿与滚动保留 |
| NAV-003 左右抽屉 | 未开始 | 互斥与 `BackHandler` 已实现；真机验证被 §2 与一次意外登出打断 |
| NAV-004 菜单排序 | 未开始 | 排序模式、顺序持久化、单测齐备；真机排序按钮与重启后顺序未验 |
| NAV-005 手机可见性 | **已完成** | 13 条入口在手机端全部渲染（无 hiddenActivityIDs 过滤），`sort` 不丢未知/未列出条目由单测固定 |
| NAV-006 命令搜索 | 未开始 | 搜索框已实现（输入即过滤、无匹配提示）；真机输入未生效（搜索框坐标随滚动变化，未取到可点位置） |
| NAV-007 主题语言 | 未开始 | 语言切换已验证无混排；但编辑器「项目ID / 文件路径」、章节段等**仍有硬编码中文**，未达「全部控件单语」 |
| NAV-008 边缘手势 | 未开始 | 左边缘手势由 `ModalNavigationDrawer` 提供（未验阈值）；右抽屉无边缘手势，需补 |
| NAV-009 浮层与系统返回 | 未开始 | `BackHandler` 已加（右抽屉 → 左抽屉 → 页面）；逐层返回未验 |
| NAV-010 窄屏横屏字号 | 未开始 | 未测 320dp / 844 横屏 / 大字号 |

## 4. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL   suites=61 tests=432 failures=0
```

## 5. 建议

1. §2 的触摸问题建议**人工在真机上点一次「设置」**确认是否为注入通道限制：若手指可点、仅 adb 不行，则登记为工具限制；若手指也点不到，则是真实缺陷，需要按 `ModalDrawerSheet` 嵌套滚动方向排查。
2. 复核本机遗留的 `wm density 521`（物理 600）与 `font_scale 0.9` 是否影响抽屉高度与点击区域。
3. NAV-007 的硬编码中文建议单独一轮做 i18n 普查（编辑器、章节、变更、版本、终端各屏），而不是散落在各切片里补。
