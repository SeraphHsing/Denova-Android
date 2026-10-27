# P4-A 第二切片（新建故事表单）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 新建故事表单与故事创建`）

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 创建表单资源目录加载 | 通过 | `create resources tellers=4 templates=5 events=7 rules=7 actors=5 presets=3 lore=0` |
| 基础表单（标题 / 主角 custom / 开场 custom） | 通过 | UI 截图（本地留存） |
| 高级区展开与数值步进 | 通过 | 难度 `0→1`、行动建议数 `5→3` 即时生效（该次填表未提交，见 §4） |
| 创建故事 | 通过 | `story created id=st_dmf90ts0bi6x3ea3edf9 title_source=user protagonist=custom opening=custom choices=5 turns=0` |
| 创建后自动选中并挂接主分支 | 通过 | `start story=st_dmf90ts0bi6x3ea3edf9 branch=main`，`canonical baseline loaded … turns=0 len=0` |
| 服务端持久化 | 通过 | 重启后 `stories loaded count=6 current=st_dmf90ts0bi6x3ea3edf9`（创建即写 `index.current_story_id`，与 Web 一致，无需再 `select`） |
| 删除真实往返（补 A1 缺口） | 通过 | `story deleted id=st_dmf90ts0bi6x3ea3edf9 next=st_dmc2p6amro52de34e38f` → 自动 `story selected` + `start` 37 轮故事；列表回到 5 项 |

## 2. 创建请求（实际提交值）

| 字段 | 值 |
|---|---|
| `title` | `P4A TMP` |
| `protagonist` | `{mode:"custom", name:"Tester"}` |
| `opening` | `{mode:"custom", custom_text:"A quiet nightsfall."}` |
| `story_teller_id` | 资源默认（tellers `default_id`） |
| `planning_template_id` / `event_package_ids` / `rule_system_id` / `actor_state_id` | 资源默认（`default`） |
| `image_settings.preset_id` | `game-cg` |
| `choice_count` / `reply_target_chars` | 5 / 2000（默认值） |
| `presentation_settings` | `background=true, characters=true` |
| `state_schema_policy` | `adapt_template` |

创建返回 `turn_count=0`、`branches` 默认，随后 `start … branch=main` 成功，说明 story 与初始分支身份准确。

## 3. 真机暴露并修复的缺陷

**创建对话框底部按钮被软键盘遮挡**：原实现用 `AlertDialog`（内容 `heightIn(max=520.dp)`），
键盘弹出后对话框被顶到屏幕上方，`Cancel` / `Create` 落到键盘下面，真机上点不到（首次提交创建时
点击落在键盘上，无任何请求发出）。

修复：改为 `Dialog(usePlatformDefaultWidth = false)` + `Surface(fillMaxHeight(0.92f), imePadding())` +
「固定页脚」结构（标题 / `weight(1f)` 滚动内容 / 底部按钮行）。复测键盘弹出时两个按钮仍在键盘上方可点。

同时把空叙事提示由「选择故事后挂接游戏流」改为「还没有剧情内容，发送行动即可开始」——新建的 0 轮故事
此前会显示该误导文案。

## 4. 未覆盖 / 后续

- **资料库主角（`mode=lore`）**：当前书 `lore=0`（无 `type=character` 条目），下拉为禁用态，未构造出可选项。
- **预设开场的方案列表**：只实现了手填 `preset_text`，未接书籍预设文件 `setting/interactive-openings.json` 的选择。
- `custom_agent_id` / `profile_id` / `thinking_level` / `initial_trait_rolls` / `speech_settings` 未作为表单项，缺省走服务端 seed。
- **开场文案与状态结构的服务端读回**：创建响应只证明 `protagonist=custom` / `opening=custom` 的模式，
  文案与 `state_schema_initialization` 需快照读回，留待 A3（GAM-003/006）。
- 高级区交互：事件包多选、图像 `interval` 模式的 interval 步进仅单测覆盖；本轮构造的改动（难度 1、建议数 3）
  在切换输入焦点时因对话框被 `BACK` 关闭而丢弃，未随创建提交。
- 因此 **GAM-001 不回写**：判据「配置主角创建并选择故事」已真机通过，「配置表单和开场对应」尚缺开场文案读回。

## 5. 复现要点（取证方法）

- 表单需要大量文本输入，软键盘会反复弹出并改变对话框高度；取证时临时 `adb shell ime disable <id>`
  关闭软键盘，结束后 `ime enable` + `ime set` 恢复（本机为百度输入法 `com.baidu.input_mi/.ImeService`）。
- 该操作只影响取证效率，不改变产品行为；键盘遮挡是真实缺陷，已按 §3 修复。
