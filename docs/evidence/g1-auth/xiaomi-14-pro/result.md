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

## 2. 一次性配对：不可在本机验证

`POST /api/auth/link`（创建配对链接）受 `localHostEffectMiddleware` 限制：

```text
POST /api/auth/link → 403
{"code":"http.403","error":"此系统操作只能从运行 Denova 的本机触发"}
```

因此从局域网客户端无法生成配对 token，`AUTH-003`（一次性配对）保持未验证；要验证需在服务器本机执行创建链接，再由客户端消费并尝试二次消费（预期第二次 401）。

## 3. HTTPS / WSS：无可用环境

测试服务器只监听 `http://192.168.2.102:18081`，没有 TLS 端口，`AUTH-001` 的「HTTPS」一半与 `AUTH-006` 的 WSS 均无法在此环境验证。

## 4. 迟到 401：随 P2 处理

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
