# 09 · 视觉缺口清单（P5 输入）

> 本文是 **P5「逐屏 1:1 视觉对齐」的待办清单**，由 P4 收口后的功能验收轮次（2026-10-30）逐条对照 Web 基线
> `SeraphHsing/denova@85ec97c` 与真机现状后建立。每项都写明「现状 / 基线 / 影响面 / 怎么改」，
> 完成一项就把该行改成 `已对齐` 并附截图，不要在本文堆积新的未验证结论。

## 0. 根因：`DesignTokens` 尚未落地

> **2026-10-31 进展**：token 层已落地——`app/src/main/java/com/denova/novara/ui/theme/DesignTokens.kt`
> 按基线 `web/src/index.css` 的 `--nova-*`（颜色）与 `web/src/mobile.css`（尺寸）逐项抄写，
> `NovaraTheme` 负责映射成 `ColorScheme` / `Typography` / `Shapes` 并通过 `NovaTheme.colors` 提供；
> 抽屉、右抽屉已换成 token（[证据 1](../evidence/p5-tokens/xiaomi-14-pro/result.md)）。
> 共用件 `ui/components/NovaComponents.kt`（`NovaTopBar` / `NovaIconButton` / `NovaTextAction` /
> `NovaTextField` / `NovaButton` / `NovaSearchField`）已落地并接入连接页、编辑器顶栏与打开文件区、
> 抽屉搜索（[证据 2](../evidence/p5-tokens/xiaomi-14-pro/components.md)）。
> **2026-10-31 第三轮（收口）**：14 处页面顶栏全部换成 `NovaTopBar`，74 处输入框换成 `NovaTextField`，
> 30 处实体按钮换成 `NovaButton`，`☰ » ⋯ ⋮ ⟳ ⇅ ＋` 文本字符图标全部换成矢量图标
> （[证据 3](../evidence/p5-tokens/xiaomi-14-pro/pages.md)）。VG-001…009 已全部对齐。
> **2026-11-01 第四轮（token 全量收口）**：`DesignTokens.kt` 按 04 §3 的覆盖面补全——颜色补齐 `:root` /
> `[data-theme="light"]` 两段全部 `--nova-*`（开关、`--nova-danger-foreground`、消息气泡、浮层与选择、
> 文件树、`chip` / `style-chip` / `lore-chip`、代码块与表头、`dialogue-highlight`、滚动条、IDE 面板），
> 并新增 `NovaStroke`（描边 / 图标 `stroke-width` 1.8 / 1.7）、`NovaWeight`、`NovaTracking`（`-0.025em` / `-0.012em`）、
> `NovaFont`、`NovaShadow` + `NovaShadows`（`--nova-shadow` 深浅两套、抽屉 `0 10px 15px -3px`、字段聚焦外环）、
> `NovaMotion`（`--nova-motion-*`、面板时长、`--nova-ease` / `--nova-motion-ease` 的三次贝塞尔）；
> 半透明值的 alpha 按 `CSS 值 × 255` 写死并由 `DesignTokensTest` 锁定。
> **2026-11-01 第五轮（散落尺寸回收，第 1 批）**：四类共用件（抽屉 / 顶栏 / 输入 / 按钮）在上一轮已全部只从
> `DesignTokens` 取值，本轮清理共用件之外页面层残留的尺寸字面量——连接页 18 处、编辑器页 34 处全部改为
> `NovaSpacing` / `NovaRadius` / `NovaStroke` / `NovaSize`；为保持视觉不变，新增 `NovaSpacing.tab`（标签起始 10dp）、
> `NovaSize.tabMaxWidth` / `tabCloseBox`（120 / 18dp）、`NovaSize.menuMaxHeight`（360dp）、`NovaStroke.thick`（2dp 指示条）。
> 抽屉壳与右抽屉只剩两处语义为「归零」的 `0.dp`（`tonalElevation`、`padding(start = 0)`），不计为散落尺寸。
> 剩余 20 个 feature 文件仍有约 400 处字面量（`AgentScreen` 61、`GameScreen` 56、`AutomationsScreen` 41、
> `MarketScreen` 36、`LoreScreen` 33 等），按屏逐个回收，§3 标准 1 尚未达成。
> 仍未做的是**列表行、卡片、编辑区正文排版的尺寸字面量**（属 04 的逐屏细化，不在 VG-005…009 范围）。

`04-ui-and-editor.md` 把 `DesignTokens`（颜色、圆角、间距、字号、行高、图标尺寸的唯一来源）列为硬要求。
落地前的状况是色值手挑、尺寸散落在各 Composable 的 `dp` 字面量里，导致抽屉宽度、行高、圆角、顶栏高度、按钮高度
全部跟随 Material3 默认值，与 Web 基线系统性不一致。

**后续做法**：先落 `ui/components` 下的 `NovaTopBar` / `NovaTextField` / `NovaButton` 共用件（同样只从 token 取值），
再把各 feature 页面替换过去；每替换一类，用「Web 390×844 截图 vs 真机 390dp 截图」同屏比对并把读数写进下表。

## 1. 已确认的具体差异（本轮实测）

| # | 元素 | 现状（Android） | 基线（Web 85ec97c） | 影响面 | 状态 / 处理 |
|---|---|---|---|---|---|
| VG-001 | 抽屉宽度 | `novaDrawerWidth()` = `min(可用宽度 − 32dp, 384dp)`（右抽屉 512dp） | `24rem` 手机 / `32rem` 宽屏（≈384px / 512px），且不超过可用宽度 - 32px | 左抽屉、右抽屉 | **已对齐**（2026-10-31）：390dp→358dp、320dp→288dp，与 Web 358/288 一致 |
| VG-002 | 抽屉标题栏 | 56dp、17sp/600、字距 −0.425sp、左右 12dp、左侧 20dp 返回箭头、无分隔线 | 标题栏 56px，标题 18px/600（实测 17px），右侧动作 20px 图标 | 左抽屉、右抽屉 | **已对齐**（2026-10-31）；右侧动作图标仍待补 |
| VG-003 | 抽屉导航行高 | 52dp（工具/设置组）/ 58dp（创作组入口） | 52px 行高，写作/游戏入口 58px | 抽屉全部导航行 | **已对齐**（2026-10-31）：实测行距 196px@590dpi = 53.1dp（52 + 1px 分隔线） |
| VG-004 | 抽屉内容侧圆角 | 24dp（左抽屉圆右侧、右抽屉圆左侧） | 内容侧 24px / 18px（按断点） | 左抽屉、右抽屉 | **已对齐**（2026-10-31） |
| VG-005 | 顶栏高度与动作 | 14 处页面顶栏全部换成 `NovaTopBar`：48dp 高、左右 12dp、标题 17sp/600 单行省略、副标题 12sp/muted、左侧 `Menu` 20dp、尾部 20dp 图标；`☰ » ⋯ ⋮ ⟳ ⇅ ＋` 文本字符全部换成矢量图标 | 顶栏 48px、左右 12px，动作为 20px 线性图标 | 编辑器、工作台、游戏、终端、设置、资料库、书籍、Skills、Agents、自动化、创作方案、版本管理、资源中心、Agent 舞台、占位页 | **已对齐**（2026-10-31）：见 [pages 证据](../evidence/p5-tokens/xiaomi-14-pro/pages.md) |
| VG-006 | 输入框 | 74 处 `OutlinedTextField` → `NovaTextField`：44dp 视觉高（触控盒 48dp）、10dp 圆角、1dp 描边、label 在框外上方 16sp/500，支持多行（`minLines`/`maxLines`/`textStyle`）与 `supportingText`/`keyboardActions`/前置图标 | 32px 高、1px 描边、圆角 10px、字号 16px、label 16px/500 | 连接页、编辑器、工作台、资料库、设置、书籍、市场、Skills、Agents、自动化、预设、终端、调试面板、对话框 | **已对齐**（2026-10-31，高度 +12dp 为触控偏离） |
| VG-007 | 按钮 | 30 处 `Button`/`OutlinedButton` → `NovaButton`（44dp、10dp 圆角、16sp/500、最小宽 72dp、主/次/加粗三态），含编辑器模式行与格式按钮、游戏故事选择器 | 32px 高、10px 圆角、16px/500 | 全站实体按钮 | **已对齐**（2026-10-31）。保留口径：`TextButton`（行内 ghost 文字动作）与 M3 `FilterChip`（32dp 高，与基线 chip 一致）不替换 |
| VG-008 | 搜索框 | `NovaSearchField`（44dp、10dp 圆角、20dp 放大镜、无浮动 label）：左抽屉与右抽屉搜索 | 抽屉内搜索 40~44px、圆角 10px、左侧 20px 放大镜 | 抽屉搜索 | **已对齐**（2026-10-31） |
| VG-009 | 编辑器顶栏动作 | 保存/终端/写作/退出为 `NovaTextAction`（44dp、15sp/600），窄屏折叠进 `MoreVert` 溢出菜单；`☰`→`Menu`、`»`→右箭头、`⋯`→`MoreVert` | 图标 + 文字（窄屏折叠为图标） | 编辑器 | **已对齐**（2026-10-31）。物化图标需 `material-icons-extended`，当前文字动作 + 图标按钮 |
| VG-010 | 侧栏交替、抽屉阴影 | 左抽屉 `ModalDrawerSheet` 与右抽屉 `Surface` 都加 `NovaSize.drawerShadow`(10dp) 阴影、`clip = false`，scrim 用 `NovaColors.scrim` | 基线 `0 10px 15px -3px rgba(0,0,0,.1)` + 独立 scrim | 抽屉 | **已对齐**（2026-10-31）：阴影与 scrim 都用 token，真机 480dp 抽屉边缘可见过渡 |
| VG-011 | 「当前作品」切换卡 | 分组标签 + 书籍 `TextButton` 文本列表 + 「新建书籍」 | 单个 52px 下拉卡：图标 22px + 名称 17px + chevron | 抽屉顶部 | 待办：改成下拉卡（切换仍走 `POST /api/workspace/switch`） |
| VG-012 | 快捷动作 | 抽屉内搜索框（只过滤导航）+ 底部「消息中心」入口 + 「排序 / ↑↓ / 恢复默认」 | 「命令」「消息」两列 44px 快捷卡，搜索是独立命令面板；排序在设置/自定义里 | 抽屉 | 待办：命令面板复用现有搜索逻辑，排序入口迁到设置页 |
| VG-013 | 一级入口集合 | 13 项：写作 / 游戏 / 写作 Agent / 工作台 / 资料库 / 创作方案 / 版本管理 / 书籍管理 / Skills / Agents / 自动化 / 资源中心 / 设置 | 13 项：写作 / 游戏 / 工作台 / 资料库 / 创作方案 / 版本管理 / 书籍管理 / Skills / Agents / 自动化 / **统计（trajectory）** / 资源中心 / 设置 | 抽屉 | 待办：补「统计」入口（条件入口，需确认 Web 判定），「写作 Agent」是否保留待产品决定 |
| VG-014 | 设置页字段行内边距 | `NovaSpacing.lg`（左右 16dp）/ `NovaSpacing.md`（上 12dp） | `padding-top:14px`、左右 16px（`data-slot="field"`，见 2026-11-01 读数） | 设置页 14 个 section | **已对齐（允许差异）** 2026-11-01：按产品决定取 12dp 保持 `NovaSpacing` 阶梯，与 Web 差 2dp；左右 16dp 一致 |


## 2. Web 对照读数状态

移动视口抓图能力已建立（`tools/web_parity_capture.py`，产物在 `docs/evidence/web-baseline/2026-10-30/`
与 [2026-11-01](../evidence/web-baseline/2026-11-01/README.md)），五类读数按项目状态如下：

- **抽屉**：**已采齐**（358px 宽、内容侧 24px 圆角、标题栏 56px、标题 17px/600/−0.425px、
  工具组行 52px / 创作组行 58px、圆角 18px、`padding-left:14px`、分组标签 12px/500 `#bdbdb8`、
  图标容器 22×24）。新增读数：抽屉阴影是**两层** `0 10px 15px -3px` + `0 4px 6px -4px`（rgba(0,0,0,.1)），
  Android 目前只有一层 elevation。
- **顶栏**：**已采**（48px 高、左右 12px、背景 `--nova-bg`、标题 17px/600），Android 已按此落地（VG-005）。
  09 表中「52~58dp」是抽屉导航行，不是顶栏。
- **输入框 / 按钮 / 标签**：**已采**（输入 326×32、16px、圆角 10px、1px 描边、`padding-left:10px`；
  主按钮 326×32、16px/500；表单 label 16px/500；说明文字 16px/400；登录卡片 358×397、圆角 14px、无描边）。
  32px 与「触控目标 44×44 / Android 48dp」冲突，**2026-10-31 决定触控优先**：`NovaTextField` / `NovaButton`
  按 44dp 落地，圆角/描边/字号/内边距照抄基线；如将来要照抄 32px，只改 `NovaSize.input` / `NovaSize.button` 两个 token。
- **列表**：抽屉行高 52px / 分隔线左缩进 48px 已用于抽屉；**2026-11-01 补采**设置页表单行
  （`data-slot="field"`：`padding-top:14px`、标签 14px/20px、字段行 100.63~101.63px、末项 1px 分隔线）、
  资料库卡片（`card-header` 92px + `card-content` 86px + 操作行 44px = 223.75px）与消息中心列表
  （`.divide-y` 容器、行是 `<button>`：上下内边距各 **16px**、行高 105/125px、文字 16px/22px、1px 分隔线；
  抽屉入口文案是「消息」+ 未读角标，不是「消息中心」）。**五类读数至此采齐。**
- **编辑器**：正文 **16px / 行高 30.4px** / 400 / 字距 `normal` / 段距 0 / 内容宽 358 左边距 16 / 色 `#f7f7f4`。
  块级读数已采（探针章节 `chapters/ch00002-第2章-排版探针块.md`）：`h2` 19.2px/600/24.96 + 上 7.68 下 5.76；
  `h3` 16.8px/600/23.52；`blockquote` 左边线 **3px** `#242422` + `padding-left:16px`；`table` 上边距 12.8；
  `th` 16px/**700**、背景 `rgba(36,36,34,.64)`、`padding:6.4px 8.8px`、边框 1px；`td` 16px/400 同内边距；
  `pre` 背景 `#101010`、圆角 **6px**、`padding:12px 16px`、文字 `#e0e0dc` 16px/30.4px；行内 `code`
  14.4px、圆角 **3px**、背景 `rgba(36,36,34,.75)`、`padding:2.16px 4.32px`。
  **预览口径**：Web 手机端无 Markdown 预览模式（顶栏与 `当前标签页操作` / `编辑器设置` / `编辑工具`
  三个菜单均无预览项），Android `MarkdownPreview` 的 WebView 以「上述编辑器排版 + 块级排版」为唯一基准，
  仅容器能力差异可登记为允许差异。

> 读数与命令见 [2026-11-01 证据说明](../evidence/web-baseline/2026-11-01/README.md)；
> 已知差异：编辑器正文 Web 16px vs Android `NovaText.reading` 18sp（差 2sp，行高 30.4 vs 30.6），
> 待改 token 并在真机重测后回写。

## 3. 完成标准（P5 退出条件）

1. `DesignTokens` 存在且被抽屉/顶栏/按钮/输入四类共用件引用；`grep -r "\.dp\b" feature/` 里不再有散落的尺寸字面量（允许 token 定义处除外）。
2. 每屏都有「Web 390×844 截图 / 真机 390dp 截图」成对证据，尺寸差 ≤ 1dp（字号 ≤ 1sp）。
3. 本文第 1 节表格逐行变为 `已对齐` + 证据链接。
