# P5 第二步：共用件 NovaTopBar / NovaTextField / NovaButton（真机）

- **日期**：2026-10-31
- **设备**：小米 14 Pro（`135babde`），Android 16，物理 1440×3200
- **应用**：`com.denova.novara` Debug（本轮构建）
- **档位**：`wm density 590` → 390dp（1440 / 3.6875）；物理密度 600
- **Web 对照**：`SeraphHsing/denova@85ec97c`，读数见
  [`web-baseline/2026-10-30/metrics.json`](../../web-baseline/2026-10-30/metrics.json)
- **登录**：会话 Cookie 在当前进程内有效，未重复输入凭证

## 1. 落地内容

新增 `app/src/main/java/com/denova/novara/ui/components/NovaComponents.kt`，只从 `DesignTokens` 取值：

| 组件 | 尺寸/样式 | 基线来源 |
|---|---|---|
| `NovaTopBar` | 48dp 高、左右 12dp、标题 17sp/600（省略号压缩）、左侧可选 20dp 图标、尾部动作区 | `topBar.h = 48`、`padding 12px` |
| `NovaIconButton` | 44dp 触控、20dp 图标、圆角 10dp、禁用降透明 | 动作图标 20px |
| `NovaTextAction` | 44dp 触控、15sp/600、左右 8dp | 顶栏文字动作 |
| `NovaTextField` | 44dp 高、圆角 10dp、1dp 描边（聚焦换 `fieldFocusBorder`）、label 在框外上方 16sp/500、左内边距 12dp、可带 20dp 前置图标 | `login.input`：32px 高 / 10px 圆角 / 1px 描边 / 16px 字号 |
| `NovaButton` | 44dp 高、最小宽 72dp、圆角 10dp、16sp/500；主按钮反色、次按钮 surface + 1dp 描边 | `login.submit`：32px 高 / 16px/500 |
| `NovaSearchField` | `NovaTextField` 的紧凑变体（无 label、左侧放大镜） | 抽屉搜索 40~44px |

接入范围（本切片）：

- `feature/connection/ConnectionScreen.kt`：服务器地址 / 用户名 / 密码 / 配对 token 四个输入框 + 连接 / 登录 / 配对 / 重试四个按钮。
- `feature/editor/EditorScreen.kt`：顶栏整体换成 `NovaTopBar`（`☰` → `Menu` 图标、`»` → 右箭头图标、`⋯` → `MoreVert` 图标，保存/终端/写作/退出改为 `NovaTextAction`）；项目 ID / 文件路径输入框与「打开」按钮（纵向与并排两种布局）。
- `feature/shell/AppShell.kt`：抽屉搜索框换成 `NovaSearchField`。

## 2. 真机实测

| 项 | 基线（Web 390×844） | Android 390dp | 说明 |
|---|---|---|---|
| 顶栏高度 | 48px | 48dp（`heightIn(min)`） | 与抽屉标题栏 56dp 区分，视觉与基线一致 |
| 顶栏背景 | 深色 `#000` / 浅色 `rgb(242,242,242)` | `bgDeep`（`#000` / `#f2f2f2`） | 浅色实测与基线一致 |
| 顶栏左内边距 | 12px | 12dp | — |
| 顶栏动作图标 | 20px | 20dp | `☰`/`»`/`⋯` 文本字符已移除 |
| 输入框视觉高度 | 32px | 44dp | **有意 +12dp**：32dp 低于 Android 触控下限；uiautomator 报告的触控盒 177px@590dpi = 48dp |
| 输入框圆角 / 描边 | 10px / 1px | 10dp / 1dp | — |
| 输入框字号 | 16px | 16sp | — |
| label 位置 | 框外上方 16px/500 | 框外上方 16sp/500 | — |
| 按钮高度 | 32px | 44dp | 同上，触控优先 |
| 按钮圆角 / 字号 | 10px / 16px·500 | 10dp / 16sp·500 | — |
| 抽屉搜索框 | 40~44px、圆角 10px、左侧放大镜 | 44dp、圆角 10dp、20dp 放大镜 | 原先是有浮动 label 的 56dp 输入框 |

截图：

| 文件 | 场景 |
|---|---|
| `connect-390-light.png` | 连接页（输入框 + 主按钮新样式） |
| `editor-390-light.png` | 编辑器顶栏 + 打开文件区 + 模式行 |
| `drawer-search-390-light.png` | 抽屉（顶部含新搜索框） |

## 3. 未覆盖（继续留在 09）

- **其余 14 处页面顶栏**仍是 `Row + Text("☰")`：`WorkbenchScreen`、`GameScreen`、`VersionsScreen`、`TerminalScreen`、`SkillsScreen`、`SettingsScreen`、`PresetsScreen`、`MarketScreen`、`LoreScreen`、`BooksScreen`、`AutomationsScreen`、`AgentsScreen`、`AgentScreen`、`AppShell` 的占位页。它们只在「图标字符」上等价于修好，高度/内边距尚未统一到 `NovaTopBar`。
- 编辑器**模式行**（源码 / 富文本 / 预览）与工作台内嵌顶栏仍是 Material3 胶囊按钮，属 VG-007 剩余。
- 输入框/按钮的 32px vs 44dp 取舍已按「触控优先」落地，若产品决定照抄 32px，只需改 `NovaSize.input/button` 两个 token。
