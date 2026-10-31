# P5 成对证据（Web 390×844 / 真机 390dp）

采集日期：2026-11-01。Web 侧读数与截图见 [../web-baseline/2026-11-01](../web-baseline/2026-11-01/README.md)，
本目录只放真机侧截图与控件树。

## 环境

| 项目 | 值 |
|---|---|
| 设备 | 小米 14 Pro（`2304FPN6DC`），adb 序列号 `135babde`，Android 16 |
| 物理分辨率 / 密度 | 1440×3200 / 600dpi（默认 384dp 宽） |
| 采集时覆写 | `adb shell wm size 1463x3200` + `wm density 600` → **390.13dp** 宽 |
| 恢复 | 每轮结束执行 `wm size reset` / `wm density reset`，已复核回到 1440×3200 / 600dpi |
| 采集脚本 | `tools/p5_android_capture.py`（覆写 → 打开抽屉 → 点一级入口 → 截图 + dump → 恢复） |
| 取证 | `adb exec-out screencap -p` + `adb shell uiautomator dump` |

## 产物（本轮新增 9 屏）

| 目录 | 屏幕 | 文件 |
|---|---|---|
| `workbench/` | 工作台 | `android-390.png`、`android-390.xml` |
| `game/` | 游戏 | 同上 |
| `agent/` | 写作 Agent | 同上 |
| `debug/` | 调试日志（内部工具） | 同上 |
| `lore/` | 资料库 | 同上 |
| `presets/` | 创作方案 | 同上 |
| `versions/` | 版本管理 | 同上 |
| `books/` | 书籍管理 | 同上 |
| `skills/` | Skills | 同上 |
| `agents/` | Agents | 同上 |
| `automations/` | 自动化 | 同上 |
| `market/` | 资源中心 | 同上 |
| `settings/` | 设置 | 同上 |

此前已采：`editor/`（源码 / 富文本 / 预览）、`drawer/`（含阴影修复）、`settings/`、`library/`。

## 未采 / 缺口

1. **终端**：没有抽屉一级入口，需「编辑器顶栏 → 终端」；本轮脚本在 Debug 页收尾时无法回编辑器
   （Debug 页顶栏无抽屉按钮），未采。补采时先重启回编辑器再跑 `--only terminal`。
2. **连接页**：需退出登录，会丢当前持久化会话（下次取证要重新登录），本轮不采。
3. **占位页**：13 个一级入口全部落地后占位页应删除（见 09 §3 与 07 §5），不再作为对外表面。
4. **Web 侧成对截图未采**：需要在 Chrome 里登录测试服务器后用 `tools/web_parity_capture.py` 逐屏抓
   390×844 截图与 DOM 读数；本目录暂只有真机侧。

## 采集过程中发现的问题

- `tools/p5_android_capture.py` 首版按 `clickable="true"` 找元素，导致「工作台」等抽屉项全部匹配失败：
  Compose 把 `clickable` 放在父节点，带 `text`/`content-desc` 的子节点本身不可点。改为点文本节点中心命中父级。
- 抽屉会保留上次滚动位置，采集前必须先回顶部再向下找，否则会漏掉上方入口。
