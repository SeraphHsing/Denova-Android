# AUTH-006 服务器作用域隔离（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建，`pm clear` 后全新开始）
- **两个 origin**：
  - A = `http://192.168.2.102:18081`（真实服务器，后端 0.6.3-b）
  - B = `http://192.168.2.101:18099`（本机 `tools/g3d_slow_proxy.py` 转发到 A，无延迟；只用于制造第二个 origin，内容与 A 相同）

## 实现

- 草稿身份改为 `origin \0 projectId \0 path`（`EditorSession.draftKey`），由 `EditorViewModel.bindClient` 传入 `client.serverOrigin`。
- 升级前的旧格式 `projectId \0 path` 草稿：首次打开时读入并**迁移到当前 origin、同时删除旧 key**（只迁一次，否则同一份旧草稿会被多个服务器先后读到）。
- 登录页新增「更换服务器」入口（回到地址输入态）；`changeServer()` 丢弃当前 client 并 retire 世代，旧会话在途回执按 `c !== client` 作废。
- Cookie 仍按 origin 持久化（`PersistentCookieJar(origin)`），标签在换服务器时清空（`bindClient`）。

## 判据与实测

| 步骤 | 结果 | 证据 |
|---|---|---|
| A 编辑后断网制造草稿 | 通过 | A 打开 `ideas.md`，断网后输入 `aa` → 显示「错误 / 保存失败：Failed to connect to /192.168.2.102」，草稿落盘（key 含 A origin） |
| 改连 B 后打开同一文件 | 通过 | 退出登录 →「更换服务器」→ 地址改为 B → 连接 → 登录（B 需重新登录，Cookie 不共享）→ 打开同一 `projectId + ideas.md`：节点中**没有「已恢复未保存的编辑」**，正文为服务器版本 |
| 再回 A 打开同一文件 | 通过 | 退出登录 →「更换服务器」→ 地址改回 A → 连接 → 登录 → 打开：出现「已恢复未保存的编辑」，A 的草稿完好；截图 `restored-on-a.png` |
| 连接状态隔离 | 通过 | B 首次连接时状态为 `authenticated=false`（与 A 的登录态无关），需要重新输入账号密码；换服务器时编辑器标签清空、不继承 |
| 旧格式草稿迁移 | 通过（JVM） | `EditorSessionTest`：旧 key 草稿恢复到新 origin 并删除旧 key；迁移后另一台服务器读不到 |

## 未覆盖

- B 侧草稿的对称验证（在 B 上编辑产生草稿、回 A 不串）未单独跑一遍：机制与 A 侧相同（key 含 origin），由 JVM 的 `draft from another server is not restored` 覆盖。
- 同一 origin 下不同账号的隔离（服务端会话切换）未构造：草稿 key 只带 origin，不带账号；若同一服务器换账号登录，草稿仍按项目共享（与 Web 的 localStorage 行为一致）。
