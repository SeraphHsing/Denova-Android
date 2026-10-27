# P4-A 第六切片（暂停 / 续接；提问与放弃待补）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 提问回答与运行时暂停续接`）

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 运行中暂停（GAM-012） | 通过 | `suspend submitted op=run-20261027T090610.028268200-d33c5478e312`；阶段转 `已暂停`，按钮变为「续接」 |
| 暂停后舞台保持当前轮次 | 通过 | 暂停期间 `total=39 start=29` 未变，叙事不新增 |
| 续接回到同一任务（GAM-012） | 通过 | `resume action submitted kind=resume op=run-20261027T090610…`（**与暂停同一 operation id**）→ `start …` → `active probe active=true phase=running` |
| 续接后继续产出并落库 | 通过 | 约 2 分钟后 `snapshot turns=10 total=40 start=30`（39 → 40） |
| 重启后的挂起入口恢复 | 通过 | `active probe active=false phase=suspended pending=false actions=[resume, abort]` → 界面显示「已暂停 / 续接」 |
| 提问回答（GAM-011） | **未真机验证** | 依赖模型主动 `ask`，无法确定性触发；仅单测覆盖解析 / 合并 / 提交体 |
| 放弃挂起输入（GAM-013） | **未真机验证** | `suspend` 不产生 `pending_interruption_id`（实测 `pending=false`），Web 同口径要求「无活动 + phase=idle + 有待续接输入」，当前无法构造 |
| 双重流恢复（GAM-014） | **未真机验证** | 本轮续接走「recovery + 重新挂接」，未同时保留 POST 流与 active 流 |

## 2. 关键日志

```
17:06:07 send story=st_dmc2p6amro52de34e38f cmd=9390866e-0cfc-4e26-b663-6ad6c527574c
17:06:24 suspend submitted op=run-20261027T090610.028268200-d33c5478e312
17:06:5x 状态：已暂停（续接 / 放弃挂起 按钮出现）
17:09:43 send … cmd=117dc382… → 17:09:44 send failed code=http.500   （挂起期间新发送被服务端拒绝）
17:12:17 active probe active=false phase=suspended pending=false actions=[resume, abort]
17:12:34 resume action submitted kind=resume op=run-20261027T090610.028268200-d33c5478e312
17:12:39 active probe active=true phase=running pending=false actions=[]
17:14:20 snapshot turns=10 total=40 start=30 choices=5 stateStatus=ready
```

## 3. 实现要点

- 暂停：先 `GET /chat/active` 取 `active_operation_id`，再发 `{type:"suspend", command_id, target_operation_id, story_id, branch_id}`；**不复用本地旧 id**。
- 续接：与 Web `resumeTask` 对齐——优先用 active 里服务端投影的 `recovery_actions[kind=resume]` 调 `/chat/recovery`，再重新挂接；
  仅「无 active 且 `runtime_recoverable=false` 且有 `pending_interruption_id`」时退回「带 `resume_interruption_id` 重发 `Continue.`」。
- 放弃：`{type:"abandon_pending", story_id, branch_id, resume_interruption_id}`，并前置校验「无活动 + phase=idle + 有 pending」（与 Web 一致，否则提示运行已变化）。
- 提问：流事件 `ask_pending` / `ask_resolved` 的 `data` 就是 `AskInteraction` 体，复用 AGT-004 的 `AgentAskInteraction` / `AgentAskLogic`；
  回答走 `/api/interactive/chat/asks/:id/answer`，取消走 `/cancel`，`ask_stale` 时刷新投影而非重试。

## 4. 真机暴露并修复

**重启后挂起态不可见**：流不会重放 `suspended`，重启后本地阶段回到 idle，界面不显示「续接 / 放弃」，
此时新发送被服务端拒绝（实测 `http.500`）。修复：挂接时探测一次 active 投影，`phase=suspended` 映射为 `SUSPENDED` 阶段，
并把 `pending_interruption_id` 落到 UI 状态，使「续接 / 放弃挂起」入口在重启后仍可用。修复后实测可正常续接。

## 5. 未覆盖 / 后续

- **GAM-011 提问**：需要模型在剧情里触发 `ask`（当前故事连续多轮未触发）。解析与提交逻辑有单测，缺真机取证。
- **GAM-013 放弃挂起**：`suspend` 不产生 `pending_interruption_id`；要构造「idle + 待续接输入」需要排队输入被挂起的场景（Web 用队列/审批触发）。
- **GAM-014 双重流**：本轮续接后只挂一条流；「POST 流与 active 流并行不串台」需要更复杂的时序构造。
- 因此本轮 **GAM-012 回写 `已完成`；GAM-011 / GAM-013 / GAM-014 维持 `未开始`**。
