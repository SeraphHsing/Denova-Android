# AUTH-007 连接超时与恢复（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建）
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b）

## 判据与实测

| 步骤 | 结果 | 证据 |
|---|---|---|
| 断网后连接超时 | 通过 | `svc wifi disable` 后点连接：状态变 `Failed`，界面显示「连接失败」+「无法连接服务器」+「重试」，**地址输入保留**（`192.168.2.102:18081`） |
| 地址错误导致失败后保留输入 | 通过 | 地址改为 `192.168.2.102:11` → 连接失败；「重试」再次失败；地址仍是 `192.168.2.102:11`（未被分支切换重置） |
| 手动重试 | 通过 | `Failed` 态点「重试」→ 复用同一客户端重跑 status（不重建 CookieJar），地址与输入不丢 |
| 网络恢复自动重连 | 通过 | `svc wifi enable` 后 logcat：`network available, retrying` → `reconnecting to http://192.168.2.102:18081` → `status ok authenticated=false`，界面自动回到登录页（无需再点连接） |
| 修改地址后重连 | 通过 | 改回正确地址 → 连接 → 登录页；同一 origin 复用客户端，换 origin 新建客户端（Cookie 按 origin 隔离） |

## 实现要点

- 地址输入提升到 `ConnectionScreen` 层，分支切换（Empty / Checking / Failed）不会重建输入框状态。
- `retry()` 复用 `lastInput`；`connect()` 在 origin 相同时复用既有 `DenovaClient`（保留 Cookie 与在途请求），否则新建。
- `ConnectivityManager.registerDefaultNetworkCallback` 在 `onAvailable` 时仅对 `Failed` 态自动重连（已认证时不重复拉 status，未认证时不打断用户输入）；`onCleared` 反注册。
- 新增 `ACCESS_NETWORK_STATE` 权限。

## 未覆盖

- 无线网络切换到移动数据、以及弱网下的慢超时（默认 OkHttp 超时）未逐档测量。
- 自动重连退避：只在网络恢复瞬间重试一次，失败后仍靠用户点「重试」（与 Web 的 online 事件一次性 refetch 一致）。
