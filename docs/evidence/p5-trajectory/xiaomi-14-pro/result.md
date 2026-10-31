# 统计页（trajectory）真机验证 — 小米 14 Pro

采集日期：2026-11-01。对应矩阵 `TRJ-001…003`、[09 VG-013](../../android-web-parity/09-visual-gap-backlog.md)。

## 环境

| 项目 | 值 |
|---|---|
| 设备 | 小米 14 Pro（`2304FPN6DC`），adb 序列号 `135babde`，Android 16 |
| 分辨率 / 密度 | 1440×3200 / 600dpi（384dp 宽，未覆写） |
| 构建 | `app/build/outputs/apk/debug/app-debug.apk`（commit `e074d31` 之后的修复构建） |
| 服务器 | 测试服务器（版本 0.6.3，固定基线 85ec97c），开发者模式经设置页开启 |
| 取证 | `adb shell screencap -p` + `uiautomator dump`（`tools/ui_dump.py`）

## 前置：开发者模式是服务端开关

- 设置 → 分区「高级」→ `labs.developer_mode`，三态 chip（继承 / 开 / 关），继承值初始为 `false`。
- 选「开」→ 保存 → 提示「设置已保存」。
- 抽屉随即出现「统计」，位置在「写作 Agent」之后、「工作台」之前（与基线
  `DEFAULT_ACTIVITY_ORDER` 的 `agentchat, trajectory` 顺序一致）。
- 关闭开发者模式时抽屉不出现该入口，但路由保留（深链/恢复可解析）。

## TRJ-001 全局运行目录与筛选

| 步骤 | 结果 |
|---|---|
| 进入统计页 | 顶栏「轨迹」+ 副标题「跨所有已注册项目检查 Run 与归因反馈」；动作：运行记录 / 刷新 / 导出 |
| 目录加载 | `catalog loaded runs=100 issues=0`（`GET /api/agent-runs?limit=100`） |
| 运行记录面板 | 标题「运行记录 100」；组织方式 **Run / 会话** 可切换 |
| 会话组织 | 按「项目 → 会话」分组，会话行显示标题与 `N 个 Run`（实测 `verify-hash-1030` 2 个、`verify-slow-1030` 3 个、`# Image Generation Request` 4 个、`无 Session` 分组） |
| Run 组织 | 单条 Run 行：状态点 + 状态文案（`已完成`）+ Agent（`ide`）+ 时间（`10-30 20:14`）+ 耗时（`4m 28s`）+ `2 模型 · 1 工具` |
| Agent 筛选 | 顶栏筛选在基线里是 `hidden … sm:flex`（<640px 隐藏），390dp 下不出现；Android 同样不出现，口径一致 |

## TRJ-002 运行详情（概览 / 对话 / 时间线 / 原始）

选中 `run-20261030T121417.204029684-1e3fd94e59a6`：

- 概览头：Run ID（含「复制 Run ID」按钮）、状态「已完成」、Agent `ide`、时间、`2 模型 · 1 工具`、`仅元数据` 徽章（服务端 `content_captured=false`）。
- 指标条：总耗时 `4m 46s`、模型 `2 · 19.3 s`、工具 `0 · 0 ms`、首字延迟 `P50 8.19 s · P95 8.93 s`。
  「工具 0」与摘要里的「1 工具」并存：指标按 span 归因（`tool_call` span），摘要按服务端计数，与基线
  `trajectory-analysis.ts` 的口径一致，属预期。
- 对话视图：按请求分组（本 Run 为 `requests=0`，只显示元数据空态）。
- 时间线视图：尺度切换 **实际时间 / 压缩空闲 / 等宽步骤**；行显示模型名 `gpt-5.6-luna` 与段耗时 `8.85 s`。
- 原始视图：格式化 JSON（`summary` → `id` / `created_at` / `path` …）。
- 日志：`trace loaded run=… records=10 spans=3 requests=0 truncated=false content=false`。

## TRJ-003 导出与分享

- 点「导出」→ 系统分享面板弹出，日志：`exported run=… bytes=3749 file=run-20261030T121417.204029684-1e3fd94e59a6.jsonl`。
- 分享 URI：`content://com.denova.novara.files/trajectory_exports/run-…jsonl`。
- 无 `Permission Denial`、无 `ChooserPreview: Could not read`。

## 本轮发现并修复的缺陷（均为单测覆盖不到）

1. **条件入口不刷新**：`LaunchedEffect(client, currentRoute)` 只在换页时重读 `labs.developer_mode`，
   停在设置页改完开关再打开抽屉仍看不到「统计」。改为把 `drawerState.isOpen` 也纳入 key。
2. **运行记录面板落进状态栏**：面板从屏幕顶端铺开且未加 inset，「运行记录 / Run / 会话」整行
   y≈67px 位于状态栏内，`uiautomator` 能查到但点击无效（真机实测点了两次都无反应）。
   修复：面板列加 `windowInsetsPadding(WindowInsets.systemBars)`，标题行下移到 y≈179px 后可正常切换。
3. **导出文件名污染**：`Content-Disposition` 是
   `attachment; filename="run-….jsonl"; filename*=UTF-8''run-….jsonl`，旧实现
   `substringAfter("filename=")` 把后半段一起当文件名，缓存文件与分享路径变成
   `…jsonl___filename__UTF-8__…`。新增 `attachmentFilename()`（RFC 5987 优先 + percent-decode +
   路径分隔符清洗），并加 6 个回归单测。
4. **分享授权失败**：只给内层 `ACTION_SEND` 加 `FLAG_GRANT_READ_URI_PERMISSION`，系统选择器
   `com.android.intentresolver` 读取时报 `Permission Denial`。改为 chooser 也加该 flag 并带 `ClipData`。

## 未覆盖 / 缺口

- **Web 侧成对对照未执行**（需登录 Web 端跑同一 Run）：`09 §3` 标准 2 的「Web 390×844 ↔ 真机 390dp」
  截图与读数对比随 P5 逐屏采集一并补；本文件只登记真机侧证据。
- 移动端未复刻基线的「详情位置 inline/side 切换」与时间线滚轮缩放、拖拽区间选择（触屏输入方式差异），
  已在 `TRJ-002` 的 `gap_or_decision` 登记。
- 390dp 覆写（`wm size 1463x3200` + `wm density 600`）本轮未执行，截图按设备默认 384dp 宽度采集。

## 证据文件

| 文件 | 内容 |
|---|---|
| `drawer-bookmenu.png` / `.xml` | 抽屉「当前作品」下拉卡展开（4 本书、管理书架） |
| `cmd-panel-sort.png` / `.xml` | 命令面板 + 排序模式（↑ / ↓ / 恢复默认 / 完成） |
| `run-conversation.png` / `.xml` | 统计页概览 + 指标条 + 对话视图 |
| `run-timeline.png` / `.xml` | 时间线视图（三种尺度） |
| `run-raw.png` / `.xml` | 原始 JSON 视图 |
| `runs-panel-by-run.png` / `.xml` | 运行记录面板（按 Run 组织，状态栏避让修复后） |
| `runs-panel-by-session.png` / `.xml` | 运行记录面板（按会话组织，14 个 Run 分组） |
