# PLN-001 / PLN-002 真机验证（创作方案：六类预设资源）

- **日期：** 2026-10-28（设备本地时间 10-29 凌晨）
- **设备：** Xiaomi 14 Pro（`135babde`，Android 16）
- **服务器：** `http://192.168.2.102:18081`，Denova 0.6.3 @ `85ec97c`
- **构建：** debug APK（`feat(presets)` + 后续修复）
- **验证形态：** Android 真机端到端 + 服务端接口读回。Web 端同场景对照随 P5。
- **凭证：** 仅经环境变量注入。

## 1. 目录与类型切换（PLN-001 / PLN-002 资源可用类型）

| 类型 | 端点 | 真机日志 | 服务端计数 |
|---|---|---|---|
| 叙事风格（TELLER） | `/api/interactive/tellers` | `Presets: loaded kind=TELLER count=4` | 4 |
| 游戏规划（PLANNING） | `/api/game-planning-templates` | `loaded kind=PLANNING count=5` | 5 |
| 事件包（EVENT） | `/api/event-packages` | `loaded kind=EVENT count=7` | 7 |
| TRPG 检定（RULE） | `/api/rule-systems` | `loaded kind=RULE count=7` | 7 |
| 状态系统（ACTOR） | `/api/actor-states` | `loaded kind=ACTOR count=5` | 5 |
| 图像方案（IMAGE） | `/api/image-presets` | `loaded kind=IMAGE count=5` | 5 |

列表项展示 `名称 / ID · 结构摘要（slots / sections / events / checks / templates）/ 说明 / 自定义 / 无效`，
结构摘要取自各自 DTO 的嵌套数组长度，与后端字段一一对应。

## 2. 叙事风格 CRUD（PLN-001）

| 步骤 | 结果 |
|---|---|
| 选中条目（查看） | 详情卡显示名称、`ID · 叙事风格`、`版本 <rev> · 1 slots`、路径、说明与结构 JSON；选中后列表自动滚到底 |
| 新建（空名称 → 保存） | 客户端校验生效：提示「名称不能为空」，不发请求 |
| 新建（合法） | 填 `androidprobeteller` / `androidprobe desc`，结构用骨架（1 个 slot，`target=system`）→ `Presets: saved kind=TELLER id=teller-1793205556509073360`；`GET /api/interactive/tellers` 读回 `name=androidprobeteller`、`description=androidprobe desc`、`slots=1`、`custom=true`、`revision=sha256:3…`，计数 4→5 |
| 修改 | 详情「编辑」→ 改说明为 `androidprobe desc2` → `saved kind=TELLER id=teller-1793205556509073360 revision=sha256:6…`；`GET .../tellers/:id` 读回 `description=androidprobe desc2`、`slots=1`、revision 前移 |
| 结构校验 | 结构框改为 `{` → 提示「结构不是合法 JSON」，不发请求 |
| 删除 | 详情「删除」→ 二次确认 → `Presets: deleted kind=TELLER id=teller-1793205556509073360`；`GET /api/interactive/tellers` 回到 4 条，`GET .../tellers/:id` 返回 404 |

## 3. 字段与校验（PLN-002）

纯逻辑单测 `PresetEditorTest`（10 例）覆盖：

- ID 字符集：叙事风格允许大写与下划线，其余模块只允许小写字母 / 数字 / 连字符；
- 叙事风格：`slots` 必填且至少一条、`target ∈ {system, turn_context}`；
- 游戏规划：`sections` 必填且每条需 `id` + `title`；
- 状态系统：`actor_state.templates` 至少一条；
- 保存体组装：保留 `version` 与未知字段（`future_field`）、剥掉 `path/revision/custom/…` 只读字段、结构字段整体以编辑结果为准（缺键即删除）、ID 留空不写。

真机侧同时验证了服务端二次校验的错误原文直接显示（4xx `message`），客户端不重试、不吞错。

## 4. 未覆盖 / 边界

- 复杂结构（TRPG `rule_templates`、状态系统 `templates/initial_actors/trait_pools`、事件卡片）采用 JSON 文本编辑，未做逐字段表单；Web 的专用表单形态差异登记为 P5 视觉/形态对照欠账。
- 删除 / 更新他人正在引用的方案时后端可能给出引用警告（服务端逻辑），本次未构造。
- 仪器测试未执行。
