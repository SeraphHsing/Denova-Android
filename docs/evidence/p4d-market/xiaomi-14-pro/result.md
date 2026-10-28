# P4-D · 资源市场与扩展（MKT-001…005）真机证据

- 日期：2026-10-29
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 `0.6.3-b`，本机判定为**远程客户端**）
- 用例：`:app:testDebugUnitTest` **80 套件 / 590 用例 / 0 失败**（新增 `MarketLogicTest` 11 例）；`lintDebug`、`assembleDebug` 通过

## 1. 实现

| 能力 | 实现位置 |
|---|---|
| 契约：市场目录 / 已安装资源 / 备份 / 插件页 / 更新状态 / 项目扩展配置 | `core/network/PlatformCatalog.kt` |
| 客户端端点（目录、刷新、已安装、备份、检查更新、策略、插件页、运行时、更新状态与检查、项目扩展读写） | `DenovaClient`（新增 12 个方法） |
| 纯逻辑：客户端搜索、按 format 分组、本地化取名、本机限定错误映射、状态标签 | `core/market/MarketLogic.kt`（11 个 JVM 用例） |
| 页面（目录 / 已安装 / 运行时三标签 + 项目扩展配置） | `feature/market/MarketScreen.kt`、`MarketViewModel.kt` |
| 导航接入 | `MainActivity`（Activity 作用域 `MarketViewModel`）、`ActivityNav`（`market.implemented = true`） |

事实来源按固定基线 `85ec97c`：`web/src/features/platform/api.ts`、`ExtensionDirectory.tsx`、
`InstalledExtensions.tsx`、`RuntimeSetup.tsx`、`internal/api/handlers/handler_platform*.go`、
`internal/api/middleware.go`（`platformManagementLocalHostMiddleware` / `localHostEffectMiddleware`）。
本机限定动作的判定以后端为准：远程客户端拿到 `403 {"code":"LOCAL_HOST_ONLY"}` 或 `403 {"error":…}`。

## 2. MKT-001 资源浏览 / 搜索 / 详情 / 已安装状态

| 步骤 | 实测结果 |
|---|---|
| 目录 | 「资源市场」标题显示 `3 个目录条目 · 47 个已安装`；目录按 `denova.resource-pack` 分组，条目名称与描述按当前语言取（照夜长河 · 大世界修仙模拟 / 漫画工坊 / 扩展示例 · 文本统计与邻里来信），副行显示 format + kinds + 作者 |
| 搜索 | 输入 `cultivation` → `1 个目录条目`，只剩带该词（名称 / 描述 / tags）的条目；清空后恢复 3 条（客户端分词全包含，服务端无搜索参数） |
| 详情 | 显示本地化名称、id / format / 兼容说明、`#game #cultivation #images` 标签、本地化描述、本地化用法、作者 |
| 已安装 | 切「已安装」：按项目分组（如 `项目 9c27e113 · 15 个`），行内显示 `手动 · 缺失/已修改 · 已跟踪`；点开 `冯保善` → `character-card · 4670f77e`、`来源: file · 冯保善.png`、`更新策略 手动 · 本地 缺失 · 远端 -`、`已复核源 sha256:cea0fa…`、`备份（1）2026-10-20T03:54:38 · committed · 4` |
| 一致性 | 列表 47 条与服务端 `GET /api/resource-exchange/installations` 数量一致；`local_state` 的 `missing / modified` 与服务端取值同集 |

## 3. MKT-002 远程配置（项目级扩展，远程可写）

基线的扩展配置分两类：`packages/:kind/:id/settings`（本机限定写、需 releaseId 的 schema 表单）与
`platform/manage/projects/:id/extensions`（**远程可读写**：禁用插件清单 + 项目模型映射）。本轮实现后者，
前者登记为缺口（见 §6）。

| 步骤 | 实测结果 |
|---|---|
| 读取 | 运行时标签 →「项目扩展」输入项目 id → `读取` → 显示 `revision sha256:f3ff696cd…`、`已禁用插件（无）`、`模型映射（JSON）{}` |
| 写入 | 添加 `probe-plugin` → `保存` → 提示「项目扩展已保存」，revision 变为 `sha256:6621dfc…`；服务端读回 `disabledPlugins=["probe-plugin"]`、`models={}` |
| 复原 | 点条目上的 ✕ 移除 → 保存 → revision `sha256:8e99c…`，服务端读回 `disabledPlugins=[]`（内容寻址 revision 回到原值） |
| 冲突 | 用脚本把服务端改成 `["external-probe"]`（revision `ab107…`）后，在 App 里用旧 revision 保存 → 提示「项目扩展已在其他位置更新，请重新读取后再保存」，服务端未被覆盖；随后脚本恢复 `[]` |

## 4. MKT-003 本机限定动作（权限表现一致，同时作为 P1 台账 **G6-b** 证据）

远程客户端点这些入口全部拿到服务端拒绝，界面如实展示「该操作需要在运行 Denova 的本机上执行」：

| 动作 | 服务端 | 界面 |
|---|---|---|
| 刷新市场目录（`POST /api/resource-market/catalog/refresh`） | `403`（`localHostEffect`） | 顶部错误文案 = 本机限定说明 |
| 切换更新策略（`POST /api/resource-exchange/installations/:id/policy`） | `403`（`localHostEffect`） | 同上，且底部常驻「本机」说明；服务端 `update_mode` 保持 `manual` 未被改动 |
| 检查更新（`POST /api/resource-exchange/installations/:id/check`） | `403` | 同上 |
| 安装 / 预览 / 权限 | 本机限定 | 详情页「安装（需本机）」按钮**禁用**并附限制说明，不发出请求 |

配合设置页的证据（只写 `user` 层；写 `remote_access_password` 会让旧会话立即失效并回到登录页），
P1 台账 G6-b「权限表现一致」的远程/本机分层与设置分层两条均已实测。

## 5. MKT-004 / MKT-005 备份 / 更新策略 / 动态插件页面

| 步骤 | 实测结果 |
|---|---|
| 备份读取 | 已安装详情列出 `备份（1）… committed · 4`（`GET /installations/:id/backups` 远程可读） |
| 更新策略 | 三档 `手动 / 提示 / 自动应用` 与服务端 `manual|notify|auto_apply` 对应；写入受限（见 §4），服务端值未变 |
| 程序更新 | 运行时标签显示 `当前 0.6.3-b · 最新 0.6.3`、`已是最新` 与 release 链接（`GET /api/update/{status,check}` 远程可读）；未调用 `install/apply`（会真实升级服务端） |
| 插件页目录 | `插件页（0）` + 空态；`GET /plugin-pages` 远程可读 |
| 运行时 | `运行时（0）` + 空态；`GET /runtimes` 远程可读 |
| 页面本体 | 不实现：插件页依赖宿主 `127.0.0.1` loopback 运行时与浏览器 DOM/iframe，远程必然 403；界面给出该说明 |

## 6. 未覆盖 / 已知差异

- `packages/:kind/:id/settings` 的 schema 表单（RJSF + JSON Schema）未实现：写入本机限定、读取需 `releaseId`，
  且当前部署 `GET /platform/manage/catalog` 与 `packages/{plugin,game}` 均为空（没有经平台安装的扩展）。
- 平台侧安装 / 卸载 / 升级 / 权限授予（`packages/preview|install`、`PATCH packages/:kind/:id`、
  `PUT .../permissions`）未做 UI：本机限定，且无已安装的 platform 包可对照。
- `GET /platform/manage/projects/:id/plugins` 需要合法 contribution context（`workbench` 被判 400
  `INVALID_ARGUMENT`），未接入插件贡献清单。
- 动态插件页面渲染（MKT-005 的「进入动态页面」）按上表理由不实现；只提供目录与状态只读视图。
- 目录搜索为客户端实现（与基线一致，服务端无参数）；`tags / kinds` 只作展示，不做分类筛选。
- 仪器测试未执行；Web 端同场景对照随 [P5](../../../android-web-parity/05-roadmap.md#7-p5全量双端对照)。
