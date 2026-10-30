# AUTH-001 连接与认证状态（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建，`pm clear` 后全新开始）
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b），隔离测试账号
- **Web 对照**：`docs/evidence/web-baseline/2026-10-30/login-390.png`（登录卡片：标题 / 描述 / 用户名 / 密码 / 记住提示 / 登录按钮，空表单提交禁用）

## 判据与实测

| 步骤 | 结果 | 证据 |
|---|---|---|
| 输入 HTTP 服务器地址并读取 status | 通过 | `Connection: connecting to http://192.168.2.102:18081` → `status ok authenticated=false local=false` → 进入登录页 |
| 已认证时读取 status | 通过 | 登录后再连接：`status ok authenticated=true` → 直接进入编辑器（不经登录表单） |
| 未认证显示登录表单 | 通过 | 节点：`登录 Denova / 请输入设置里配置的远程访问用户名和密码。/ 服务器：http://192.168.2.102:18081 / 用户名 / 密码 / 登录状态保留 30 天，可在设置中退出登录。/ 登录 / 配对链接 token / 配对` |
| 地址非法 | 通过（JVM + 真机文案） | `ServerKey.parse` 失败 → `Failed(conn_err_invalid_server)`，不发起请求 |
| HTTPS | **环境受限（降级）** | 测试服务器只有 HTTP 部署；不关闭 TLS 校验规避。与 G1-b 同口径记录 |

## 本轮 UI 调整（对齐 Web 登录卡片）

- 新增登录标题「登录 Denova」与描述「请输入设置里配置的远程访问用户名和密码。」。
- 新增「登录状态保留 30 天，可在设置中退出登录。」提示（对应 Web `remoteAccess.rememberHint`）。
- 登录 / 配对按钮在用户名或密码为空、或提交中时禁用；提交中文案为「登录中…」「配对中…」。

## 未覆盖

- HTTPS / WSS 分支（无 TLS 部署）。
- 视觉像素级差异（输入框 32px、卡片 14px 圆角等）登记在 [09](../../android-web-parity/09-visual-gap-backlog.md) VG-007，P5 统一对齐。
