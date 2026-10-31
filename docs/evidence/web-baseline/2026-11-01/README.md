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
| `editor-390.png` | 编辑器正文（章节「第1章 排版探针」，2 段中文正文） |
| `library-390.png` / `library-bottom-390.png` | 资料库（卡片列表 + 工具条） |
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

> 代码块、表格、引用块本轮**未采到**：探针章节只有纯段落。编辑器工具栏可插入代码块/表格，
> 但脚本化插入需要额外交互；这三项留待下一轮补采（连同预览 WebView 单独口径）。

## 测试数据说明

测试账号的「晏宫-十妃定九州」原为 0 章。为取编辑器排版读数，本轮通过产品自带的
「打开文件 → 分卷章节 → + → 新建章节」创建了 **`chapters/第1章 排版探针`**（63 字，两段中文），
该章节参与自动保存。若要清理，删除该书 `chapters/` 下对应文件即可（不影响其它作品）。
采集过程中曾临时把当前作品切到 `/data/.denova/projects/1`，**已切回「晏宫-十妃定九州」并复核**。

## 与 Android 现状的差异（登记到 09）

1. 编辑器正文字号 16px vs `NovaText.reading` 18sp（行高 30.4 vs 30.6）。
2. 抽屉阴影基线是两层（`10/15/-3` + `4/6/-4`），Android 只有 `elevation = 10dp` 一层。
3. 设置页字段行 `padding-top:14px` 不在 `NovaSpacing` 阶梯（12/16），需决定是新增 token 还是就近取 12。
4. 资料库卡片网格（223.75px 卡高、92/86/44 分段）尚无 token，属列表/卡片类后续对齐项。
5. 代码块 / 表格 / 引用块读数缺失，编辑器排版类未采全。
