# P5 第一步：DesignTokens 落地 + 抽屉对齐（真机）

- **日期**：2026-10-31
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建），单 `app` 模块
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b）
- **Web 对照基线**：`SeraphHsing/denova@85ec97c`，读数与截图见
  [`docs/evidence/web-baseline/2026-10-30/`](../../web-baseline/2026-10-30/README.md)
- **档位构造**：真机物理 1440×3200，`adb shell wm density 590` → 390dp（1440/3.6875），
  `wm density 720` → 320dp（1440/4.5）；测完 `wm density reset`。
- **登录**：隔离测试账号（凭证走本机会话，未落盘、未入截图）；为稳定点击临时 `ime disable`，
  验证后恢复。

## 1. 本轮落地内容

| 位置 | 内容 |
|---|---|
| `ui/theme/DesignTokens.kt` | 新增：`NovaColors`（基线 `--nova-*` 与 `--primary` 的 sRGB 值，深浅两套）、`NovaSpacing`、`NovaRadius`、`NovaSize`、`NovaText`（字号/行高阶梯）、`novaDrawerWidth` / `novaDrawerShape` |
| `ui/theme/Theme.kt` | `NovaraTheme` 用 token 生成 `ColorScheme` / `Typography` / `Shapes`，并通过 `LocalNovaColors` 提供 `NovaTheme.colors`；`Color.kt` 里的手挑色板删除 |
| `MainActivity` | 左抽屉 `ModalDrawerSheet` 宽度 `min(可用宽度 − 32dp, 384dp)`、内容侧 24dp 圆角、容器色 `--nova-bg`、无 tonal elevation |
| `feature/shell/AppShell.kt` | 标题栏 56dp + 17sp/600 + 返回箭头 20dp；分组标签 12sp/500；创作组两列 58dp 入口卡（选中 `primary` 反色）；工具/设置组整组卡片、行高 52dp、18dp 圆角、行间 1px 分隔线左缩进 48dp、尾部 chevron 14dp |
| `feature/shell/RightDrawer.kt` | 宽度 `min(可用宽度 − 32dp, 512dp)`、左侧 24dp 圆角、`--nova-bg` 背景、独立 scrim、标题栏 56dp |
| `res/values/strings.xml`、`values-zh` | 抽屉标题改为基线文案（导航菜单 / Navigation）；工具组标签改为「工具与资料 / Tools & resources」；新增关闭导航的描述 |

## 2. 与基线的同屏读数

差值口径：dp 值由 uiautomator 节点 bounds 或截图边缘换算（1dp = 3.6875px @590dpi、4.5px @720dpi）。

| 项目 | Web 390×844 | Android 390dp | 差 | 结论 |
|---|---|---|---|---|
| 抽屉宽度 | 358px（`100% - 2rem`，上限 24rem） | 358.3dp（行 89–1351px + 左右各 8dp 容器内边距） | ≤ 0.5dp | 一致 |
| 抽屉宽度（320dp 档） | 288px | 288.7dp | ≤ 0.7dp | 一致 |
| 内容侧圆角 | `0 24px 24px 0` | 24dp | 0 | 一致 |
| 标题栏高度 | 56px | 56dp（`heightIn(min)` + 内容居中） | 0 | 一致 |
| 标题字号/字重 | 17px / 600 / −0.425px | 17sp / SemiBold / −0.425sp | 0 | 一致 |
| 分组标签 | 12px / 500 / 左缩进 12px | 12sp / Medium / 左缩进 12dp | 0 | 一致 |
| 创作组入口高 | 58px | 58dp | 0 | 一致 |
| 工具组行高 | 52px | 52dp；实测相邻行中心距 196px = 53.1dp（52 + 1px 分隔线） | 0 | 一致 |
| 尾部 chevron | 14px | 14dp | 0 | 一致 |
| 抽屉背景（深色） | `#000`，行 `#0a0a0a`，分隔线 `#1e1e1e` | 同 | 0 | 一致 |
| 抽屉背景（浅色） | `#fff`，行 `#fafafa`，分隔线 `#e5e5e5` | 同 | 0 | 一致 |
| 创作组选中态（浅色） | 深底 `oklch(0.205)` + 浅字 | `#171717` + `#fafafa` | 0 | 一致 |
| 创作组选中态（深色） | 浅底 `oklch(0.922)` + 深字 | `#e5e5e5` + `#171717` | 0 | 一致 |

## 3. 截图

| 文件 | 场景 |
|---|---|
| `drawer-390-light.png` | 390dp、浅色、抽屉顶部 |
| `drawer-320-light.png` | 320dp、浅色、抽屉顶部 |
| `drawer-390-dark.png` | 390dp、深色、抽屉顶部 |

## 4. 本轮未覆盖（已登记进 09）

- 抽屉里的**信息架构**仍是 Android 版：书籍是文本列表而不是基线那张「当前作品」下拉卡；快捷动作是内嵌搜索框 + 底部消息入口，不是「命令 / 消息」两列；「排序」入口在基线的设置里；一级入口缺「统计」（trajectory）。
- **顶栏、输入框、按钮**尚未换成共用件（`NovaTopBar` / `NovaTextField` / `NovaButton`），仍是 Material3 默认尺寸，对应 09 的 VG-005…009。
- 抽屉 `Surface` 未加基线那层 `box-shadow`（只有独立 scrim），对应 VG-010。
- 默认主题：Web 无 `data-theme` 时是深色，Android 默认浅色；是否改默认属产品决定，未在本轮改。

## 5. 复现命令

```bash
# 构建与主检查
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# 390dp 档
adb -s 135babde shell wm density 590
adb -s 135babde install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 135babde shell am start -n com.denova.novara/.MainActivity
# 连接 → 登录 → 点左上角 ☰ → screencap

# 320dp 档
adb -s 135babde shell wm density 720
# 深色：抽屉滚到底 → 打开「深色模式」→ 滚回顶部 → screencap
adb -s 135babde shell wm density reset
```
