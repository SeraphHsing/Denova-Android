# Web 移动视口基线抓图（2026-11-01，09 §2 五类读数补采）

用途：补齐 [09 §2](../../../android-web-parity/09-visual-gap-backlog.md) 列出的五类读数（抽屉、顶栏、
输入/按钮/标签、列表、编辑器排版），作为 P5「每屏 Web 390×844 / 真机 390dp 成对证据」的 Web 侧基准。
本目录与 [2026-10-30](../2026-10-30/README.md) 并列，**不覆盖**上一轮产物。

## 环境与采集方式

| 项目 | 值 |
|---|---|
| 固定 Web 基线 | `SeraphHsing/denova@85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e` |
| 采集对象 | `http://192.168.2.102:18081`（后端 0.6.3-b） |
| 工具 | `tools/web_parity_capture.py`（Python + Playwright，`--channel chrome`，不进 APK） |
| 视口 | 390×844，`device_scale_factor=3`、`is_mobile=true`、`has_touch=true` |
| 语言 / 主题 | zh-CN / 默认深色（无 `data-theme`，`--nova-bg:#000`） |
| 凭证 | 仅由 `DENOVA_USER` / `DENOVA_PASS` 环境变量注入，截图与读数不含用户名、密码、Cookie |

复现命令（凭证走环境变量，不入库）：

```bash
DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \
  python tools/web_parity_capture.py --out docs/evidence/web-baseline/2026-11-01 \
    --scene all --widths 390 --channel chrome
```

场景可逗号分隔单独重跑（`--scene editor,library,settings`）；`metrics.json` 按 key 合并，
不会覆盖上一次运行的读数。

## 产物

| 文件 | 场景 |
|---|---|
| `login-390.png` | 未认证登录页（卡片 / label / 输入 / 按钮） |
| `workspace-390.png` | 登录后的写作工作台首屏 |
| `drawer-390.png` / `drawer-bottom-390.png` | 左抽屉（导航）打开态 / 滚到底 |
| `command-panel-390.png` | 「命令」打开的命令面板 |
| `editor-390.png` | 编辑器正文（章节「第2章 排版探针块」：二级/三级标题、段落、引用、GFM 表格、fenced 代码、行内代码） |
| `library-390.png` / `library-bottom-390.png` | 资料库（卡片列表 + 工具条） |
| `messages-390.png` / `messages-bottom-390.png` | 消息中心（左上「消息」入口打开的面板：全部/待处理/自动化/产品 + 列表） |
| `settings-390.png` / `settings-bottom-390.png` | 设置页（`data-slot=field` 表单行） |
| `metrics.json` | 每个场景的 computed 尺寸、`listRows`、`controls`、`editor` 块与页面文案 |

`*-bottom-*` 变体本轮与首屏**完全相同**：基线的滚动容器不是 `documentElement`，脚本的滚底
语句未命中，截图保留以便下次修正选择器后对比，不作为读数依据。

## 五类读数（390 宽，深色，computed style）

### 1. 抽屉

| 项目 | 实测 | 对应 token |
|---|---|---|
| 抽屉宽度 | 358（= `100% - 2rem`，上限 24rem） | `novaDrawerWidth()` → `NovaSize.drawerMaxStart` |
| 内容侧圆角 | `0 24px 24px 0` | `NovaRadius.sheet` |
| 抽屉阴影 | `0 10px 15px -3px rgba(0,0,0,.1)` + `0 4px 6px -4px rgba(0,0,0,.1)` | `NovaShadows.drawer`（第二层未落） |
| 标题栏 | 56px 高，左右 12px | `NovaSize.drawerHeader` / `drawerHeaderPadding` |
| 标题 | 17px / 600 / 22px，letter-spacing **−0.425px**（−0.025em） | `NovaText.drawerTitle` |
| 分组标签 | 12px / 500，色 `rgb(189,189,184)` = `#bdbdb8` | `NovaText.sectionLabel` + `colors.textMuted` |
| 创作组行（写作/游戏） | 157.5×**58**，圆角 18px，`padding-left:14px`，15px/600 | `NovaSize.navEntryRow` / `navRowPadding` / `NovaRadius.group` |
| 导航图标容器 | 22×24（svg 20px） | `NovaSize.iconNavBoxWidth/Height` |

### 2. 顶栏

| 项目 | 实测 |
|---|---|
| 高度 | **48px**，宽 390（满宽） |
| 左右内边距 | 12px |
| 背景 | `rgb(0,0,0)`（`--nova-bg`），移动顶栏 `box-shadow:none`、下边线用 `--nova-border-soft` |
| 标题 | 17px / 600 / −0.425px（`--nova-mobile-title-size`） |

> 09 表中「顶栏 52~58dp」是把**抽屉导航行**（52 工具组 / 58 创作组）误记为顶栏；
> 顶栏自 2026-10-30 采到的读数一直是 48px，Android `NovaSize.topBar = 48.dp` 与之一致。

### 3. 输入 / 按钮 / 标签

| 项目 | 实测 | 对应 token / 偏离 |
|---|---|---|
| 登录卡片 | 358×397，圆角 14px，`border-width: 0` | `NovaRadius.card`；Android 卡片未加描边，一致 |
| 输入框 | 326×**32**，16px 字号，圆角 10px，1px 描边，`padding-left:10px` | `NovaSize.input`（44dp，触控偏离 +12dp，VG-006） |
| 表单 label | 16px / 500 | `NovaText.fieldLabel` |
| 说明文字 | 16px / 400 | `NovaText.large` |
| 主按钮 | 326×**32**，16px / 500 | `NovaSize.button`（44dp，VG-007）、`NovaText.buttonLabel` |
| 资料库工具条按钮 | 高 28px（图标 + 文字小按钮） | 未建 token，属 VG-007 保留口径（`TextButton` / `FilterChip`） |

### 4. 列表

设置页（`data-slot="field"`，共 63 个可见字段行）：

| 项目 | 实测 |
|---|---|
| 行 `padding-top` | **14px** |
| 行标签 | 14px / 20px 行高，色为 `--nova-text` |
| 单字段行高（label + 控件） | 100.63 ~ 101.63px（控件 44px） |
| 行间分隔线 | 末项 `border-bottom: 1px`（`--nova-border-soft`） |

资料库（卡片网格）：

| 项目 | 实测 |
|---|---|
| 卡片 | `card-header` 92px + `card-content` 86px + 页脚 44px，整卡 223.75px |
| 卡内文字 | 16px / 22px 行高 |
| 卡片操作按钮 | 高 44px |

消息中心（抽屉「消息」→ 全屏面板，列表容器 `.divide-y`、行是 `<button>`）：

| 项目 | 实测 |
|---|---|
| 列表行上下内边距 | **16px**（`px-2 py-4`） |
| 列表行高 | 105px（两行文本）/ 125px（三行文本） |
| 行内文字 | 16px / 22px 行高 |
| 行间分隔线 | 1px（`divide-y`） |
| 分组页签 | Tab 高 44px（容器 52px），「全部 / 待处理 / 自动化 / 产品」 |
| 面板标题 | 16px / 500（「消息中心」） |

### 5. 编辑器排版

| 项目 | 实测 | 现状 / 差异 |
|---|---|---|
| 正文容器 | 宽 358，左边距 16，内容区 `padding-left:0` | — |
| 正文字号 | **16px** | Android `NovaText.reading` = **18sp**，差 2sp，待改 |
| 行高 | **30.4px**（≈1.9×） | Android 30.6sp（1.7×），差 0.2sp |
| 字重 / 字距 | 400 / `normal` | 一致 |
| 段间距 | `margin: 0`，段间靠行高 | Android 段间靠行高，一致 |
| 颜色 | `rgb(247,247,244)` = `#f7f7f4` | `NovaColors.Dark.text` 一致 |
| 字体栈 | `SF Pro Text, SF Pro Display, PingFang SC, …` | `NovaFont.reading`（系统无衬线） |

块级排版（同一章节里的标题 / 引用 / 表格 / 代码块，全部 computed style）：

| 块 | 实测 |
|---|---|
| `h2` | 19.2px / 600 / 24.96px（1.3×），`margin: 7.68px 0 5.76px` |
| `h3` | 16.8px / 600 / 23.52px（1.4×），`margin: 5.88px 0 3.36px` |
| `blockquote` | 左边线 **3px** `rgb(36,36,34)`，`padding-left: 16px`，文字 16px/30.4px |
| `table` | `margin: 12.8px 0`，无背景/圆角 |
| `th` | 16px / **700**，背景 `rgba(36,36,34,.64)`，`padding: 6.4px 8.8px`，边框 1px `rgba(36,36,34,.76)` |
| `td` | 16px / 400，`padding: 6.4px 8.8px`，边框 1px 同上 |
| `pre` | 背景 `rgb(16,16,16)`、圆角 **6px**、`padding: 12px 16px`、`margin: 8px 0`，文字 `#e0e0dc` 16px/30.4px（等宽字体） |
| 行内 `code` | 14.4px、圆角 **3px**、背景 `rgba(36,36,34,.75)`、`padding: 2.16px 4.32px` |

> 插入方式：不依赖编辑器工具栏，直接用后端既有 `PUT /api/projects/:id/files/file`
> （`base_revision: "missing"` 表示新建）写入固定样本章节，保证可复现、逐字节稳定。

### 5.1 预览口径（Web 手机端无独立预览）

探测了编辑器顶栏与三个菜单（`当前标签页操作` / `编辑器设置` / `编辑工具`）：
手机端**没有 Markdown 预览模式**，唯一能看到 Markdown 渲染结果的地方就是编辑器渲染态本身。
因此 Android `MarkdownPreview`（WebView）的对齐口径定为：
**以上表「编辑器排版 + 块级排版」为唯一基准**，预览 HTML 的正文/标题/引用/表格/代码样式必须与上表一致；
差异只允许出现在 Web 不存在的容器能力上（如 WebView 的滚动容器、图片加载方式），并在 09 登记。

## 测试数据说明

测试账号的「晏宫-十妃定九州」原为 0 章。为取编辑器排版读数，本轮通过产品自带的
「打开文件 → 分卷章节 → + → 新建章节」创建了 **`chapters/第1章 排版探针`**（63 字，两段中文），
该章节参与自动保存。若要清理，删除该书 `chapters/` 下对应文件即可（不影响其它作品）。

第二轮又用 `PUT /api/projects/project-7b8d52726cc4e7db0c02d0a09c27e113/files/file`
（`base_revision: "missing"`）写入 **`chapters/ch00002-第2章-排版探针块.md`**（163 字），
内容为固定样本：`##`/`###` 标题、段落、`>` 引用、GFM 表格、```kotlin 代码块、行内代码。
两个探针章节都只用于 P5 读数，可随时删除。

采集过程中曾临时把当前作品切到 `/data/.denova/projects/1`，**已切回「晏宫-十妃定九州」并复核**。

## 与 Android 现状的差异（登记到 09）

1. 编辑器正文字号 16px vs `NovaText.reading` 18sp（行高 30.4 vs 30.6）。
2. 抽屉阴影基线是两层（`10/15/-3` + `4/6/-4`），Android 只有 `elevation = 10dp` 一层。
3. 设置页字段行 `padding-top:14px` 不在 `NovaSpacing` 阶梯（12/16），需决定是新增 token 还是就近取 12。
4. 资料库卡片网格（223.75px 卡高、92/86/44 分段）尚无 token，属列表/卡片类后续对齐项。
5. 编辑器块级排版缺 token：`pre` 的 6px 圆角 / 12×16 内边距、行内 `code` 的 3px 圆角与半透明底、
   表格 `th` 的 700 字重与 6.4×8.8 单元格内边距、`blockquote` 的 3px 左边线，
   `DesignTokens` 目前没有对应项（预览 CSS 重写时一并补）。
6. 消息中心行：Web 上下各 16px、行高 105/125；Android 消息卡片未按此收敛。
7. 预览：Web 手机端无预览模式，口径以上文「编辑器排版 + 块级排版」为准。
