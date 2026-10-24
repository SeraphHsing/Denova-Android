# G6 双端共存验证（Android + 第二客户端）

- 日期：2026-10-24
- Android：Xiaomi 14 Pro（debug），服务器 `http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 第二客户端：`tools/g6_dual_client.py`（真实后端 + 真实 token 的 WS/REST 客户端；凭证走环境变量）
- 第二客户端**不是** Web 浏览器：本轮验证的是服务端契约下的双端行为，浏览器特有的清理逻辑见「限制」

## 服务端契约（决定客户端行为的事实）

| 事实 | 依据 |
|---|---|
| `owner_tab_id` 只用于**创建去重**：同项目复用同一 pty 与同一 token；同 owner 跨项目 → `409 api.terminal.ownerConflict` | `internal/terminal/manager.go:252-266`、`internal/api/handlers/handler_terminal.go:366-376` |
| attach 校验只有 `id + token`，**不校验 owner/用户**；多端 attach 共享同一份输出、不踢人；`attached = len(subs)` | `handler_terminal.go:154-164`、`internal/terminal/session.go:224-241` |
| resize 多端在线时**最后写入者获胜** | `internal/terminal/session.go:201-202` |
| `DELETE /api/terminal/sessions/:id` **不校验 owner**，直接 kill pty 并移出列表；对端是**静默断 TCP（无 exit 帧）**，幂等返回 `{closed:false}` | `handler_terminal.go:123-141`、`session.go:370-389`、`handler_terminal.go:300-313` |
| 列表把 attach token 下发给任何已认证调用者 → **拿到列表即可接管** | `handler_terminal.go:40-73` |
| Web 端：切页只断 WS、关标签才 DELETE；启动会回收 `attached===0 且不被任何持久化标签拥有` 的会话 | `web/src/features/agent-chat/tab-state.ts`、terminal 相关 store（静态核对） |

## 场景结果

| 场景 | 结果 | 证据 |
|---|---|---|
| S1 双端同时 attach 同一会话 | **通过**：脚本用列表里的 token 挂接成功，`attached` 1 → **2**；脚本发 `echo G6_SECOND`，Android 端实时看到该命令与输出 | `01-second-client-attached.png` |
| S2 另一端 DELETE 正在使用的会话 | **通过**：服务端 `closed:true`、列表清空；Android **7–11s** 内落到「连接失败 / 终端会话已在其他端释放，内容未自动重建」（重测 11s，受 2s 轮询与 20s 空闲对账间隔影响），屏幕内容保留、**未静默新建空 PTY**（列表保持 `sessions=0`） | `02-released-by-peer.png` |
| S3 同一 owner 被另一个项目占用 | **通过**：脚本在项目 B 用 `owner=android-main` 建会话后，Android 连项目 A 得到 `409 ownerConflict`，UI 显示「该终端归属已被另一个项目占用：先在原项目关闭终端，再回到本项目重试」，**不再进入重连循环** | `03-owner-conflict.png` |
| S4 释放 owner 后恢复 | **通过**：删除 B 会话后 Android 点「连接」新建并 attach（`attached=1`），无残留状态 | 日志 `attached session=a3c80164…` |

## 本轮暴露并修复的 3 个问题

1. **终端 REST 抛的是 `GameStreamError`**（SSE 的错误类型），`code/status` 语义丢失，导致 `ownerConflict` 无法识别、被当成可重试错误无限退避。
   修复：create/list/delete 三个终端端点改抛 `DenovaApiException`；新增错误键 → 中文原因的映射（ownerConflict / tokenInvalid / notFound / tooMany / disabled），并在 `runSessionLoop` 里命中即终止重试。
2. **会话被其他端释放后会静默新建一个空 PTY**：用户只看到内容清空、以为历史丢了，而服务端其实已被释放。
   修复：记住 `previousSessionId`，自动重连时若列表里已无该会话 → 报「已在其他端释放，内容未自动重建」并停手；用户**显式点「连接」**才允许新建（显式意图 vs 自动恢复要分开）。
3. **静默断 TCP 的发现延迟约 40s**：DELETE 不 close frame，只靠 idle 看门狗（45s）才会发现，界面长时间停在「已连接」。
   修复：流安静 ≥20s 时顺带对账一次会话列表（每个心跳周期最多一次 REST），实测发现时间降到 **7–9s**。

## 限制（记录，不构成通过）

- **token 即权限**：`GET /api/terminal/sessions` 把 attach token 下发给任何已认证客户端，服务端不区分「谁的会话」。Android 无法阻止同账号的另一端接管自己的 PTY —— 这是服务端设计，客户端只能在 UI 上如实反馈（本轮已做）。
- **DELETE 无 owner 校验**：任一认证客户端可删任意会话；Web 端挂载工作台时的孤儿回收（`attached===0 且无持久化标签拥有`）会**误删 Android 的会话**——本文件下节已在真实浏览器中复现。
- **多端 resize 互相覆盖**（最后写入者获胜）：本轮未做尺寸协商；两端尺寸不同会互相改 PTY 宽度，长行可能被提前折行。
- 第二客户端是脚本而非 Web 浏览器：G6 退出条件中「权限表现一致」只覆盖了终端相关契约，14 个设置 section 的权限分层仍属 P4-D 范围。

## Web 端实测：孤儿回收会误删 Android 的 PTY（R06 确认）

> 首次观测后怀疑「用户手动关过一个终端」可能污染结果，因此**重做了一次带对照组的实验**：先建一个 `attached=1`（对照组，应被保护）和一个 `attached=0`（实验组）的会话，再让浏览器挂载工作台；随后单独把 Android 会话从 1 降为 0 复测。结论与首次一致。

- 环境：本机 Chrome（`playwright-core` + `channel: chrome`，headless），登录后进入「工作台」。
- 实验 1（对照）：会话 `4934a744…`（owner=`android-main`，**attached=1**，Android 正在连着）+ 会话 `c1de3996…`（owner=`terminal-fake-1`，**attached=0**）→ 浏览器挂载工作台：

```
GET    /api/terminal/sessions
GET    /api/terminal/sessions
DELETE /api/terminal/sessions/c1de3996…      <- 只删 attached=0 的
GET    /api/terminal/sessions  → 剩 4934a744…（attached=1，未被删除）
```

- 实验 2（隔离变量）：`force-stop` Android 使 `4934a744…` 变为 **attached=0**（服务端会话仍在）→ 重新挂载工作台：

```
GET    /api/terminal/sessions
DELETE /api/terminal/sessions/4934a744…      <- Android 的 PTY 被删
GET    /api/terminal/sessions  → sessions: []
```

- 结论：Web **挂载工作台时**删除所有 `attached===0` 且不在其本地持久化标签里的会话，**不区分 owner、不区分项目**；`attached=1` 的会话不会被删。Android 的 PTY 与其 scrollback 因此在用户无感知的情况下被释放。
- 触发条件：只在进入/挂载工作台时发生；单纯登录或首页加载只读取列表（未产生 DELETE）。
- 判据影响：G6 退出条件写明「误删对方 PTY 或丢失会话内容时未通过，仅记录限制不能退出」。**G6 判为未通过**，需要范围决策（见下面的选项）。
- 截图：`04-web-workbench-cleanup.png`（触发回收时的工作台）、`05-web-terminal.png`（Web 终端可正常 attach 自己的会话）。

## 时序排查：客户端能避开哪些 `attached=0` 窗口

浏览器回收只发生在「挂载工作台」那一刻，因此唯一能减少误删的做法是缩小 Android 处于 `attached=0` 的时间。实测（真机 + `GET /api/terminal/sessions` 轮询）：

| 场景 | `attached` 观测 | 结论 |
|---|---|---|
| 终端页前台 | 1 | 基线 |
| 切页回编辑器 +10s | **1**（无断连） | 终端 ViewModel 保活连接，**切页没有窗口** |
| HOME 后台 30s | **0** | 系统断开 socket；进程被冻结，`logcat` 里连 `disconnected` 都没有 |
| 后台再 60s | 0 | 窗口长度 ≈ 后台时长，线性变长 |
| 回前台 | **2s** 内回到 1 | 解冻后心跳/重连立即生效 |
| 回前台（加 `ON_RESUME` 钩子后复测） | **1.4s**（含 1s 轮询间隔） | 解冻即主动重挂，不再等心跳周期 |

推论（不靠后台保活的前提下）：

- **可避开且已覆盖**：切页（0 窗口）、回前台的恢复（≈2s）。
- **不可避开**：① 后台/锁屏期间——进程被系统冻结，socket 被系统断开，客户端在冻结期内既无法感知也无法重挂；② 进程被系统回收或用户划掉——`attached` 保持 0 直到重新打开应用，而当前 Cookie 只在内存，重启后还要重新登录，窗口从秒级变成分钟级。
- 因此「靠时序绕开」的上限就在这里：再优化只能把回前台的 2s 压到 <1s，对回收窗口没有实质影响。

可选（需决策）：把登录会话 Cookie 持久化到应用私有存储，使进程死亡后重启可免登录并立即重挂，从而把「分钟级窗口」压到「打开应用 → 秒级」。这会与 G1「Cookie 仅保留在内存、按服务器隔离」的决定冲突，需明确批准。

## 缓解方案 B + C 落地与验证（2026-10-24）

用户（Hsing）决定采用 **B（本地留存近期 scrollback）+ C（持久化登录 Cookie）**。注意：两者都是**降低损失**，不改变「Web 挂载工作台会回收 attached=0 会话」这一基线行为，因此仍不构成满足原判据，G6 收口仍需一条范围决定。

| 项 | 实现 | 真机验证 |
|---|---|---|
| C 持久化会话 Cookie | `core/persistence/CookieStore` + `PersistentCookieJar`（按 server origin 隔离、HttpOnly/Secure/过期时间一并保存；退出登录清空）；`DenovaClient` 注入该 jar | `force-stop` → 重启 → 直接进编辑器，日志 `status ok authenticated=true local=false`，**无需重新登录** |
| B 本地历史快照 | `core/terminal/TerminalHistory`（回滚+主屏 → 纯文本，按 **64KB 上限**从最旧行整行丢弃）+ `TerminalHistoryStore`（按 origin+项目落盘）；`giveUp` / `exit` 时抓取；错误行新增「查看历史」→ 只读对话框 | 会话被对端 DELETE 后出现「查看历史」，点开显示本地快照；落盘文件 1410B，尾部含 `# echo B_SNAPSHOT_OK` 与输出 `B_SNAPSHOT_OK` |

新增 10 个 JVM 测试（`TerminalHistoryTest` 5 个：行序/尾部空行/64KB 整行截断/CJK 不截断半字符/空屏；`CookieStoreTest` 5 个：重启复用/过期丢弃/跨服务器隔离/退出清空/HttpOnly 保留）。本地 **196 测试 + Lint + Debug 全绿**。

截图：`07-local-history.png`（只读历史对话框；对话框从最旧行起显示，可滚动到最新输出）。

仍存在的损失：PTY 被回收后**无法继续在原 shell 里操作**，只能回看历史。

## G6 判定

| 判据 | 结果 |
|---|---|
| 双端操作不误删对方 PTY | **未通过**：Web 挂载工作台即删除 Android 的 attached=0 会话（已实测）。时序排查表明可避免窗口已覆盖（切页 0 窗口、回前台 2s），但后台冻结与进程死亡造成的窗口在不做保活的前提下无法消除 |
| 不丢失会话内容 | **未通过**：会话被处理后 PTY 与 scrollback 一并消失；App 侧已做到「不静默重建 + 如实提示」，但不能保住内容 |
| 权限表现一致 | 部分：终端相关契约一致；设置 section 权限分层属 P4-D |
