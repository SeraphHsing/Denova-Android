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

## 代码审查后的修复与复测（同日）

审查发现一个会让上述隔离**在真实保存路径上失效**的缺陷：`FileSaveCoordinator` 自己拼
`"$projectId\0$path"`，没有使用 `EditorSession` 的新 key。后果是「A 上保存失败 → 协调器把草稿写进旧 key →
切到 B 打开同文件 → B 的 legacy 回退命中并迁移过去」，即最初的真机闭环只在「没有发生过失败保存」时成立。

修复：

- 新增 `core/persistence/DraftKey`（`of(origin, projectId, path)` / `legacy(projectId, path)`），
  `EditorSession` 与 `FileSaveCoordinator` 共用同一个构造；`EditorViewModel` 通过
  `FileSaveCoordinator(..., keyFor = { p, f -> DraftKey.of(origin, p, f) })` 注入。
- `EditorSession.open` 在新 key 命中时顺手清掉旧 key（避免上次迁移中断留下的旧 key 长期当「公共草稿」）。
- `EditorViewModel.bindClient` 先 `session.cancelInFlight()` 再换 session：旧 client 的防抖/在途 PUT 不再继续。
- `ServerKey.parse` 的 host 归一为小写（`MyHost` 与 `myhost` 不再各存一份 Cookie jar 与草稿作用域）。

复测（`pm clear` 后全新）：

| 步骤 | 结果 |
|---|---|
| A 打开 `ideas.md` → 断网 → 在正文末尾输入 `zz1` | 显示「错误 / 保存失败：Failed to connect to /192.168.2.102」，草稿由协调器落盘 |
| 退出登录 →「更换服务器」→ 连 B（代理 origin）→ 重新登录 | B 需要重新输入账号密码（Cookie 按 origin 隔离） |
| 在 B 打开同一 `projectId + ideas.md` | **未能验证**：返回「打开文件失败：项目不存在 / 项目文件或目录不存在」（B 会话下的当前书籍与 A 不同，切书后仍不一致），无法构造「B 侧打开同一文件」的同场景 |
| 切回 A → 重新登录 → 打开同一文件 | 「已恢复未保存的编辑」出现，A 的失败保存草稿在切走再回来后完好 |

## 未覆盖

- **B 侧打开同一文件的复测**（见上表第 3 行）：B（代理 origin）登录后无法打开同一 `projectId`——返回「项目不存在 / 项目文件或目录不存在」，切书后仍不一致。因此「修复后的跨服务器隔离」在真机上只拿到了 A 侧闭环（草稿完好、B 需要重新登录），B 侧「无草稿提示」的对照依赖 JVM 测试：
  - `draft from another server is not restored`
  - `autosave writes draft under the server scoped key`（保存路径必须写新 key，不允许写旧 key）
  - `migrated legacy draft does not leak to another server`（B 不得留下迁移产物）
  - `scoped draft supersedes stale legacy key`
- B 侧草稿的对称验证（在 B 上编辑产生草稿、回 A 不串）同样未跑。
- 同一 origin 下不同账号的隔离（服务端会话切换）未构造：草稿 key 只带 origin，不带账号。
- 同一 origin 下不同账号的隔离（服务端会话切换）未构造：草稿 key 只带 origin，不带账号；若同一服务器换账号登录，草稿仍按项目共享（与 Web 的 localStorage 行为一致）。
