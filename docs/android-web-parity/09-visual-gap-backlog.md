# 09 视觉与交互差异清单（P5 逐屏对齐输入）

本文件记录「已实现但与固定 Web 基线视觉/交互不一致」的条目。它**不是**验收台账：矩阵行仍是唯一验收来源（见 [parity-matrix.csv](parity-matrix.csv) 与 [01 §8](01-product-and-parity.md#8-全量完成标准)）。这里只登记对照结论、证据位置和归因，用于 P5 按公共 token / 组件 / 页面逐层修正。

**登记口径**

- 只登记「已核对过真实 Web 页面或基线源码」的差异，写明实测值与 Android 当前值；没有实测的猜测不写入。
- 每条给出归因层级：`token`（色/字号/圆角/间距等 DesignTokens）、`component`（公共组件）、`page`（单页布局或信息架构）。
- 视觉对齐在 P5 统一处理；功能与验收阶段（NAV/AUTH 等）发现差异只登记，不在本轮改。

**对照环境**

- Web：`http://192.168.2.102:18081`，固定基线 `SeraphHsing/denova@85ec97c`，390×844 / 320×640，DPR 3，zh-CN，深色（默认）与浅色（`data-theme="light"`）。
- 证据：`docs/evidence/web-baseline/2026-10-30/`（截图 + `metrics.json` computed 读数 + README 汇总）。
- Android：小米 14 Pro（Android 16），`com.denova.novara` Debug 构建。

## 1. 待修正（已核对）

| 编号 | 层级 | 差异 | Web 实测 | Android 当前 | 证据 |
|---|---|---|---|---|---|
| VG-001 | page | 一级导航缺「统计」（trajectory 条件入口），多出独立「写作 Agent」入口 | 13 项：写作 / 游戏 / 工作台 / 资料库 / 创作方案 / 版本管理 / 书籍管理 / Skills / Agents / 自动化 / **统计** / 资源中心 / 设置；Agent 是写作页右侧面板 | `ActivityNav.SPECS` 13 项含 `agent`、无 `trajectory` | `drawer-390.png`、`ActivityNav.kt` |
| VG-002 | component | 抽屉信息结构不同 | 标题栏（返回箭头 + 标题）→「当前作品」单个下拉卡 →「命令 / 消息」两列快捷动作 →「创作」两列大卡 →「工具与资料」整组卡片列表 → 设置条目 | 文本标题 → 书籍文本列表 → 排序按钮 → 内嵌搜索框 → 图标列表 → 深色/语言开关 → 消息/调试 | `drawer-390.png`、`AppShell.kt` |
| VG-003 | component | 搜索入口语义不同 | 「命令」按钮打开命令面板：搜索命令 + 分组动作（保存当前章节 / 打开创作 Agent / 打开版本管理 / 打开全局搜索 / 继续写作 / 切换右侧栏）+ 快捷键标注 | 抽屉内 `OutlinedTextField` 只过滤导航目的地 | `command-panel-390.png`、`AppShell.kt:207-215` |
| VG-004 | component | 排序交互不同 | 设置/自定义内拖拽重排（dnd-kit） | 抽屉内「排序 / ↑↓ / 恢复默认」按钮 | `WorkbenchShell.tsx:303-324`、`AppShell.kt:200-205` |
| VG-005 | token | 抽屉与行尺寸未对齐 | 抽屉宽 `100% - 2rem`（390 下 358px，上限 24rem）、内容侧圆角 24px、标题栏 56px、创作行 58px/圆角 18px、工具行 52px、图标 22px 容器 + 20px svg（stroke 1.7）、标题 17px/600（−0.025em）、分组标签 12px/500 `#bdbdb8` | `ModalDrawerSheet` 默认 336dp 宽 / 16dp 圆角；`TextButton` 40dp 行高；M3 默认字号与图标 24dp | `drawer-390.png`、`metrics.json` |
| VG-006 | token | 色板为手挑近似值，未从基线变量取值 | 深色：`--nova-bg #000`、`--nova-surface #0a0a0a`、`--nova-text #f7f7f4`、`--nova-text-muted #bdbdb8`、`--nova-border-soft #1e1e1e`；浅色：`--nova-bg #ffffff`、`--nova-surface #fafafa` 等 | `ui/theme/Color.kt` 自定义 primary `#5B5BD6`、background `#FBFAFF` / `#131318` | `metrics.json`、`Color.kt` |
| VG-008 | component | 抽屉边缘手势没有跟手位移 | 触摸移动时抽屉随位移实时跟随，松手才判定开合 | 只在抬起时判定并开合，拖动过程无位移映射 | `use-mobile-drawer-swipe.ts`、`EdgeSwipeGesture.kt` |
| VG-007 | token | 登录页尺寸与控件形态不同 | 卡片 358×397、圆角 14px、1px 描边；标题 16px/500；输入框 326×32、圆角 10px、padding 10px；按钮 326×32 | M3 `OutlinedTextField`（约 56dp 高）与 `Button` 默认 40dp，圆角 4/20dp | `login-390.png`、`metrics.json` |

## 2. 待决定（需产品判断，不阻塞 P5 其余条目）

| 编号 | 决策点 | Web 实测 | 冲突 |
|---|---|---|---|
| VD-001 | 登录输入框与按钮高度 | 32px | 与 [04 §2](04-ui-and-editor.md#2-从源码提取的首批参数)「常规触控目标至少 44×44」冲突：照抄 32dp 视觉会让可点区域偏小，取 44dp 则不是 1:1 |

## 3. 回填规则

- 每条差异在 P5 修正并真机对照通过后，在本表标记「已修正」并追加证据链接；只改视觉不改行为的条目不需要回写矩阵行。
- 若差异导致某矩阵行 `steps` 无法满足（如 VG-001 影响 NAV-001 的「默认顺序与唯一高亮对应」），以矩阵行为准，视觉差异作为该行的 `gap_or_decision` 记录。
