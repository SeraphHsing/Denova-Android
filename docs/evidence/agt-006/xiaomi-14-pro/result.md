# AGT-006 追加与转向 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug（AGP 9.3.0 内置 Kotlin / Compose BOM 2026.09.00）
- 服务器：`http://192.168.2.101:18098`，版本 0.6.3，会话 `default`
- 时间：2026-10-25 22:39–22:40
- 探针：只读脚本登录同一账号后轮询 `GET /api/chat/active?session_id=default`（凭证仅从本机记忆文件读取，不落仓库、不回显）

## 1. 运行时输入走 `follow_up`

| 时刻 | 动作 | 观测 |
|---|---|---|
| 22:39:07 | 输入 `write 3000 word story` 后提交 | 日志 `send session=default cmd=fa79…`，走 `POST /api/chat` 新开操作 |
| 22:39:11 | 首帧到达后探测 active | `active=true phase=running op=run-20261025T143907.043917604-056af595924f` |
| 22:39:20 | 输入 `tick` 后提交（按钮文案已由「发送」变「追加」） | 日志 `follow-up session=default cmd=20786d46-270d-4ba9-918b-7327b9304f6c target=run-20261025T143907.043917604-056af595924f refs=0` |
| 22:39:20 | 探测 active | `queue=1`，项 `cmd=20786d46… op=run-20261025T143907… delivery=follow_up msg=tick`，`operation_id` 与 active 逐字符一致 |

追加不新开流、不取消在途 `sendJob`：同一运行继续出流，队列项由服务端在轮次边界执行。

## 2. 队列投影与执行

- 受理后界面出现「本轮已追加」区，逐项给出 `• message` 与 转向 / 删除（双项状态见 `queued-followups.png`，同一路径在 AGT-007 中复用于逐项控制）。
- 22:40:13 服务端 `queue=0`（轮次边界消费），流继续输出新的正文段。
- 22:40:31 截图 `queue-consumed.png`：队列区已收起、状态仍「运行中 · 已连接」、输入区按钮仍为「追加」，说明追加项被作为下一轮输入执行而不是丢弃。
- 22:39–22:40 期间另有 `alpha` 一次追加，被服务端消费后模型就 `alpha` 发起澄清提问（`data-agent-ask` 卡片），可交叉印证「追加内容进入执行链」。

## 3. 队列投影与服务端对齐

队列消费没有 SSE 信号（`AgentUiFrame.completesTurn()` 只反映整轮结束），因此新增 `ensureQueueTicker`：队列非空时每 5s 取一次 active，队列清空后循环自行退出，不做常驻轮询。上述 22:40:13 消费到 22:40:31 队列区收起即由它完成（期间无任何用户操作）。

## 4. 门控与失败路径

- 空闲（`active_operation_id` 为空）时按钮为「发送」，走 `POST /api/chat`；只在服务端投影给出当前操作时才判为追加。
- 追加命令 4xx 记 `REJECTED`、异常记 `UNCERTAIN`，均不自动重发；`agent_runtime.target_operation_mismatch` 等目标换代错误触发一次 active 刷新纠正，不把命令打到旧操作。
- `input` 只在 `follow_up` 出现；`abort` / `suspend` / 队列控制的请求体不含 `input`，`target_command_id` 仅在队列控制出现（单测断言命令线格式）。

## 5. 同轮修复的三处真机缺陷

| 现象 | 原因 | 修复 |
|---|---|---|
| 「发送 / 追加」按钮被 debug 悬浮入口整块盖住，真机完全点不到 | `MainActivity` 的 Debug FAB 贴底右对齐，与输入区按钮同区域 | 移除悬浮按钮，入口移到左抽屉「调试日志」（`AppDrawer.onOpenDebug`），面板仍覆盖在最上层 |
| 暂停 / 长推理时输入区被挤出屏幕（只剩 65px 条） | 「思考：…」正文不限行且不可滚动，把消息区与输入区压出可视区 | 推理预览 `maxLines = 4` + `TextOverflow.Ellipsis` |
| 键盘弹出时输入区被键盘遮住，`追加` 不可见 | Activity 未声明 `adjustResize`，`imePadding()` 拿不到有效 inset | `AndroidManifest` 增加 `android:windowSoftInputMode="adjustResize"` |

键盘动作同时补 `ImeAction.Send`（`KeyboardActions(onSend)` 与右侧按钮同一入口），见 `ime-send.png`。

## 6. 验证

- JVM：`AgentQueueTest` 5 例（队列解析、`queue:null` 兜底、追加分流判据、命令体、序列化不含可选字段）。
- 主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → BUILD SUCCESSFUL，50 suites / 340 tests / 0 failures（含 AGT-007 用例）。
- 真机：本轮 §1–§3 全部在本机完成；仪器测试未执行。

## 7. 残留

- 队列项「取回编辑」已补：输入区草稿改由 `AgentViewModel`（`composerDraft`）持有，`cancel_queued`（`reason=returned_to_editor`）成功后把正文回填，详见 `docs/evidence/agt-007/xiaomi-14-pro/result.md` §7。
- `message_truncated=true` 的队列项在正文后标「（已截断）」；真机未构造出被截断的正文（阈值需长文本，`adb input text` 长串丢字符），仅单测覆盖。
