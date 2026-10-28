# P4-C · 资料库索引与类型分类（LOR-003）真机证据

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 项目：`project-9a1ce5282872a3784807476ba907fedb`（`p4c-lore-apply`，取证后已移除书籍记录）
- 构建：JVM 73 套件 / 511 用例 / 0 失败 + `lintDebug` + `assembleDebug` 通过

## 1. 索引：预览 → 保存 → 冲突

| 步骤 | 实测结果 |
|---|---|
| 打开索引 | 顶栏「索引」→ `Lore: index loaded groups=0 revision=sha256:a7b1f…`，面板显示 revision 前 12 位 |
| 预览（不写盘） | 「预览索引」→ `Lore: index preview tokens=121 over=false`，面板出现「约 121 tokens」与渲染后的索引 Markdown；随后 `GET .../book/lore/index` 的 `revision` 仍为 `sha256:a7b1f…`（预览未写盘） |
| 保存 | 前言改为 `p4c-intro-v1` →「保存索引」→ `Lore: index saved revision=sha256:4c249…`；服务端读回 `guide.intro_markdown=p4c-intro-v1` |
| 制造冲突 | 用第二客户端（同一账号、独立会话）以同一 `base_revision` 改写前言为 `p4c-intro-external` → 200，服务端 revision 变 `sha256:9ce97…` |
| 过期保存 | App 仍持 `4c249` 时再点「保存索引」→ `W/Lore: index save conflicted; reloading revision` → 界面提示「索引已在其他位置更新，已重新加载最新版本；请重新预览后再保存。」，revision 更新为 `sha256:9ce97…`，**正在编辑的前言 `p4c-intro-v1` 未被服务端内容覆盖** |
| 重新预览 + 保存 | 再预览（`tokens=125`）后保存 → 成功，服务端 `intro_markdown` 回到 `p4c-intro-v1`（revision 为内容哈希，写回同一内容得回 `4c249`） |

索引面板在编辑条目时也能打开（面板优先于表单），关掉面板回到同一份草稿。

## 2. 类型分类：预览 → 只应用勾选 → 冲突

（为覆盖「只提交确认范围」，用第二客户端预置 3 个条目：`p4c-class-alice` 类型 faction、`p4c-class-sword` 类型 location、`p4c-item-a` 类型 world。）

| 步骤 | 实测结果 |
|---|---|
| 预览 | 菜单「类型分类」→ `Lore: classification preview mode=semantic items=3`；对话框逐条显示 `组织 → 角色`、`地点 → 物品`、`世界设定 → 物品`，默认全部勾选 |
| 只应用所选 | 取消第一、第三条勾选（按钮变「应用所选（1）」）→ 应用 → `Lore: classification applied updated=1` |
| 只提交确认范围 | 服务端读回：`p4c-class-sword` 变为 `item`；`p4c-class-alice` 仍 `faction`、`p4c-item-a` 仍 `world`（未勾选的条目未被改动） |
| 冲突 | 再次预览后，用第二客户端修改 `p4c-class-alice` 正文（revision 变化）→ App 用旧 revision 应用 → `W/Lore: classification apply conflicted; dropping preview` → 提示「条目已在其他位置更新，请重新预览分类建议。」，对话框关闭、勾选清空（不重放旧建议） |
| 失败可见 | 分类预览走模型，超时/服务端报错时原样显示服务端原因（此前只显示「分类失败」且无原因，本轮改为展示 `DenovaApiException.message`） |

## 3. 同轮修复

1. **索引面板打不开**：编辑条目时 `showIndex` 被「正在编辑」分支遮蔽，点「索引」没有任何反应；改为索引面板优先于表单（草稿保留在 ViewModel）。
2. **索引保存 409 无提示**：原实现只显示「索引操作失败」并留在旧 revision，用户会反复失败；改为识别 409，提示冲突并**只刷新 revision、保留用户正在编辑的前言**。
3. **分类应用 409 无提示**：原实现提示「分类失败」且保留过期预览；改为提示「请重新预览」并丢弃过期建议。
4. **慢接口超时**：索引预览 / 分类预览 / 分类应用都要过模型，统一走 180s 读超时的 `slowHttpClient`。

## 4. 缺口

- 索引分组编辑（增删组、组内条目排序、`item_order` / `group_order`）：客户端目前只读分组、可改前言并整体保存；分组级编辑属基线 `LoreIndex*` 系列组件，未在 LOR-003 验收步骤内。
- 分类建议的「置信度筛选 / 来源过滤」未做（基线对话框按置信度分组）。
