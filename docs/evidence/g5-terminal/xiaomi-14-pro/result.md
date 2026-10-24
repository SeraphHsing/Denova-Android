# G5 终端真机验证（Xiaomi 14 Pro / 135babde）

- 日期：2026-10-24
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 账号：隔离测试账号（凭证不入库）
- APK：debug（`assembleDebug`，含本轮全部改动）
- 自动化：`adb shell input` + `uiautomator dump` 解析 bounds 定位控件（纯 adb 点击需开启「允许通过 USB 调试修改权限或模拟点击」）

## 结论

G5 的「WS 客户端 + Compose 渲染」最小闭环**真机通过**：连上真实 shell，看到提示符，输入命令有回显，中文正文完整正确，滚动回看可用，断线能自动重连到同一 PTY，`exit` 与「关闭会话」收尾正确。

## 逐项证据

| 检查 | 结果 | 截图 |
|---|---|---|
| 创建会话 + token attach + `ready` | 通过：`attached session=67b0b840… size=80x24`，UI「已就绪」 | `01-prompt.png` |
| 输入回显 | 通过：`echo ANDROID_OK` → `ANDROID_OK`；`echo G5_OK` → `G5_OK` | `03-echo-ls.png`、`24-new-session-echo.png` |
| 中文渲染（UTF-8 + 宽字 2 格） | 通过：`cat /data/.denova/projects/*/CREATOR.md` 输出大段中文，字形/对齐/折行正确 | 截图含项目正文，按 AGENTS §5 **未入库**（本地 `06-cat-cjk.png`） |
| 布局尺寸 → cols/rows → resize | 通过：画布 68x31 时 `stty size` = `31 68`；IME 收起后 68x54 | `04-cjk-stty.png`、`11-reconnected.png` |
| Ctrl-C | 通过：`sleep 60` 后点 Ctrl-C → `^C` 并回到提示符 | `07-ctrl-c.png` |
| 滚动回看 | 通过：`seq 1 300` 后上滑，显示 242–272 行 + 指示「回看 +30 行 / 629」 | `08-scrollback.png` |
| 回到底部 | 通过：点「回到底部」回到 272–300 + 提示符，指示消失 | `09-back-to-bottom.png` |
| 断线重连 | 通过：`svc wifi disable` → 「重连中（第 1 次）…」+ `Software caused connection abort`，屏幕内容保留；开回网络后 15:53:28 重新 `attached session=67b0b840…`（**同一 session**），历史经 scrollback 回放恢复 | `10-disconnected.png`、`11-reconnected.png` |
| `exit` 收尾 | 通过：状态「已退出（code=0）」，输入/键位禁用，**不再重连**，屏幕保留 `exit` 输出 | `23-exit-clean.png` |
| 关闭会话（DELETE） | 通过：状态「已关闭」，服务端 `GET /api/terminal/sessions` → `sessions:[]` | — |
| 重新连接 | 通过：新建 `session=a1243d59…` 并 attach，`echo G5_OK` 正常 | `24-new-session-echo.png` |

## 本轮真机暴露并修复的问题

1. **`GET /api/terminal/sessions` 的 `commands` 是对象数组**（`{"id":"codex","name":"Codex CLI"}`）。
   原 DTO 声明为 `List<String>`，解析直接抛 `JsonDecodingException`，整条「列会话 → 复用/新建」路径在客户端断掉。
   修复：`TerminalCommandOption(id, name)`；新增 `TerminalProtocolTest` 用实测 JSON 回归。
2. **attach URL 手工改 `ws` scheme 导致主线程崩溃**。
   OkHttp `HttpUrl.Builder.scheme` 只接受 http/https，传 `ws` 抛 `IllegalArgumentException: unexpected scheme: ws`（FATAL）。
   修复：URL 保持 http(s)，交给 `newWebSocket()` 自行升级；新增 `TerminalAttachUrlTest`（含 token 百分号编码）。
3. **密码字段被输入法记录**：登录后输入法候选栏出现密码串（`$19950725`）。
   修复：密码与配对 token 字段改 `KeyboardType.Password` + 关闭自动纠错（`ConnectionScreen`）。

## 环境事实（不是客户端缺陷）

- 服务端 PTY 的 shell 处于 **C/POSIX locale**：bash/readline 会把提示符里的非 ASCII 与部分输出显示成 `M-x` 记法（如 cwd `我的异世界生活` 显示为 `M-fM-M-gM…`），`ls` 也会把中文名转成 `'$'\346\210\221…'`。
  同一会话内 `cat` 输出的 UTF-8 正文渲染完全正常，说明客户端解码/渲染正确。
  如需提示符可读，需要服务端会话 locale（`LANG`/`LC_ALL`）而非客户端改动。

## 未覆盖 / 待办

- D03-P 终端性能预算（mock WS 64KB/s × 300s）未做。
- 跨客户端 owner 共存（G6）：另一客户端 attach 同一会话、误删对方 PTY 的风险未测。
- 后台/锁屏期间的重连策略未专门验证（切页保持连接已验证）。
- 真机「返回」键会把 Activity 置于后台；重新进入需重新登录（Session cookie 只在内存），本轮未改。
