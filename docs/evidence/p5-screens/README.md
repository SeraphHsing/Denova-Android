# P5 成对证据（Web 390×844 / 真机 390dp）

采集日期：2026-11-01。Web 侧读数与截图见 [../web-baseline/2026-11-01](../web-baseline/2026-11-01/README.md)，
本目录只放真机侧截图与控件树。

## 环境

| 项目 | 值 |
|---|---|
| 设备 | 小米 14 Pro（`2304FPN6DC`），adb 序列号 `135babde`，Android 16 |
| 物理分辨率 / 密度 | 1440×3200 / 600dpi（默认 384dp 宽） |
| 采集时覆写 | `adb shell wm size 1463x3200` + `wm density 600` → **390.13dp** 宽 |
| 恢复 | 采集结束执行 `wm size reset` / `wm density reset`，已复核回到 1440×3200 / 600dpi |
| 构建 | `app/build/outputs/apk/debug/app-debug.apk`（含本轮 token、行高、阴影、预览改动） |
| 取证 | `adb exec-out screencap -p` + `adb shell uiautomator dump` |

## 产物

| 目录 | 屏幕 | 文件 |
|---|---|---|
| `editor/` | 编辑器 | `android-390-source.png`（源码）、`android-390-rich.png`（富文本）、`android-390-preview.png`（预览 WebView）、`android-390-source.xml` |
| `drawer/` | 左抽屉 | `android-390.png`、`android-390-shadow.png`（阴影修复后）、`android-390.xml` |
| `settings/` | 设置页 | `android-390.png`、`android-390.xml` |
| `library/` | 资料库 | `android-390.png`、`android-390.xml` |

## 读数比对（容差 ≤1dp / ≤1sp）

`uiautomator` 只能取盒模型，取不到字号与行高；字号类按 `DesignTokens` 的 token 定义与 Web computed 值比对。

| 项目 | Web（390 深色） | Android | 差值 | 判定 |
|---|---|---|---|---|
| 抽屉宽度 | 358px | 358.13dp（1343px @600dpi） | 0.13 | 一致 |
| 抽屉内容侧圆角 | 24px | `NovaRadius.sheet` = 24dp | 0 | 一致 |
| 抽屉阴影 | 两层 `10/15/-3` + `4/6/-4` | 两层渐变自绘（像素 138→166 平滑） | — | 一致（近似实现，见下） |
| 顶栏高度 | 48px | `NovaSize.topBar` = 48dp | 0 | 一致 |
| 顶栏左右内边距 | 12px | `NovaSize.topBarPadding` = 12dp | 0 | 一致 |
| 编辑器正文字号 | 16px | `NovaText.editorBody` = 16sp | 0 | 一致 |
| 编辑器正文行高 | 30.4px | `NovaText.editorBody` = 30.4sp | 0 | 一致 |
| 代码块圆角 / 内边距 | 6px / 12×16 | 预览 CSS 同步 | 0 | 一致 |
| 设置行内边距（上） | 14px | 12dp | **2** | 允许差异（09 VG-014，产品决定保阶梯） |
| 输入框高度 | 32px | 44dp | **12** | 允许差异（09 VG-006，触控优先） |

### 阴影实现说明

`drawBehind` + `BlurMaskFilter`（硬件加速不支持 MaskFilter）与 `Modifier.shadow` 叠加两种实现，
真机边界像素采样均为 `255 → 168` 硬切，等于没有阴影。最终改为 `drawWithContent` 在抽屉朝内容区
一侧按每层 blur 宽度画线性渐变，采样 `138→141→146→154→161→166` 平滑过渡。
`spread` 与 CSS blur 的精确等价仍属近似，登记在 09 VG-010。

## 未采 / 缺口

1. **消息中心**：Android 抽屉没有「消息」/「命令」快捷动作（Web 有），当前仅能从 Web 进入；
   属 09 VG-012 的实现缺口，本轮不伪造真机证据。
2. 其余 14 个页面（工作台、创作方案、版本管理、书籍、Skills、Agents、自动化、资源市场、终端、
   游戏、Agent 舞台、Debug、连接页、占位页）尚未逐屏采集，属 §3 标准 2 的剩余工作量。

## 采集过程中发现并已处理的数据问题

`nav002-long.md`（NAV-002 长文档夹具）正文里混入了**测试账号用户名与密码明文**，来源是此前会话
用 `adb shell input text` 往编辑器打字时误输入并自动保存。本轮已通过
`PUT /api/projects/project-efb2ed8ad64c839df4b72c3a69772350/files/file` 就地清理：
把 `滚<乱码>动位置` 恢复成 `滚动位置`（1200 处）、删除混入的用户名，并在清理后重新拉取确认
`Hsing` / 密码片段计数为 0。含敏感内容的中间截图已删除，未进入本目录。
