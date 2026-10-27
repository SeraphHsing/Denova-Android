# P4-A 第五切片（轮次版本切换 / 从指定轮重生成）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 轮次版本切换与从指定轮重生成`）
- 故事：`st_dmc2p6amro52de34e38f` / 分支 `main`（39 轮），只对**末轮**重生成（不丢后续轮次）

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 轮次项带「重生成」入口 | 通过 | 控制台「轮次」tab 每轮一行：跳转 / 分叉 / 重生成 |
| 从指定轮重生成 | 通过 | `regenerate turn=ev_dmfgsg909wj6f2c006fd` → `send … cmd=69a84396…`（body 带 `regenerate_from_turn_id`） |
| 重生成不追加轮次（回退后重跑） | 通过 | 完成快照 `total=39 start=29` 与重生成前一致；`stage=regen turns=39 len=105874`（重生成前 106759） |
| 重生成后生成新版本 | 通过 | 该轮出现版本控件 `‹ v2/2 ›`（重生成前无控件，说明版本数由 1 → 2） |
| 版本往返切换 | 通过 | v2 → v1：`turn version switched turn=ev_dmfhpze2z… to=ev_dmfgsg909w…`，baseline **106759**；v1 → v2：反向切回，baseline **105874** |
| 版本与当前内容对应 | 通过 | 两个版本的长度与文本各自稳定复现（105874 / 106759） |
| turn 与 branch 不混用 | 通过 | 版本切换请求带 `branch_id=main` + `turn_id` + `version_turn_id`，切换后仍 `branch=main`、`total=39` |

## 2. 关键日志

```
16:46:54 regenerate turn=ev_dmfgsg909wj6f2c006fd
16:46:54 send story=st_dmc2p6amro52de34e38f cmd=69a84396-0553-4c5d-bd47-63f1b9c52353
16:50:36 regenerated; resync canonical history
16:50:37 snapshot turns=10 total=39 start=29 actors=10 world=0 changes=10 choices=5 stateStatus=ready
16:50:39 canonical baseline loaded stage=regen turns=39 hasMore=false len=105874
16:51:16 turn version switched turn=ev_dmfhpze2z55aaf0580fe to=ev_dmfgsg909wj6f2c006fd
16:51:19 canonical baseline loaded stage=version turns=39 hasMore=false len=106759
16:51:37 turn version switched turn=ev_dmfgsg909wj6f2c006fd to=ev_dmfhpze2z55aaf0580fe
16:51:39 canonical baseline loaded stage=version turns=39 hasMore=false len=105874
16:51:51 turn version switched … to=ev_dmfgsg909wj6f2c006fd   （恢复原版本展示）
```

## 3. 实现要点

- `SendInteractiveChatRequest` 增 `regenerate_from_turn_id`（仅重生成时上送，普通发送不出现该字段，见单测）。
- `switchTurnVersion` 只换 `version_turn_id`，`turn_id` 保持当前轮；版本切换后**丢弃未落库的流式尾部并整段重取历史 + 快照**，避免本地 turns 与新版本不一致。
- 重生成收尾走 `pendingRegeneration` 分支：清空流式尾部 → `loadBaseline(full)` → `refreshSnapshot()` → `loadStories()`（`turn_count`/`updated_at` 对齐）。
- 版本控件只在 `versions.size > 1` 时出现；`version_idx` 与 `versions[].current` 双来源解析当前版本号。

## 4. 未覆盖 / 边界

- 只对**末轮**重生成（对中间轮重生成会丢弃其后轮次，属破坏性操作，未在真机制造）。代码路径相同（`regenerate_from_turn_id` 一律重取历史），但「回退到中间轮后后续轮次消失」的行为未截图取证。
- 版本数 > 2 的多级切换未构造（服务端需多次重生成同一轮）。
- 重生成耗时约 3.5 分钟（16:46:54 → 16:50:36），期间界面保持「运行中」，未出现错误提示。
