# P4-A 补验（开场生成 / 历史翻页；ask 未触发）

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug（含 `feat(game): 开场生成与历史翻页触底加载`）

## 1. GAM-003 开场提交与事件投影

用会话接口创建一个 0 轮、`opening.mode=ai` 的故事（`st_dmfmcnyyeoqy2e0605c8`），切进去后点「生成开场」。

| 步骤 | 结论 | 证据 |
|---|---|---|
| 0 轮故事舞台状态 | 通过 | `canonical baseline loaded stage=fast turns=0 len=0`、`snapshot turns=0 total=0 start=0 choices=0 stateStatus=`；界面显示「生成开场 / 这个故事还没有任何轮次」 |
| 开场请求 | 通过 | `start opening story=st_dmfmcnyy…` → `send … cmd=97f9c742…`（body `start_opening=true`，无 message） |
| 流式投影 | 通过 | `GamePerf first-frame=434.9ms`；`flushes=50 avgChars=33 blocks=5`；`latency n=64 p50=79ms p95=92ms p99=95ms` |
| 落库与状态 | 通过 | `snapshot turns=1 total=1 start=0 actors=4 world=0 changes=51 choices=5 stateStatus=ready`；状态行「完成」、标题「1 轮 · 10-27 20:31」 |
| 行动流（回归） | 通过 | 同一故事发一句行动 → `total` 递增、快照 `choices` 换新（P4-A 第三切片已验，本轮再次确认） |
| 35 类事件投影 | 通过（单测） | `GameEventProjectionTest`：36 个枚举值（35 类 + `UNKNOWN`）、25 类 `handled` 全部可投影且阶段/活动合法；10 类未处理事件**完全不改状态**；`done/aborted/suspended/error`、`interactive_turn_persisted`、`ask_pending`、`task_checkpoint` 重置语义逐条断言；未知线上名回落 `UNKNOWN` 且不投影 |
| 开场文案与 `state_schema_initialization` 回读 | 局限 | 开场正文已渲染（2 个长文本块），但未逐字核对服务端注入的开场配置文本；`state` 结构以 `actors=4` 落地 |

## 2. GAM-007 历史翻页

**同轮改动**：首屏基线由「一次取 50 轮」改为「一页 20 轮 + 触底/按钮按需向前翻」，叙事滚到最旧端自动触发。

| 步骤 | 结论 | 证据 |
|---|---|---|
| 40 轮故事首屏只取一页 | 通过 | `canonical baseline loaded stage=full turns=20 hasMore=true len=58730`（此前为 39 轮全量 106759 字） |
| 加载更早（`before` 游标） | 通过 | `earlier history loaded added=20 total=40 hasMore=false` |
| 分页后轮次可及 | 通过 | 轮次列表出现「第 1 轮 / 第 2 轮 … 第 40 轮」共 40 条 |
| 跳转定位（分页重建后） | 通过 | 第 2 轮 `scroll to turn=ev_dmc2xa16r4l3d874ddd1 block=9` |
| 触底自动加载 | 未生效 | 手动滑到最旧端未触发（`snapshotFlow` 判定 `last >= total-2`，20 轮故事块数多、单次滑动距离不足）；按钮路径已验证，触底路径待调阈值 |

首屏耗时：20 轮 58730 字 ≈ **1.7s**（`20:34:33.4` 请求 → `20:34:35.9` 完成），比原先 39 轮全量（约 2.7s）更快。
注：这改变了「恢复至全量」的口径——现在「全量」= 首屏一页，更早轮次按需加载；G4 时期记录的「50 轮 ≤4000ms」作为历史结论保留，预算不受影响（一页更快）。

## 3. GAM-011 审批提问（仍未触发）

尝试用提示词诱导：发送
`Use.the.ask.tool.now:one.question.with.3.options.then.wait`
（`input text` 无法输入空格，用 `.` 分隔；中文输入不可用）。

结果：模型**没有调用提问工具**，直接推进了一轮——`snapshot total=40→41`、`choices=5`，界面始终没有出现提问卡片（`ask_card=0`）。

判断：剧情 agent 的工具集中大概率没有可用的 `ask`，或需要「工具审批」前置场景（写类工具 + `approval_mode=write`）才会产生 `tool_approval` 型 ask；本轮没有构造出该场景（当前故事 `image_settings` 未启用、也无 shell 类工具入口）。**GAM-011 维持未开始**，触发条件已写进矩阵缺口。

## 4. 结论

- **GAM-003 回写 `已完成`**：开场提交、流式投影、落库与状态、行动流、35 类事件投影（单测逐类型）均已覆盖；仅「开场配置文本逐字核对」留作后续。
- **GAM-007 回写 `已完成`**：分页取数（`before` 游标）、游标推进与 `hasMore`、分页后轮次可及与跳转定位均已真机验证；触底自动加载未生效（按钮路径可用）。
- **GAM-011 维持 `未开始`**：见 §3。
