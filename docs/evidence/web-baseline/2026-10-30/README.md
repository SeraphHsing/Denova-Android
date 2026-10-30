# Web 移动视口基线抓图（2026-10-30）

用途：为 NAV / AUTH 行的真机验收提供 Web 端同场景真实截图与 computed 尺寸读数，作为「1:1 复刻」对照的 Web 侧证据。

## 环境与采集方式

| 项目 | 值 |
|---|---|
| 固定 Web 基线 | `SeraphHsing/denova@85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e` |
| 采集对象 | `http://192.168.2.102:18081`（后端 0.6.3-b） |
| 工具 | `tools/web_parity_capture.py`（Python + Playwright，`--channel chrome`，不进 APK） |
| 视口 | 390×844、320×640，`device_scale_factor=3`、`is_mobile=true`、`has_touch=true` |
| 语言 | zh-CN（Web 默认语言） |
| 主题 | 默认深色（基线无 `data-theme` 时 `--nova-bg:#000`）；浅色由 `--scheme light` 置 `data-theme="light"` |
| 凭证 | 仅由环境变量注入，截图与读数不含用户名、密码、Cookie |

复现命令（凭证走环境变量，不入库）：

```bash
DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \
  python tools/web_parity_capture.py --scene all --widths 390,320 --channel chrome
DENOVA_BASE=... DENOVA_USER=... DENOVA_PASS=... \
  python tools/web_parity_capture.py --scene all --widths 390 --scheme light --channel chrome
```

## 产物

| 文件 | 场景 |
|---|---|
| `login-390.png` / `login-320.png` / `login-390-light.png` | 未认证登录页（Web 无服务器地址输入，地址即浏览器 origin） |
| `workspace-390.png` / `workspace-320.png` / `workspace-390-light.png` | 登录后的写作工作台首屏 |
| `drawer-390.png` / `drawer-320.png` / `drawer-390-light.png` | 左抽屉（导航菜单）打开态 |
| `drawer-bottom-*.png` | 抽屉滚到底（工具与资料组 + 设置条目） |
| `command-panel-390.png` / `command-panel-320.png` / `command-panel-390-light.png` | 「命令」按钮打开的命令面板 |
| `metrics.json` | 每个场景的 computed 尺寸与文案（可脚本比对） |

## 关键读数（390 宽，深色）

| 项目 | Web 实测 | 备注 |
|---|---|---|
| 顶栏高度 | 48px，左右 padding 12px | `--nova-mobile-title-size` = `max(17px, UI字号)` |
| 左抽屉宽 | 358px（`100% - 2rem`），上限 24rem | 320 宽时为 288px |
| 抽屉圆角 | 左抽屉 `0 24px 24px 0`；右抽屉 `24px 0 0 24px` | 内容区一侧 24px |
| 抽屉标题栏 | 56px，标题 17px/600，letter-spacing −0.425px（−0.025em） | 左端返回箭头图标 20px、stroke 1.8 |
| 分组标签 | 12px/500，色 `#bdbdb8`，左缩进 12px、下边距 8px | 「当前作品」「创作」「工具与资料」 |
| 创作组行（写作 / 游戏） | 157.5×**58**，圆角 18px，padding 14px，15px/600，两列 | 选中：深色主题浅底 `oklch(0.922)`；浅色主题深底白字 |
| 工具组行 | 325×**52**，组容器圆角 18px，行间分隔线，尾部 ChevronRight 14px | 图标 22px 容器 / svg 20px、stroke 1.7 |
| 一级目的地（13 项） | 写作、游戏、工作台、资料库、创作方案、版本管理、书籍管理、Skills、Agents、自动化、**统计**、资源中心、设置 | 「统计」= trajectory 条件入口 |
| 登录卡片 | 358×397，圆角 14px，1px 描边 | 标题 16px/500，描述 16px/400 muted |
| 登录输入框 | 326×**32**，圆角 10px，16px 字号，padding 10px | 空表单提交按钮 `disabled=true` |
| 登录按钮 | 326×32，16px/500 | 提交中文案为「登录」 |

## 与 Android 现状的差异（本轮登记，P5 逐屏对齐）

1. **信息架构**：Web 手机端一级导航含「统计」（trajectory），无独立「写作 Agent」入口（Agent 是写作页右侧面板 / 工作台内）；Android 当前 13 项为「写作 / 游戏 / 写作 Agent / 工作台 / 资料库 / 创作方案 / 版本管理 / 书籍管理 / Skills / Agents / 自动化 / 资源中心 / 设置」，缺「统计」且多「写作 Agent」。
2. **抽屉结构**：Web = 标题栏（返回箭头 + 导航菜单）+「当前作品」单个下拉切换卡 +「命令 / 消息」两列快捷动作 +「创作」两列大卡 +「工具与资料」整组卡片列表 + 设置条目；Android = 文本标题 + 书籍文本列表 + 排序按钮 + 内嵌搜索框 + 图标列表 + 深色/语言开关 + 消息/调试。深浅主题与语言开关在 Web 属设置页，不在抽屉内。
3. **搜索交互**：Web 的「命令」打开命令面板（搜索命令、按组列出动作：保存当前章节 / 打开创作 Agent / 打开版本管理 / 打开全局搜索 / 继续写作 / 切换右侧栏，带快捷键标注）；Android 是抽屉内搜索框，只过滤导航目的地，无动作语义。
4. **排序交互**：Web 在设置/自定义中以拖拽重排（dnd-kit）；Android 是抽屉内「排序 / ↑↓ / 恢复默认」。
5. **尺寸**：抽屉宽、圆角、标题栏 56、行高 52/58、图标 20/22、字号 17/15/12、登录输入框 32 高——Android 当前用 Material3 默认值（`ModalDrawerSheet` 336dp / 16dp 圆角、`TextButton` 40dp 行高等），需在 P5 统一到上表数值。
6. **触控目标冲突**：Web 登录输入框与按钮实测高 32px，低于规格「常规触控目标 44×44」；Android 需产品决定（照抄视觉 vs 保证 44dp 可点），登记为待决定项。

> 上表差异不在本轮（功能与验收）内实现，已汇总进 `docs/android-web-parity/09-visual-gap-backlog.md` 作为 P5 输入。
