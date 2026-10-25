# AGT-011 会话切换迟到回包

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：`default`（A，服务端 active）与 `s-agent-mvg8yb86-2`（B）
- 对应矩阵：`AGT-011`（Agent消息与任务 / 会话切换迟到回包；steps「A运行中切B等待A回包」，expected_ui「B内容不被A改变」，expected_server_effect「A仍绑定原任务和恢复状态」）
- 实现：
  - `app/src/main/java/com/denova/novara/core/stream/SessionScope.kt`（新增：会话世代）
  - `app/src/main/java/com/denova/novara/feature/agent/AgentViewModel.kt`（`start` / `attachLoop` / `reloadCanonical` / `refreshActive` / `reconcile` / `finishStream` / 队列轮询全部按「世代 + 会话」双向判定）
- 单测：`app/src/test/java/com/denova/novara/SessionScopeTest.kt`（4 用例，新增）

## 1. 变更要点

| 变更 | 原因 |
|---|---|
| 新增 `SessionScope`：切换会话推进世代，回包必须「世代与会话都还是当前」才允许落状态 | 只靠 `Job.cancel()` 不够——取消在下一个挂起点才生效，已经拿到响应、正在写状态的代码仍会执行，A 会话迟到的 canonical 页 / 流帧 / active 投影会改写 B 会话 |
| `start()` / `send()` 的 Catch 块先重抛 `CancellationException` | **真实缺陷**：两处都是 `catch (e: Exception)`，切会话触发的取消被吞掉，接着把 `error`、`sending=false`、「发送结果不明」写进**新会话**的状态，B 会莫名出现 A 的错误提示 |
| `onFrame` 与发送流的 `onEvent` 增加世代判定 | 取消前的最后一两帧属于旧会话，不得投影到新会话正文 |
| `reloadCanonical` / `applyCanonical` / `reconcile` / `refreshActive` / `finishStream` / `ensureQueueTicker` 全部带世代 | 这六处都会写状态，且都可能跨会话迟到（历史页、核对结论、active 投影、流收尾、队列轮询） |

## 2. 真机验证

### 2.1 A 运行中切到 B，B 内容不被 A 改变

`default` 发送 `go`（`I Agent: send session=default cmd=…`）后 3s，把 session ID 改成 `s-agent-mvg8yb86-2` 并点「挂接」：

```text
I Agent: start session=s-agent-mvg8yb86-2
I Agent: canonical parts loaded messages=72 tools=65 has_more=false total=73 session=s-agent-mvg8yb86-2
```

界面显示 B 自己的历史（首条用户消息「将资料库所有文件按系统格式导出到/data/.denova」）、`状态：空闲`。A 的回复窗口（约 40s 静默 + 26s 正文）内连续观察 12s，**文本条数与状态均未变化**，没有 A 的正文、没有 A 的错误提示。

### 2.2 切回 A：仍绑定原任务并恢复

把 session ID 改回 `default` 并「挂接」，A 正在运行时：

```text
I Agent: start session=default
I Agent: canonical parts loaded messages=48 tools=12 has_more=true total=170 session=default
I Agent: attach task=task-bb2660c3de54acbb0d39a661d49d9494-2s cursor=6 attempt=0
```

`task` 与 `cursor` 来自 `GET /api/chat/active`（按会话作用域查询），随后流继续并把状态收到「完成」（[reattach-after-switch.png](reattach-after-switch.png)）。即 A 的任务绑定与恢复状态没有因为中途去看 B 而丢失。

### 2.3 迟到回包丢弃的确定性覆盖

真机上 B 保持不变的直接原因是旧 job 被取消；「取消在挂起点之后仍有写入」这条竞态窗口无法在设备上稳定制造，因此由 `SessionScopeTest` 的 4 个用例固定判定口径（旧世代 + 旧会话、旧世代 + 新会话、同会话再次进入均判失效）。

## 3. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL   suites=56 tests=393 failures=0
```

## 4. 未覆盖 / 已知边界

- 「A 的迟到帧正好落在 B 的写状态窗口内」的真机时序未构造（需网络延迟注入），由单测覆盖判定逻辑。
- 会话列表 / 新建 / 切换 UI 仍未做：本轮切换用的是页面上已有的 session ID 输入框 + 「挂接」。
- 未在 B 会话里再做发送，因此「B 自己运行中再切回 A」的交叉时序未覆盖。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
