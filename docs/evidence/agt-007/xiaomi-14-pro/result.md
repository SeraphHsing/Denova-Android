# AGT-007 已有队列控制（`steer_queued` / `cancel_queued`）· 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug；服务器 `http://192.168.2.101:18098`，会话 `default`
- 时间：2026-10-25 22:31–22:35
- 探针：只读轮询 `GET /api/chat/active?session_id=default`，逐项打印 `command_id / operation_id / delivery / steer_requested / message`

## 1. 准备：暂停中的运行 + 两项队列

暂停态不会执行队列，队列稳定，便于逐项操作：

| 时刻 | 动作 | 观测 |
|---|---|---|
| 22:31:03 | 提交 `write 3000 word story` | `active=true phase=running op=run-20261025T143058.220229063-7adb858ae7e2` |
| 22:31:11 | 点「暂停」 | `phase=suspended`，`active_operation_id` 保留（暂停不换操作身份） |
| 22:33:09 | 追加 `keep` | `queue=1`，`cmd=be07b529-0e48-4050-a72a-7e1535426413 delivery=follow_up` |
| 22:33:14 | 追加 `add` | `queue=2`，新增 `cmd=ff4b386d-e446-4ee1-802e-6db371dc31c0 delivery=follow_up` |

界面同屏两行「本轮已追加」，各带 转向 / 删除（`queued-followups.png`）。

## 2. 转向只变化目标队列项

22:33:34 点第一行「转向」：

```
queue control type=steer_queued target=be07b529-0e48-4050-a72a-7e1535426413
  cmd=56099ff7-11cf-48e1-b523-77187c186ebb op=run-20261025T143058.220229063-7adb858ae7e2
```

服务端读回（22:33:37）：

- `be07b529…` → `delivery=steer`、`steer_requested=True`
- `ff4b386d…` → 仍为 `delivery=follow_up`、无 steer 标记

界面该项变为 `• keep · 已请求转向`（`steer-target-only.png`），另一项文案不变。

## 3. 删除只移除目标队列项

22:33:49 点第二行「删除」：

```
queue control type=cancel_queued target=ff4b386d-e446-4ee1-802e-6db371dc31c0
  cmd=fc2677ad-d207-4dca-84a4-e011eb8a8d8e op=run-20261025T143058.220229063-7adb858ae7e2
```

服务端读回（22:33:52）：`queue=1`，只剩 `be07b529`（steer 状态保持 `steer_requested=True`），删除目标未出现在队列（`cancel-target-only.png`）。

## 4. 恢复后按转向语义执行

22:34:03 点「继续」→ `phase=running`，队列仍保留该项（`delivery=steer`）；22:35:00 `queue=0`（轮次边界消费），流继续输出。删除项没有再出现，转向项按提前执行的语义被消费。

## 5. 门控（本地拒绝优先于下发）

- `canSteerQueued`：目标项必须仍在 `active.queue` 且 `operation_id` 与当前 `active_operation_id` 一致；操作已换代或队列已清空时不发命令。
- `canCancelQueued`：只要求该项仍在队列（删除不依赖当前执行目标），`operation_id` 必须自洽。
- 每次操作前先 `refreshActive()` 取服务端投影，动作基于过期列表时直接拒绝并提示，日志 `queue control skipped … reason=stale queue`。
- 提交成功按回执乐观更新该行（删除移除、转向置标记），随后重取投影；失败按错误码提示且不重发。

## 6. 验证

- JVM：`AgentQueueTest` 另 6 例（队列查找、换代后禁止转向、已清空禁止删除、转向/删除命令体、`reason` 取值、命令线含 `target_command_id`）。
- 真机：§1–§4 在本机完成；主检查 `testDebugUnitTest` + `lintDebug` + `assembleDebug` 全绿（50 suites / 340 tests / 0 failures）；仪器测试未执行。

## 7. 残留

- 「取回编辑」（`reason=returned_to_editor`）未做，本轮删除固定 `user_deleted`。
- 跨端同时操作同一队列项（另一端 steer / cancel 同一 `command_id`）未真机构造，仅由 `queue_conflict` / `target_operation_mismatch` 错误码兜底。
