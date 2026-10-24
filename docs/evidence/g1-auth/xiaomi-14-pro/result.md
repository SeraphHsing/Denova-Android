# G1 认证与受保护资源：真机与真实服务器记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 对应矩阵：`AUTH-002`（用户名密码登录 → 读取受保护项目与图片）

## 1. 登录与受保护资源

| 步骤 | 结果 |
|---|---|
| 启动后点「连接」 | `Connection: status ok authenticated=true local=false`（持久化 Cookie 免登录） |
| 读取受保护文件 | 编辑器打开 `ideas.md`：`opened … revision=sha256:459b244d…` |
| 读取受保护图片（带会话） | `GET /api/projects/:id/files/asset?path=assets/lore/79e172b5-….png` → **200 `image/png` 3,515,744 B** |
| 同一 URL 不带会话 | **401**（确认是受保护资源，不是公开静态文件） |
| 应用内渲染 | Debug 面板「读取图片」→ Coil 用共享 `okHttpClient`（含会话 Cookie）加载，图片正常显示（截图 `g1-asset.png`，仅本地保留） |

实现：`DenovaClient.projectAssetUrl()` 生成 `/files/asset` 地址；`core/image/AuthenticatedImageLoader.kt` 用 Coil3 的 `OkHttpNetworkFetcherFactory` 复用 `DenovaClient.okHttpClient`，因此不需要额外注入 token。依赖 `io.coil-kt.coil3:coil-compose` / `coil-network-okhttp`（3.6.3），**只覆盖 http**（`usesCleartextTraffic=true`），未放宽 TLS 校验。

## 2. 退出时等待进行中读取（真机）

`DenovaClient.logout()` 先用 `InFlightTracker`（OkHttp `EventListener` 计数在途 call）等请求收尾（上限 3s），再清会话。真机验证：发送 Agent 长提示后立即返回编辑器并点退出，SSE 仍在流式生成：

```text
I Connection: logout
I Connection: logout drain pending=1 drained=false after=3000ms
```

即退出动作先观察到一个在途 SSE，等待 3s 超时后照常完成退出（长连接不会无限阻塞退出）。单测 `InFlightTrackerTest` 覆盖计数、幂等减、awaitIdle 超时/成功四个用例。

**说明**：WebSocket 的 `callEnd` 在升级握手后即触发、不代表 socket 关闭，因此终端 WS 不参与该计数——它有自己的 `onCleared` 生命周期（见 G5/G6）。

## 3. 一次性配对：真机 + 真实服务器通过

`POST /api/auth/link`（创建配对链接）受 `localHostEffectMiddleware` 限制，且 Denova 跑在容器里（宿主机 18081 → 容器 8080），宿主机 loopback 会被判为 docker 网关而拒绝。经 SSH 到 NAS、`docker exec denova` 进入容器后以容器内 loopback（`127.0.0.1:8080`）创建配对链接：

```text
http://192.168.16.2:8080/#pair=<token>
```

随后用 `tools/g1_pair_consume.py` 消费同一个 token 两次：

```text
pair first=200 second=401   PASS
```

即一次性配对语义成立：首次消费进入认证态，二次消费被拒（`api.access.pairingInvalid`）。`AUTH-003` 据此回写为已完成。

## 4. HTTPS / WSS：验证已放弃（环境限制，用户决定）

测试服务器只监听 `http://192.168.2.102:18081`，443 端口是 NAS 自身的 nginx（302 到 `:725`），没有 Denova 的 TLS 端点。客户端侧 OkHttp 本身 scheme 无关（`ServerKey` 支持 `https`，WebSocket 用 `wss`）。

2026-10-24 曾尝试用 caddy 起 TLS 反代（`18443`，`tls internal` 自签 + 反代 `127.0.0.1:18081`）：容器可正常启动、在 NAS 监听 `*:18443`，但 Windows 与 NAS 本机 curl 均 `CODE:000`（TLS 握手层面失败，疑似 host 网络下 HTTP/3 与 TCP 监听冲突）。**用户决定放弃 TLS 验证**，容器与临时文件已清理。

结论：`AUTH-001` 的 HTTPS 一半与 WSS 未执行，按环境限制登记；不关闭 TLS 校验规避（AGENTS §5）。

## 5. 迟到 401：随 P2 处理

按 2026-10-24 决定，请求世代 / 退休机制并入 P2 的请求基础设施实现，`AUTH-004` 暂不验证。

## 本次新增/修改的代码

- `core/network/DenovaClient.kt`：新增 `projectAssetUrl(projectId, path)`
- `core/image/AuthenticatedImageLoader.kt`：Coil3 ImageLoader（复用会话 OkHttp）
- `feature/debug/DebugPanelScreen.kt`：受保护图片探针（项目 ID + 资源路径 → 加载显示）
- `MainActivity.kt`：把 `client` 传给 Debug 面板

## 限制

- 图片加载目前只通过 Debug 探针验证；写作预览（WebView）与资料库页面尚未接入，属 P3/P4 范围。
- 只测 http；https 未验证。
- 配对与 HTTPS 两项需要服务器本机权限或 TLS 部署，超出当前环境。
