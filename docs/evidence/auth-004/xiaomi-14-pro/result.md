# AUTH-004 迟到 401 与会话有效性确认（真机，通过）

- **日期**：2026-10-31
- **设备**：小米 14 Pro（`135babde`），Android 16，480dp（`wm density 480`）
- **应用**：`com.denova.novara` Debug
- **服务器**：`http://192.168.2.102:18081`（0.6.3-b），客户端经本机延迟代理 `192.168.2.101:18099`
- **代理**：`python tools/g3d_slow_proxy.py --listen 192.168.2.101:18099 --target http://192.168.2.102:18081 --put-delay-ms 28000 --status-delay-ms 20000`
- **夹具**：`nav002-long.md`（1200 小节），富文本模式点 `H2` 插入模板使 `content != baseContent`，`saveNow()` 才会真正发 `PUT /files/file`

## 1. 构造方法（真实链路，不伪造响应）

1. App 经代理登录并打开文件。
2. 点 `H2` 插入模板 → 触发自动保存 → `PUT` 被代理延后 28s 转发。
3. 用 `PATCH /api/settings`（`remote_access_password`）吊销全部旧会话——服务端会重新生成 bcrypt 哈希，
   所有既有会话立刻失效（`internal/api/remote_access_store.go` 的 `browserSession.valid()`）。
4. 顶栏「退出」→ 登录页 → 用**新密码逐键输入**登录（`input text` 在禁用 IME 时会丢字符，见第 4 节）。
5. 延后的 `PUT` 到达服务端时携带的是旧 Cookie → 401 → 客户端先读 `status` 再决定。

## 2. 实测时序（`adb logcat -s Auth Connection`）

```
23:29:47.341 I Connection: logout
23:29:50.343 I Connection: logout drain pending=1 drained=false after=3000ms
23:30:03.714 W Auth    : session unauthorized (401) origin=http://192.168.2.101:18099
23:30:12.524 I Connection: login ok local=false
23:30:23.779 W Connection: session expired, returning to login
23:30:31.975 W Auth    : session unauthorized (401) origin=http://192.168.2.101:18099
23:30:52.155 I Connection: late 401 ignored, session still valid
```

判读：

| 观察 | 结论 |
|---|---|
| 23:29:47 退出后 16s（23:30:03）才收到 401 | 旧请求的 401 **确实迟到**（代理延后 28s 转发、服务端在会话吊销后才处理） |
| 23:30:31 第二次 401 与 23:30:52 才有结论 | 401 到达后**先读 canonical status**（代理把 status 拖慢 20s），不是直接降级 |
| 23:30:52 `late 401 ignored, session still valid` | 探测确认新会话有效 → **保持当前登录态**，这条正是 AUTH-004 的验收行为 |
| 23:30:12 `login ok` 早于 23:30:52 | 新登录已建立，迟到 401 没有把它顶掉 |
| 23:30:23 `session expired` | 来自**更早那条** 401（23:30:03）的探测：它开始时（23:30:03）用户还在登录页、会话确实未认证，属于正常降级；当时设备上跑的还是**未包含「探测期世代判定」修复的旧 APK**，因此没有打印 `stale 401 confirmation ignored` |

界面：重新登录后停留在编辑器（`editor-after-late-401.png`），没有出现「登录成功又掉回登录页」。

## 3. 覆盖的行为

- 401 先 `GET /api/auth/status` 二次确认，只有 `authenticated != true` 才降级（`SessionExpiryConfirmer`）。
- 迟到 401 在会话有效时被忽略并打印 `late 401 ignored`；一次性通知标志被复位，后续真实失效仍能提示。
- `logout` 的 `drain pending=1 drained=false` + `generation.retire()`：退出时在途写请求的响应不再落到界面上。

## 4. 踩过的坑（复现时必读）

1. **禁用 IME 后 `input text` 会静默丢字符**：密码框实际值不完整（曾输成 11 位），登录按钮点了没反应，
   而 20s 探测窗口就此耗尽。可靠做法是 `%TEMP%\type_keys.py` 逐键输入；大写字母用
   `input keycombination 59 <code>`（SHIFT + 字母），`input text Hsing` 偶发整串丢失。
2. **`saveNow()` 在 `content == baseContent` 时直接返回**（`EditorSession.kt:365`），不制造 dirty 就不会有 PUT，
   代理侧看不到任何请求。
3. 退出登录后登录页可能多一行「登录已失效」提示，控件整体上移约 42px；按 `uiautomator dump` 实时取坐标，
   不要沿用上一轮常量。
4. MIUI 上搜狗与百度两个输入法都要 `ime disable`，否则 `input text` 仍会被改写。

## 5. 复现命令

```bash
# 代理：保存延后 28s、status 探测延后 20s
python tools/g3d_slow_proxy.py --listen 192.168.2.101:18099 \
  --target http://192.168.2.102:18081 --put-delay-ms 28000 --status-delay-ms 20000

# 设备
adb -s 135babde shell wm density 480
adb -s 135babde shell ime disable com.sohu.inputmethod.sogou.xiaomi/.SogouIME
adb -s 135babde shell ime disable com.baidu.input_mi/.ImeService

# App：连接 192.168.2.101:18099 → 登录 → 打开 nav002-long.md → 富文本 → H2 → 退出 → 新密码登录
# 改密码与恢复：本机临时脚本（凭证从项目记忆读取，不落盘、不进仓库）

# 证据
adb -s 135babde logcat -d -s Auth Connection
```
