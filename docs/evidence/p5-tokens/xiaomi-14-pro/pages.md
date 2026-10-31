# P5 第三轮：全站顶栏 / 输入框 / 按钮统一（真机）

- **日期**：2026-10-31
- **设备**：小米 14 Pro（`135babde`），Android 16，物理 1440×3200，`wm density 590` → 390dp
- **应用**：`com.denova.novara` Debug（本轮构建）
- **Web 对照**：`SeraphHsing/denova@85ec97c`，读数见
  [`web-baseline/2026-10-30/metrics.json`](../../web-baseline/2026-10-30/metrics.json)

## 1. 覆盖范围（本轮的替换口径）

| 类别 | 处理 | 数量 |
|---|---|---|
| 页面顶栏 | 全部换成 `NovaTopBar`（48dp、左右 12dp、标题 17sp/600、`Menu` 图标、20dp 动作图标） | 14 处：编辑器、工作台、游戏、终端、设置、资料库（lore）、书籍、Skills、Agents、自动化、创作方案（presets）、版本管理、资源中心（market）、Agent 舞台 + 占位页 |
| 顶栏/工具行文本字符图标 | `☰ » ⋯ ⋮ ⟳ ⇅ ＋` 全部换成 `material-icons-core` 矢量图标 | 20 处 |
| 输入框 | `OutlinedTextField` → `NovaTextField`（44dp 视觉高 / 48dp 触控盒、10dp 圆角、1dp 描边、label 在框外上方 16sp/500，支持多行与 `supportingText`） | 74 处，19 个文件 |
| 实体按钮 | `Button` / `OutlinedButton` → `NovaButton`（44dp、10dp 圆角、16sp/500、最小宽 72dp、主/次/加粗三态） | 30 处（27 处脚本转换 + 模式行 `ModeButton` / 格式按钮 `FormatButton` / 游戏故事选择器） |

保留口径（写进 09）：

- `TextButton` 未替换：它们是行内的 ghost 文字动作（列表行操作、菜单触发器、对话框文字按钮），基线对应的是无背景文字链接，没有 40dp 胶囊形态。实体按钮（有背景/描边的 `Button` / `OutlinedButton`）已全部替换。
- `FilterChip`（设置页选项组）未替换：M3 FilterChip 的高度 32dp、圆角与基线 chip 一致，属于「已核对一致」而非缺口。

## 2. 真机实测

| 项 | 基线（Web 390×844） | Android 390dp |
|---|---|---|
| 顶栏高度 | 48px | 48dp（`heightIn(min = NovaSize.topBar)`） |
| 顶栏左右内边距 | 12px | 12dp |
| 顶栏背景 | 深色 `#000` / 浅色 `rgb(242,242,242)` | `bgDeep`（`#000` / `#f2f2f2`），浅色实测一致 |
| 标题 | 17px / 600 / −0.425px | 17sp / SemiBold / −0.425sp，单行省略 |
| 副标题 | 12px / 400 / muted | 12sp / `NovaText.micro` / `textMuted` |
| 动作图标 | 20px | 20dp（`NovaSize.iconAction`），触控 44dp |
| 输入框视觉高 | 32px | 44dp（触控盒 uiautomator 实测 177px / 3.6875 = 48dp） |
| 输入框圆角 / 描边 / 字号 | 10px / 1px / 16px | 10dp / 1dp / 16sp |
| 按钮 | 32px 高 / 10px 圆角 / 16px·500 | 44dp / 10dp / 16sp·500 |

截图（390dp、浅色）：

| 文件 | 场景 |
|---|---|
| `connect-390-light.png` | 连接页（4 输入 + 主按钮） |
| `editor-390-light.png` | 编辑器（顶栏图标化 + 新输入框 + 模式行新按钮） |
| `pages/workbench-390-light.png` | 工作台（顶栏 + 主区/副区 chip + 历史） |
| `pages/game-390-light.png` | 游戏（顶栏 + 故事行 + 玩家输入框 + 发送按钮） |
| `pages/settings-390-light.png` | 设置（顶栏 + 副标题 + 保存/重新加载 + 表单输入） |

## 3. 验证方式

- `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全绿。
- 真机安装后逐页进入截图（连接 → 编辑器 → 抽屉 → 工作台 → 游戏 → 设置），确认顶栏动作可点、输入框可聚焦、按钮可点。
- 转换后立即编译（`compileDebugKotlin`）并修完 7 处脚本误伤（`keyboardActions` 未支持、`label` lambda 解包残留、双逗号）。

## 4. 已知差异（不属于 VG-005…009）

- 抽屉信息架构（当前作品下拉卡、命令/消息快捷动作、统计入口）→ VG-011…013。
- 抽屉 `box-shadow` → VG-010。
- 多行输入框高度随内容增长（`heightIn(min = 44dp)`），与基线的 `auto-grow textarea` 行为一致。
