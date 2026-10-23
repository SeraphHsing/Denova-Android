# 08 页面 / 表面全量清单（P0-03 基础盘点）

> 静态盘点 v1（2026-10-23）。依据后端 `E:\Work\Git\denova\denova` 的 Web 源码：
> `web/src/App.tsx`、`web/src/stores/workspace-store.ts`、
> `web/src/components/workbench/{ModeRouter,WorkbenchRouteHost,SharedWorkbenchRoutes}.tsx`、
> `web/src/features/*`，以及后端 `internal/api/routes.go`。
> 本清单只到「顶层表面 + 子表面 + 条件入口 + 主要 API 族」粒度；逐字段展开、DTO 审计、双端对照尚未完成。

## 1. 顶层表面（WorkbenchRouteId）

Web 是单页 SPA，无 URL 路由，靠 `workspace-store.mode`（`WorkspaceRoute`）驱动
`WorkbenchRouteHost` 把 mode 映射到 14 个 `WorkbenchRouteId` 之一。

| # | RouteId | mode(s) | 布局 | 需 Book | 说明 | 主要 feature 目录 |
|---|---|---|---|---|---|---|
| 1 | `books` | `books` | full | 否 | 书籍/项目管理首页：列表、创建、导入（小说/角色卡）、封面、排序、切换工作区 | （`SharedWorkbenchRoutes` home，无独立 feature） |
| 2 | `ide-writing` | `ide` | writing | 是 | 写作 IDE：编辑器 + 章节大纲/文件树侧栏 + 右侧写作 Agent 面板 + 变更审阅 + 文档审阅 | `writing`、`chapters`、`document-review`、`changes`、`project-explorer` |
| 3 | `interactive` | `interactive` | interactive | 是 | 游戏：故事/分支/回合/状态/控制台/规划 | `interactive` |
| 4 | `lore` | `lore` | full | 是 | 资料库（Lore 条目、分类、索引、语义分类、角色卡、图片/语音/素材） | `lore` |
| 5 | `presets` | `presets` | full | 是 | 预设（teller 导演、风格引用、游戏规划模板、事件包、规则系统、状态系统、图片预设） | `interactive`（SettingPanel）、`skills`? |
| 6 | `versions` | `versions` | full | 是 | 版本历史 / diff / 回滚 | `versions`、`diff` |
| 7 | `market` | `market` | full | 否 | 资源市场与扩展安装/导出 | `market`、`platform` |
| 8 | `skills` | `skills` | full | 否 | Skills 库、编辑、偏好 | `skills` |
| 9 | `agents` | `agents` | full | 否 | Agents（引擎、模型、审批规则、子代理会话） | `agents`、`agent-runtime`、`agent-approval` |
| 10 | `automations` | `automations` | full | 否 | 自动化（模板、catalog、收件箱、运行） | `automations` |
| 11 | `agentchat` | `agentchat` | full | 否 | Agent 聊天（项目会话、历史、活动、终端） | `agent-chat`、`agent-goal`、`agent-quick-prompts` |
| 12 | `trajectory` | `trajectory` | full | 否 | 轨迹（developer mode 专属） | `trajectory` |
| 13 | `plugin` | `plugin:<id>/<page>` | full | 否 | 插件页面工作区 | `platform` |
| 14 | `settings` | （覆盖层） | full | 否 | 设置面板（叠加层，`settingsOpen` 控制） | `settings`、`analytics` |

> `messages`（消息中心）、`notices`、`workspace-events`、`activity`、`remote-access`（远程登录）、
> `onboarding`（引导）、`motion`（动效偏好）、`speech`（语音播放）、`conversation-config`（对话配置）
> 不是独立 mode，而是覆盖层 / 弹层 / 面板，或由 settings / shell 承载。

## 2. 各表面子表面与条件入口

### 2.1 books（首页）
- 书籍列表（排序模式）、创建书籍、导入小说（预览 + 流式预览）、导入角色卡（`CharacterCardImportDialog`，目标=new_book/current）。
- 封面：查看 / 生成 / 上传。书籍信息编辑、删除、排序、切换工作区（`/workspace/switch`）。
- 条件入口：无工作区时 fallback 到本页；`workspaceModeRequiresBook` 之外的模式不依赖前台 Book。

### 2.2 ide-writing（写作）
- 布局：左侧 `WritingSidebar`（`sidebarView` ∈ outline / files / search 三视图）+ 中间编辑器 + 右侧写作 Agent 面板（`rightPanel === 'ai'`）。
- 编辑器：Markdown 双模式、自动保存（去抖）、revision 校验、标签页（file/lore/subagent）、子代理会话标签。
- 章节：大纲、章节确认状态、插画生成/插入。
- 变更审阅：`changes/*`（review threads、comment、undo/redo、review）。
- 文档审阅：`document-review` + `document-comments`。
- 全局搜索/替换：`workspace/search`、`workspace/replace`。
- 版本：`versions` 面板入口。

### 2.3 interactive（游戏）
- 子模式由 `interactive-store.submode` 驱动（story 等），`InteractiveLayout` + 右侧 `SettingPanel`。
- 故事：列表/创建/选择/删除/快照/历史/执行详情/分支（创建/删除/plan/切换）/回合（presentation/narrative 更新、版本切换）/图像生成/上下文压缩。
- 控制台：`interactive/chat` 流 + 命令 + ask + recovery + context-analysis。
- teller：导演（增删改查）。
- 规划：游戏规划模板 / 事件包 / 规则系统 / 状态系统 / 图片预设（与 presets 共享）。

### 2.4 lore（资料库）
- 条目 CRUD、分类 CRUD、索引（读/写/预览）、语义分类（preview/apply）、条目图片生成、语音生成、素材上传/管理、资产访问。

### 2.5 presets（预设）
- `SettingPanel mode="teller"`：teller、风格引用（styles）、游戏规划模板、事件包、规则系统、状态系统、图片预设。

### 2.6 versions（版本）
- 状态、历史、diff、restore-plan、restore。

### 2.7 market（资源市场）
- catalog、catalog/refresh、资源交换（preview/plan/apply/install/export/backup/restore/detach/check/policy）。

### 2.8 skills
- 库、文档/文件编辑、创建/删除、偏好。

### 2.9 agents
- 引擎列表（agent-runtimes）、引擎探测（check）、模型列表（models）、子代理会话消息、审批规则。

### 2.10 automations
- 列表、模板、catalog、创建、收件箱（confirm/dismiss/read）、更新/删除、check、run。

### 2.11 agentchat
- 项目（list/create/directory/reorder/update/archive）、历史、活动、会话消息、终端会话（create/attach/delete/list）。

### 2.12 trajectory（developer mode）
- outcomes 列表/创建、run traces、时间线、内容账本/检查器。

### 2.13 plugin
- 插件页面，`PluginPageWorkspace`，路由 `plugin:<id>/<page>`。

### 2.14 settings（覆盖层）
- 通用设置（分层 draft）、模型（catalog/discover/ping）、图像 ping、语音、web-search test、更新（status/check/install/upload/apply）、analytics、conversation-config、conversation-goal、agent-runtimes。

## 3. 跨表面能力
- CommandPalette（命令面板）、OnboardingGuide（引导）、CharacterCardImportDialog、RemoteAccessLogin、SpeechPlayback、Notices、WorkspaceEvents、Activity summary。

## 4. 主要 API 族映射（按 routes.go 分组）

| API 族 | 归属表面 |
|---|---|
| `/books`, `/workspace/switch`, `/workspace/current`, `/imports/character-card/preview`, `/books/import-character-card` | books |
| `/projects/:id/files`, `/projects/:id/book`, `/projects/:id/workspace/*`, `/projects/:id/versions/*`, `/projects/:id/changes/*`, `/projects/:id/book/document-review`, `/projects/:id/book/document-comments`, `/projects/:id/agent-chat/*`（写作 Agent） | ide-writing |
| `/interactive/*`, `/game-planning-templates`, `/event-packages`, `/rule-systems`, `/actor-states`, `/image-presets` | interactive |
| `/projects/:id/book/lore/*` | lore |
| `/interactive/tellers`, `/styles` | presets |
| `/resource-market/*`, `/resource-exchange/*` | market |
| `/skills`, `/projects/:id/skills` | skills |
| `/agent-runtimes`, `/agent-runtimes/:id/*`, `/agents/:agent/session/messages`, `/settings/agent-approval-rules/:id` | agents |
| `/automations/*` | automations |
| `/agent-chat/projects`, `/agent-chat/history`, `/agent-chat/activity`, `/terminal/sessions*` | agentchat |
| `/trajectory/outcomes`, `/projects/:id/agent-runs*` | trajectory |
| `/settings*`, `/models/*`, `/images/*`, `/speech*`, `/update/*`, `/analytics*`, `/conversation-config`, `/conversation-goal`, `/activity/summary`, `/messages*` | settings / 跨表面 |

## 5. 与既有清单的差异（已升级基线，待重提取）

- `docs/android-web-parity/api-inventory.csv`（277 条）与 `parity-matrix.csv`（118 条）产自 Web 基线
  `671ef4eb`；本轮服务端已登记为 `85ec97c`（0.6.3）。`routes.go`（85ec97c）含若干 csv 未见的族：
  `/trajectory/outcomes`、`/agent-runs`、`/agent-runtimes`、`/event-packages`、`/rule-systems`、
  `/actor-states`、`/image-presets`、`/game-planning-templates`、`/update/*`、`/host/dialogs/directory`、
  `/activity/summary`、`/messages*`、`/speech*`、`/images/comfyui/*`、`/conversation-goal`、
  `/interactive/actor-traits/roll`、`/interactive/context-compaction`、`/workspace/switch|current`。
  → Web 基线已升级至 85ec97c（`baseline.json` 已更新 commit、源 blob SHA 与比较 URL；671ef4eb→85ec97c
  仅 AGENTS.md / routes.go / settings-sections.ts 三个文件变化）。api-inventory.csv 需在 85ec97c 重提取；
  parity-matrix.csv 的源文件 blob 在两端一致，URL 已同步指向 85ec97c。

## 6. 下一步（P0-03/04 续）
1. 对每个顶层表面展开子页面/控件/字段/状态/权限/条件入口，逐条写入 `parity-matrix.csv`。
2. 沿 Web 调用 → 路由 → handler → DTO 核对请求/响应/错误/权限/作用域，更新 `api-inventory.csv`。
3. 在 85ec97c 重提取 api-inventory.csv（routes.go 已变更），并核对与 parity-matrix.csv 的一致性。
