# DEF-001 / DEF-002 真机验证（Agents 自定义定义、引擎状态与会话）

- **日期：** 2026-10-28（设备本地时间 10-29 凌晨）
- **设备：** Xiaomi 14 Pro（`135babde`，Android 16）
- **服务器：** `http://192.168.2.102:18081`，Denova 0.6.3 @ `85ec97c`
- **构建：** debug APK（`feat(agents)`）
- **验证形态：** Android 真机端到端 + 服务端接口读回。Web 端同场景对照随 P5。
- **凭证：** 仅经环境变量注入。

## 1. DEF-001 自定义定义 CRUD

页面：抽屉 → Agents。顶部显示 `用户层（跨项目） · N · rev <user revision>`，
六个内置 Agent（General / Writing / Game / Image / Version Summary / Tool Agent）按组展示，
`version_summary` 与 `tool_agent` 标注「内置 Agent（只读）」（无可定制运行契约）。

| 步骤 | 结果 |
|---|---|
| 初始读取 | `Agents: settings loaded layer=user custom=1 revision=218dc6b0`；服务端 `GET /api/settings` 的 `user.custom_agents` 有 1 条（`人物设计`，`writing.primary.v1`） |
| 新建 | 运行契约选 `ide`（`writing.primary.v1`）→ 名称 `androidprobe8` → `Agents: definition saved id=custom-agent-1793204636383-0 creating=true total=2 revision=6692fcbe`；服务端读回该条 `name=androidprobe8`、`contract=writing.primary.v1`、`enabled=true` |
| 修改（改名） | 详情「编辑」→ 名称改 `androidprobe9` → `definition saved id=custom-agent-1793204636383-0 creating=false total=2 revision=f2766e3b`；服务端读回 `name=androidprobe9`，且 `model` 等未展示字段仍在（`model? True`）——**整份定义回写不丢配置** |
| 删除 | 详情「删除」→ 二次确认 → `definition deleted id=custom-agent-1793204636383-0 total=1`；服务端 `user.custom_agents` 只剩 `人物设计`，revision 回到 `218dc6b0` |
| 冲突保护 | 保存时若服务端 revision 变化（409 `api.settings.revisionConflict`），保留表单内容、提示「设置已在其他位置更新」并只刷新 revision（代码路径 `refreshRevision`，本次由单测与实现审阅覆盖，未构造并发双端写入） |

写路径只有一条：`PATCH /api/settings {layer:"user", base_revision, changes:{custom_agents:[完整数组]}}`，
每次写前都以服务端快照为基准；`AgentDefinitionsTest`（9 例）覆盖 ID 规范化（对齐后端 `NormalizeSubAgentID`）、
契约双向映射、按 ID 合并/删除、整份数组提交、可见性过滤、分组与「未知字段保留」。

## 2. DEF-002 引擎状态查询与进入会话

| 步骤 | 结果 |
|---|---|
| 引擎目录 | `Agents: engines loaded count=5 native:ready, codex:ready, claude:not_installed, grok:ready, antigravity:ready`；界面逐条显示 id、本地化状态与 `reason_key` |
| 重新检查 | 点 `claude` 的「检查」→ `Agents: engine checked id=claude status=not_installed`，界面状态保持「未安装 · agentRuntime.claudeNotInstalled」（`POST /api/agent-runtimes/:id/check` 返回最新 descriptor） |
| 进入会话（自定义 Agent） | 选中 `androidprobe9` → 「进入会话」→ `Agents: session created id=s-20261028162419-a35daf128358 custom=custom-agent-1793204636383-0`；自动跳转写作 Agent 页，会话 ID 即该会话，`状态：空闲`，可正常挂接 |
| 进入会话（内置） | 内置 Agent 详情「进入会话」走普通 `POST /api/sessions`（不带 `custom_agent_id`），行为同上（本次以自定义路径为主证据） |

**证据边界：** 服务端 `GET /api/sessions`（`sessionDTO`）与 `GET /api/chat/active` 都不回显 `custom_agent_id`
（固定基线契约如此）。因此「会话绑定自定义 Agent」以「创建请求带 `custom_agent_id` + 服务端 200 返回会话 ID +
会话可挂接」为证据，不以读回字段为证据。绑定失效（ID 不存在）时服务端会拒绝创建，属同一路径的负向保护。

## 3. 未覆盖 / 边界

- 定义的 `runtime`（引擎选择）、`tools`、`skill_policy`、`delegation`、`context_bindings`、`image_api_profile_id`
  在 Android 侧**只展示与保留、不提供编辑表单**；这些配置属设置页 14 个 section 的范围（P4-D）。
- 工作区层（`workspace`）自定义定义未提供编辑入口，页面固定在 `user` 层。
- 子代理（`sub_agents`）与会话内 Agent 切换 UI 未做（基线 `CustomAgentSelect` 在会话头部，属 P4-D/Agent 会话范围）。
- 引擎模型列表（`GET /api/agent-runtimes/:id/models`）已接入客户端但页面未展示（模型配置属 SET-003）。
- 仪器测试未执行。
