# P2 导航收尾（NAV-001~010）：左抽屉分组 / 图标 / 状态对齐

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 基线来源：`web/src/components/layout/workspace-mobile-layout.tsx`（分组 creation/tools/settings、尾部 `Check`/`ChevronRight`、`navigationMenu` 标题、`disabled?: boolean` 行属性）、`web/src/components/workbench/workbench-activity-order.ts`（默认顺序、保存顺序优先、新增项按默认位插入、`hiddenActivityIDs` 仅桌面生效）、`web/src/features/settings/settings-sections.ts`（设置是 14 个 section 的真实页面）
- 实现：
  - `app/src/main/java/com/denova/novara/feature/shell/ActivityNav.kt`（新增：`DEFAULT_ORDER`、`insertMissing`、`sort`、`grouped`、`move`、13 个目的地的标签/分组/图标）
  - `app/src/main/java/com/denova/novara/feature/shell/AppShell.kt`（抽屉改为「固定头 + 中间滚动列表 + 固定底部设置区」；分组标题「创作 / 工具」、设置组无标题；每行图标 + 尾部 `✓`/`›`；**未实现行 `WIP` 且 `enabled=false`，不渲染 `›`**；排序模式 ↑/↓；搜索动作过滤；新增文案全部走 `stringResource`）
  - `app/src/main/java/com/denova/novara/core/persistence/PreferencesStore.kt`（`activity_order` 持久化，作用域对齐基线的 workspace）
  - `app/src/main/java/com/denova/novara/MainActivity.kt`（顺序加载/保存、左右抽屉互斥、`BackHandler` 逐层收起）
  - `gradle/libs.versions.toml` + `app/build.gradle.kts`（新增 `material-icons-core`）
- 单测：`ActivityNavTest`（6 例：默认顺序、缺项插入、保存顺序优先、条目不丢失、`move` 边界、分组归属）

## 1. 已验证（真机）

抽屉结构（截图 [drawer-en.png](drawer-en.png)，英文界面）：

- 分组：`Creation`（`Writing` / `Story`）、`Tools`（`Writing Agent` 起其余 10 条）、无标题的 `Settings` 单条；
- 每行左侧图标；尾部状态：当前页 `✓`（仅一行有），**已实现但非当前页给 `›`**，未实现只给 `WIP`（没有 `›`）；
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

## 2. 未实现入口的行为：保持不可点（重要更正）

**结论：`WIP` 行是 `enabled = spec.implemented` 的 disabled 行，点击无反应，这是当前有意的行为。**

本轮排查过程中曾把「点不进去」误判成 adb 注入的触摸阻塞（怀疑下半屏手势/裁剪），并据此写过一段阻塞记录。**该判断是错的，已作废**，更正如下：

- 真实原因就在代码里：`AppShell.kt` 的分组渲染器保留了 `enabled = spec.implemented`；
- 之前脚本报告的「工作台 / 资料库 / 创作方案 / 版本管理 进入成功」是**误读**——这些行同样 disabled、点击不导航，脚本把抽屉标题 `Novara` 当成了目标页标题；
- 因此也没有「下半屏触摸失效」这回事：`uiautomator` 报出的坐标是对的，只是那些行本来就不接受点击。

当前取舍（与基线对齐）：基线 `workspace-mobile-layout.tsx` 的行组件支持 `disabled`，而基线里 13 个入口全部指向**真实页面**（例如设置是 14 个 section 的页面），所以基线不存在「WIP 又能进空壳」的中间态。Android 侧页面没做就保持 disabled + `WIP`，**不提供「点进未实现空白页」的路径**；`NAV-001`（依次打开全部目的地）因此在页面补齐前不能收口。

## 3. 本轮未完成的行

| 行 | 状态 | 说明 |
|---|---|---|
| NAV-001 完整一级入口 | 未开始 | 顺序、分组、唯一高亮已证；**只有 3 条目的地可进入**（编辑器 / 游戏 / 写作 Agent），其余 10 条 `WIP` 且 disabled，需页面补齐后才能逐项验证 |
| NAV-002 正文Agent切换 | 未开始 | 未验证草稿与滚动保留 |
| NAV-003 左右抽屉 | 未开始 | 互斥（`LaunchedEffect(drawerState.isOpen)` + 开右抽屉前 `close()`）与 `BackHandler` 已实现；真机未做「右抽屉打开后再开左抽屉」的对照 |
| NAV-004 菜单排序 | 未开始 | 排序模式、顺序持久化、单测齐备；真机「改顺序 → 重启 → 顺序保持」未验 |
| NAV-005 手机可见性 | **已完成** | 13 条入口在手机端全部渲染（不应用 hiddenActivityIDs），`sort` 不丢未列出条目由单测固定 |
| NAV-006 命令搜索 | 未开始 | 过滤与「无匹配」提示已实现；真机输入未落到搜索框（坐标随滚动变化），未验收 |
| NAV-007 主题语言 | 未开始 | 语言切换已验证无混排；编辑器「项目ID / 文件路径」、章节段、终端等仍有硬编码中文，未达「全部控件单语」 |
| NAV-008 边缘手势 | 未开始 | 左边缘手势由 `ModalNavigationDrawer` 提供（阈值未验）；右抽屉无边缘手势，需补 |
| NAV-009 浮层与系统返回 | 未开始 | `BackHandler` 已加（右抽屉 → 左抽屉 → 页面）；逐层返回未验 |
| NAV-010 窄屏横屏字号 | 未开始 | 未测 320dp / 844 横屏 / 大字号 |

## 4. i18n 全量复核（NAV-007，2026-10-26 追加）

两遍改造：第一遍把 9 个界面的 Compose 硬编码中文换成字符串资源；第二遍引入 `core/i18n/UiText`（`Res`=资源 id+参数、`Raw`=服务端原文、`resolve()` / `resolve(context)` / `debugName()`），把 ViewModel 与纯逻辑层约 150 处文案改成 `UiText`，界面侧统一 `.resolve()`。

真机切 English 后逐屏 dump（无中英混排）：

| 页面 | 关键文案 |
|---|---|
| 连接 | Server address / Connect / host:port or http(s)://host:port |
| 登录 | Username / Password / Sign in / Pair link token / Pair |
| 编辑器 | Save / Terminal / Write / Sign out / Project ID / File path / Open / Source / Rich / Preview / Undo / Redo / Find |
| 左抽屉 | Current book / + New book / Navigation / Reorder / Search actions / Creation / Tools |
| 右抽屉 | Project files / Changes / Versions / Chapters / Project directory / Refresh |
| 写作 Agent | session ID / Attach / Status: idle / Pause / Stop / Context / Assistant |
| 游戏 | story ID / branch (optional) / Attach / Status: idle / Player input / Send |
| 终端 | Terminal / Ready / Project ID / connected / Close session / Input (Enter to send) / Send |

有意保留的非界面中文：`ThreeWayMerge` 冲突标记（写进被合并文件）、`EditorScreen` 6 条 Markdown 插入模板（写进正文）、`prettyText` 截断后缀（改为语言中立 `…(N)`，因调用点在非 Composable 上下文）、语言选项「中文」（语言自称）。服务端返回的中文（章节状态串、部分 `message`）走 `UiText.Raw` 直出。

缺口：深色模式 × 语言交叉切换未逐屏验证。

## 5. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL   suites=61 tests=432 failures=0
```

## 6. 下一步

1. 要让 `NAV-001` 收口，先做页面而不是放开 disabled：优先级最高的两个是**设置**（14 个 section 的壳，已有 `PreferencesStore` 与部分 `/api/settings` 契约）和**书籍管理**（`/books/reorder`、`/books/remove`、`/books/info` 已可用）。
2. `NAV-007` 建议单开一轮 i18n 普查（编辑器、章节、变更、版本、终端各屏的硬编码中文）。
3. 顺带核对本机遗留显示设置：`wm density 521`（物理 600）、`font_scale 0.9`，此前视口实验留下的 override。
