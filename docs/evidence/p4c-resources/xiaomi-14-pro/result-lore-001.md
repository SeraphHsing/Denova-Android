# P4-C · 资料库分类与条目管理（LOR-001）真机证据

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 项目：`project-4e550712ab2ded2e73980e31ad102d0d`（我的异世界生活）

## 步骤与结果

| 步骤 | 实测结果 |
|---|---|
| 列表与分类 | `Lore: loaded project=project-4e55… items=103 categories=6`，6 个分类标签 + 103 条条目正常渲染 |
| 新建分类 | 分类菜单 →「新建分类」→ 输入 `p4c-cat` → `Lore: category create id=… total=7` |
| 重命名分类 | 「管理分类」→ 该行「重命名」→ `p4c-cat2` → `Lore: category rename id=6733f47b-… total=7` |
| 删除分类 | 「管理分类」→「删除」→ 确认框写明「该分类下的条目会移动到 location」→ `Lore: category delete id=… total=6`（服务端拒绝迁往 `character`，客户端已按规则选非 character 目标） |
| 新建条目 | 「新建条目」→ 名称 `p4c-lore-test` → 保存 → `Lore: saved item=p4c-lore-test name=p4c-lore-test`，条目数 103→104 |
| 编辑条目 | 搜索 `p4c-lore` → 打开 → 正文改为 `lore-v3` → 保存 → `Lore: saved item=p4c-lore-test name=p4c-lore-test`（首次曾因缺字段 400，见下） |
| 启用开关 | 表单内关闭「启用」→ 写入成功，重新进入仍为关闭 |
| 删除条目 | 表单「删除」→ 二次确认「删除"p4c-lore-test"？正文与媒体会一并移除。」→ `Lore: deleted item=p4c-lore-test` |
| 作用域 | 全部读写都带 `project_id`（`/api/projects/:id/book/lore/...`），未出现跨项目条目 |

## 同轮修复

1. **条目更新必须带全部 9 个字段**：服务端 `bindCompleteLoreItemInput` 缺任一字段即 400
   `api.projectBook.loreFieldsRequired`；kotlinx 默认省略「等于默认值」的字段（空数组/空串），
   所以「只改正文」以前必然失败。改用 `encodeDefaults = true` + `explicitNulls = false` 的 `loreJson`，
   `index_memberships` 仍保持「不传=不改」。
2. **删除分类的目标不能是 `character`**：服务端 `MutateCategory` 直接拒绝；客户端改为优先选非 character 分类，
   对话框文案随实际目标变化。
3. **分类标签行点不到 ⋮**：标签放不下时 ⋮ 滚出屏幕，新增「管理分类」对话框统一改名 / 删除。
4. **索引面板空快照**：顶栏「索引」只切视图、没读服务端索引，导致预览 / 保存静默失效；
   改为进入面板即 `loadIndex()`。
5. 分类 create 不再由客户端造 id（服务端分配 uuid）。

## 未覆盖

- ~~拖拽移动分类~~ / ~~条目移动入口~~：见下节第二轮，已补齐并真机通过。

## 第二轮真机（分类移动 / 角色锁定 / 自定义分类 / 条目移动）

- 日期：2026-10-28 晚
- 项目：`project-9a1ce5282872a3784807476ba907fedb`（`p4c-lore-apply`；取证完成后已用 `/api/books/remove` 移除书籍记录）
- 构建：JVM 73 套件 / 511 用例 / 0 失败 + `lintDebug` + `assembleDebug` 通过

| 步骤 | 实测结果 |
|---|---|
| 新建分类 | 菜单「新建分类」→ `p4c-cat` → `Lore: category create id= total=6`；服务端分配 uuid `f6663533-…` |
| 分类上移 | 管理分类 → 该行 `↑` → `Lore: category move id=f6663533-… total=6`；`GET categories` 顺序由 `… item, world, p4c-cat` 变为 `… item, p4c-cat, world` |
| 角色分类锁定 | 管理分类里角色行显示「角色分类不可重命名或删除」；点「重命名」「删除」均不弹对话框、不发请求（服务端同样拒绝） |
| 自定义分类建条目 | 选中 `p4c-cat` → 新建条目（分类显示 `p4c-cat`）→ 名称 `p4c-item-a`、重要度 `minor` → 保存 `Lore: saved item=p4c-item-a` |
| 读回类型/重要度 | `GET items` → `type=f6663533-d663-4452-84c5-a6143739d919`（= 分类 id）、`importance=minor` |
| 条目移动（显式入口） | 列表长按条目 →「移动到分类」→ 地点 → `Lore: moved item=p4c-item-a to=location`，服务端 `type=location`、`type_source=manual` |
| 改分类下拉 | 表单「分类」改回 `p4c-cat` → 保存 → 服务端 `type` 回到分类 uuid |
| 删除分类并选迁移目标 | 管理分类 → 删除 `p4c-cat` → 目标选择「世界设定」→ 确认文案随选择变化 → `Lore: category delete id=… total=5`；服务端读回条目 `type=world`，`character` 分类保留 |

### 本轮修掉的三处契约/枚举缺陷

1. **自定义分类的条目类型**：`LoreDraft.typeForCategory` 原把非内置分类映射成 `other`，而服务端要求条目 `type` ∈ 分类表（`validateCollection`），在自定义分类里新建条目必然保存失败。改为直接用分类 id。
2. **重要度取值**：客户端原用 `high/medium/low`，服务端 `normalizeLoreImportance` 会把它们统一改成 `important`；改为基线的 `major/important/minor`，真机读回 `minor` 不再被改写。
3. **角色分类不可改名/删除**：删除目标选择器与重命名入口都按服务端规则禁止 `character`（此前自动取「第一个非 character 分类」，只是碰巧避开）。

### 仍不在本行范围

- 分类拖拽排序：基线 `LoreCategoryManager` 本身就是 `↑ / ↓` 按钮（不是拖拽），已按基线实现。
