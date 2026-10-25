# AGT-013 Agent 进程恢复：杀进程重启恢复活动会话

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：`default`（项目作用域空，写作 Agent 页）
- 对应矩阵：`AGT-013`（Agent消息与任务 / 进程恢复；steps「杀进程重启恢复活动会话」，expected_ui「恢复 ask 输入和任务入口」，expected_server_effect「服务端 active 为事实源」）
- 实现（本轮无代码改动，验证既有链路）：
  - `app/src/main/java/com/denova/novara/feature/agent/AgentViewModel.kt`（`ensureSession` / `start` / `attachLoop` / `refreshActive`）
  - `app/src/main/java/com/denova/novara/core/stream/AgentStreamRecoveryCoordinator.kt`（active → 决策 → 挂接）
  - `app/src/main/java/com/denova/novara/core/persistence/CookieStore.kt`（进程死亡后免重登）

## 1. 场景一：kill 时任务在跑，重启时任务已结束

1. 写作 Agent 页发送 `go` → 界面 `状态：运行中 · 已连接`；
2. 约 10s 后 `am force-stop`（进程被杀，客户端不可能继续收取流）；
3. 重启 → 连接 → 进入写作 Agent 页。

服务端仍然把这一轮跑完（客户端已不在）：

```text
agent-execution-summary · completed duration_ms=28284
agent-token-usage · prompt_tokens=50025 total_tokens=52047 model_calls=1
```

重启后客户端读到的是**服务端 canonical 历史**，没有半截正文、没有重复：

```text
I Agent: start session=default
I Agent: canonical parts loaded messages=48 tools=15 has_more=true total=163 session=default
状态：空闲
```

即「服务端 active 为事实源」：任务已结束就没有挂接入口，正文以服务端历史为准。

## 2. 场景二：kill 后重启时任务仍在跑 → 恢复任务入口并挂接

重复发送 `continue`，5s 后 `am force-stop`，立即重启并进入 Agent 页：

```text
I Agent: canonical parts loaded messages=49 tools=15 has_more=true total=164 session=default
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-2p cursor=6 attempt=0
状态：完成
```

`task` 与 `cursor` 都不是客户端记忆里的值，而是重启后从 `GET /api/chat/active` 读回的（`AgentViewModel.attachLoop` → `refreshActive()` → coordinator `recover(sessionId, seed)`），随后直接在 `after=6` 挂接收尾，界面落到「完成」（[restored-after-kill.png](restored-after-kill.png)）。

## 3. ask 输入恢复（引用既有证据）

未决提问/审批的重建走同一条 `pending_asks` 投影路径，已在 AGT-004 真机验证过：未答状态下 `install -r` + `force-stop` 重启，进入页面后提问卡片由 `pending_asks` 完整重现并可作答（`docs/evidence/agt-004/xiaomi-14-pro/result.md`）。本轮未再单独构造 ask（需要服务端 LLM 主动提问，不可确定性触发）。

## 4. 未覆盖 / 已知边界

- 「kill 时任务在跑且重启后仍在跑」的窗口只有一次实证（场景二），未做 5 次重复统计；服务端 LLM 静默 40s 的节奏使窗口不稳定。
- 未验证「进程死亡期间任务被服务端标记 aborted」的收尾文案（属 AGT-009 的错误帧路径）。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
