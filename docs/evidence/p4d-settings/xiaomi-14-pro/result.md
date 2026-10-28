# P4-D · 设置页（SET-001…014）真机证据

- 日期：2026-10-29
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 `0.6.3-b`，会话按 30 天 Cookie 持久化）
- 用例：`:app:testDebugUnitTest` **77 套件 / 557 用例 / 0 失败**（新增 `SettingsEditorTest` 17 例）；`lintDebug`、`assembleDebug` 通过

## 1. 实现

| 能力 | 实现位置 |
|---|---|
| 15 个 section 的字段表（点分 JSON 路径、控件类型、范围与枚举） | `core/settings/SettingsSchema.kt` |
| 分层读取与 merge patch 构造（空值 = 清除 / 继承）、本地校验 | `core/settings/SettingsEditor.kt`（17 个 JVM 用例） |
| 页面状态机：载入、逐字段草稿、保存、409 保留草稿、重新加载 | `feature/settings/SettingsPageViewModel.kt` |
| 页面：section 标签 + 单列字段表（布尔三态 / 枚举 / 整数 / 文本 / 密钥 / JSON / 只读） | `feature/settings/SettingsScreen.kt` |
| 导航接入 | `MainActivity`（`composable("settings")`）、`ActivityNav`（`settings.implemented = true`） |
| 客户端写入口（复用 P4-C 已有契约） | `DenovaClient.settingsSnapshot()` / `patchSettings(layer, baseRevision, changes)` |

事实来源按固定基线 `85ec97c`：`web/src/features/settings/settings-sections.ts`（section 顺序与字段键）、
`web/src/features/settings/SettingsFields.tsx`（枚举取值：locale `auto/zh-CN/en-US`、theme
`dark/light/system`、motion `system/full/reduced/off`、approval `ask/write/full_access`、shell
`auto/process`）、后端 `config/settings.go`（扁平结构 + JSON tag）、`config/settings_patch.go`
（RFC 7386 merge patch，`null` 清除字段、数组整体替换）。

基线共有 15 个 section，矩阵只登记 14 行（无 `decision`）。`decision`（决策模型）在同一个设置页里
一并实现（`SET-001…014` 之外的第 15 个 section，仍写 `user` 层），矩阵不新增行，记在本证据里。

## 2. 逐 section 真机核对（读取 + 渲染）

| section | 实测（小米 14 Pro，中文界面） |
|---|---|
| general 常规 | 语言 `auto`、主题 `dark`、动效 `system`、界面字体 `apple-system`、界面字号 14、阅读字体/字号、源码字体、使用统计，全部显示显式值与「继承值」 |
| writing 写作 | 自动保存、间隔 1500、章节文件名格式 `ch{order:05}-{chapter}-{title}.md`、分卷目录格式、最多标签 5、分组上下限、默认叙事风格 `rhythm`、默认 Skill `novel-standard` |
| model 模型 | `model_endpoints` / `model_profiles` 以 JSON 编辑器载入（格式化结构），默认模型档案 `gemini-3.8-flash-high` |
| image 图片 | `image_api_endpoints` / `image_api_profiles` JSON、默认图片档案 `default`、默认图片 Agent（空） |
| decision 决策 | `decision` 整份 JSON（`enabled` / `endpoints` / `profiles` / `game_presentation` / `lore_classification` / `semantic_triggers`） |
| speech 语音 | `speech.endpoint` / `speech.api_key`（遮蔽）/ `speech.model` / `speech.voice` 四个嵌套标量 |
| approvals 审批 | 审批模式三选（`ask` / `write` / `full_access`，当前继承 `write`）；审批规则为只读 JSON 列表 |
| terminal 终端 | 启用终端、终端 Shell、终端命令（JSON）、会话上限、回滚 KB、Shell 环境模式（`auto`/`process`）、Shell 路径、Agent bash 路径 |
| web-access Web 访问 | `web_access` JSON（有效值含 `search_max_results=10`、`fetch_max_response_kb=8192` 等） |
| market 资源市场 | 市场索引地址 `https://alfredxw.github.io/denova-index/index.json`、GitHub Token（遮蔽） |
| paths 路径 | `skills_dir` 可写 `./skills`；`paths.nova_dir = /data/.denova`、`paths.user_config = /data/.denova/config.toml` 标记「只读」 |
| versions 版本 | 定时版本（当前 `true`）、间隔 10 分钟 |
| access 连接权限 | 允许局域网访问（继承 `true`）、用户名 `Hsing`、密码不回显（占位「（空）」）、只读「已配置密码 = true」 |
| updates 更新 | 自动检查更新 / 自动下载更新（均继承 `false`） |
| advanced 高级 | 模型重试 5、Agent 空闲超时 0、工具结果上限 128KB、工具并发 8、子 Agent 并发 4、脚本超时 0、默认计划模式、开发者模式（`labs.developer_mode`） |

## 3. 写入 / 清空 / 校验 / 冲突（真实服务器读回）

| 步骤 | 客户端 | 服务端读回 |
|---|---|---|
| 整数写入 | 常规 → 界面字号 14 → 15 → 保存 → 「设置已保存」 | `user.ui_font_size = 15`、`effective = 15`、`revisions.user` 前移（`af88cdd6…`） |
| 清空（继承） | 删空界面字号 → 保存 | `user` 层不再含 `ui_font_size`；`effective = 14 = default.ui_font_size`（`null` 语义生效） |
| 恢复 | 重新填 14 → 保存 | `user.ui_font_size = 14` |
| 整数越界 | 界面字号 5 → 保存 | 本地拦截「有字段校验未通过 / 取值范围 10–32」，**未发出 PATCH**，服务端 revision 不变 |
| 结构写入 | Web 访问 → JSON 输入 `{"search_max_results":8}` → 保存 | `user.web_access = {"search_max_results": 8}`、`effective.search_max_results = 8` |
| 结构清空 | 清空该 JSON → 保存 | `effective.search_max_results` 回到 `10`（默认层） |
| 布尔三态 | 版本 → 定时版本「关」→ 保存；再「开」→ 保存 | `user.version_timed_enabled` `true → False → True`，`effective` 同步 |
| 密钥 + 访问层 | 连接权限 → 密码填当前密码 → 保存 | 返回 200 后**原有的所有会话立即失效**（基线 `browserSession.valid()` 要求密码哈希一致）：设置页下一次 reload 得到 401 → 自动回连接页并提示「登录已失效，请重新输入密码」；用同一密码重新登录成功，回到设置页 |
| 409 冲突 | 先用脚本对服务端 `PATCH` 推进 `revisions.user`，再在 App 保存 | 提示「设置已在其他位置更新，草稿已保留；请重新加载后再保存。」，草稿（字号 14）仍在 → 点「重新加载」取回最新 revision、草稿清空 → 再次保存成功 |
| 只读字段 | 路径 → `paths.nova_dir` / `paths.user_config` | 显示服务端实际路径并标「只读」，不进入 patch（`SettingsEditorTest` 断言） |

**同轮修复**：只读字段原先按 `effective` 根节点取值，导致 `paths.*` 与
`remote_access_password_set` 显示「（空）」。改为只读项按整份快照根取值后，
`paths.nova_dir = /data/.denova`、`已配置密码 = true` 正确显示。

## 4. 分层与权限口径

- 设置页**只写 `user` 层**（基线 `SettingsView` 的 `GLOBAL_SETTINGS_TARGET`）；`base_revision` 取
  `revisions.user`，成功后用回包重建快照与 revision。
- 关闭 / 开启局域网访问、改用户名、改密码都会让旧会话失效（`AllowLANAccess` 与密码哈希都是会话
  校验项）。本轮用「重发同一密码」实证了这条链：写入成功 → 401 → 回登录 → 同密码重新登录。
- `PATCH /api/settings` 不受 `LOCAL_HOST_ONLY` 限制（与本机无关）；受限的是
  `/api/platform/manage/*` 与 `/api/resource-exchange/*` 的写操作，属 P4-D 的市场切片（MKT-003）。

## 5. 未覆盖

- 「逐字段」覆盖方式是**按控件类型抽样 + 每 section 全字段渲染核对**：枚举/整数/文本/密钥/布尔三态/
  JSON 各驱动一次写入或清空，未对 60+ 个字段逐个改值（其余字段的读取、范围、枚举与路径由
  `SettingsSchema` + `SettingsEditorTest` 固定）。
- 模型 / 图像档案的**连通性测试**（`POST /api/models/ping`、`/api/images/ping`）未接入设置页。
- workspace 层的白名单写入（`agent_*` 等）属 Agents/工作台已有入口，设置页不改 workspace 层。
- 仪器测试未执行（`connectedDebugAndroidTest` 仍受 UTP 插件下载 TLS 失败阻塞，见 WRI-016 记录）。
- Web 端同场景对照随 [P5](../../../android-web-parity/05-roadmap.md#7-p5全量双端对照)。
