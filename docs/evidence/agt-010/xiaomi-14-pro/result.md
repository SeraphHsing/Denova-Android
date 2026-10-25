# AGT-010 历史向上分页与读锚点 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug；服务器 `http://192.168.2.101:18098`，会话 `default`（该会话累计 157 条消息）
- 时间：2026-10-25 23:30–23:41
- 探针：只读轮询 `GET /api/chat/active`；分页口径由应用日志 `canonical parts loaded … has_more=… total=…` 与 `older history loaded added=… total=…` 记录

## 1. 挂接只取一页

挂接后日志：`canonical parts loaded messages=45 tools=8 has_more=true total=157`，界面贴底显示最新消息。
首屏只取 `limit=50` 的一页，不因为「还没贴底时索引是 0」而自动把历史翻完（初版实现踩过这个坑，已用「贴底一次后才允许顶部翻页」的门控修掉）。

## 2. 顶部入口翻页

滚到列表顶部后点击「加载更早的消息」：

```
older history loaded added=47 total=92 has_more=true before=106
```

`added=47` 说明这一页向前补了 47 条，`total=92` 与「45 + 47」一致，没有把同一消息算两遍。

## 3. 读锚点保持

翻页前记下当前首条消息 id 与偏移，回包后按 id 重新定位：

```
anchor effect size=92 loading=false anchor=agent-message-…@…
anchor restore index=47 offset=0
```

界面停在同一段正文上，插入的更早消息被放在视线之上（`anchor-kept.png`：与翻页前看到的是同一条消息，顶部入口已滚出屏幕）。

**身份稳定性（关键缺陷）**：原先消息身份是 `"$id@$位置"`，翻页把旧消息插到前面后所有 id 都变，锚点找不到、`LazyColumn` key 也全变。改为 `"$id@$内容摘要"`：跨页稳定，同时仍能区分共用同一 run id 的 `agent-token-usage` 与 `agent-execution-summary` 两条消息（`AgentPartsTest` 与 `AgentHistoryTest` 各有断言）。

## 4. 新消息提示（不在底部时）

用户正在往上读时流里来了新内容：界面不抢滚动位置，改在列表下方显示「有新消息，点此回到底部」（`new-messages-chip.png`，23:41 截图，同时可见暂停态与已挂的追问卡片）；点击后回到底部并清掉提示。

只在「流正文真的变长」时挂提示：向上插入旧消息不算新消息（首版把翻页误判成新消息，已修）。

## 5. 验证

- JVM：`AgentHistoryTest` 8 例（分页元信息、无 page 字段兜底、前置合并、边界去重、同页幂等、空 id 丢弃、取回编辑命令体、截断项解析）+ `AgentPartsTest` 身份稳定性断言。
- 主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → BUILD SUCCESSFUL，50 suites / 342 tests / 0 failures。
- 真机：§1–§4 在本机完成；仪器测试未执行。

## 6. 残留

- 滚动到顶部的**自动**翻页被移除：`firstVisibleItemIndex` 在挂接与锚点复位时会短暂回到 0，自动触发会把整段历史一口气翻完（真机复现两次）。当前只保留顶部可见入口，点击即翻页；自动触发的可靠实现需要服务端给出「已到顶」边界或改用 `LazyColumn` 的滚动事件，登记为缺口。
- 会话切换 / 服务端补写历史后不做增量合并，仍以重新加载当前页为准。
