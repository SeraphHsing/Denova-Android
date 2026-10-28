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

- 拖拽移动分类（Web 支持拖拽排序；手机端用管理对话框，移动语义未做）。
- 条目「移动分类」没有独立入口：改条目类型即等价迁移，未单独验证。
