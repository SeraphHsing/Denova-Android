# P3 书籍切换（当前项目切换）：真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建，serial `135babde`）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 对应：P3 链路起点「选择书籍」；矩阵参考 `BOK-001`（创建切换与信息，P4）的**切换子集**、`WRI-001`（从想法创建书籍进入写作，P3）的前置
- 实现：`core/network/BookShelf.kt`（`BookRecord`）、`DenovaClient.bookshelf()`（`GET /api/books`）、`feature/bookshelf/BookShelfViewModel.kt`、`AppDrawer` 顶部书籍区、`MainActivity` 接线（选书 → `currentProjectId` → 编辑器 / 项目目录）

## 数据源

`GET /api/books` → `{ books: BookRecord[], sort_mode }`；`BookRecord { project_id, name, path, author, cover_updated_at?, last_opened_at }`。书籍 == 工作区：`path` 是 workspace，`project_id` 用于项目作用域接口。真实服务器返回 5 本。

## 验证步骤与结果

1. 登录后加载书架：`BookShelf: loaded books=5`。
2. 左抽屉「当前书籍」区列出 5 本：Wuthering Waves / 我的异世界生活 / 霓光巡演-性感舞团 / 灵渊劫-问道 / 晏宫-十妃定九州。
3. 点击「灵渊劫-问道」：`BookShelf: selected name=灵渊劫-问道 project=project-efb2ed8ad64c839df4b72c3a69772350`；抽屉关闭。
4. 编辑器「项目 ID」输入框同步为 `project-efb2ed8ad64c839df4b72c3a69772350`。
5. 打开右抽屉：`ProjectDir: loaded dirs=1 rows=7`（按新项目加载）。
6. 点击 `ideas.md`：`Editor: opened project=project-efb2ed8ad64c839df4b72c3a69772350 path=ideas.md revision=sha256:2a803f10…`（与 `project-b47902…` 的 `459b244d…` 不同，证明 `project scope` 正确切换）。

## 补：服务端 workspace 切换（2026-10-24 修复缺口）

核对 Web 发现 `handleQuickWorkspaceSwitch` 是「先 `flushEditorDraft()` 再 `POST /api/workspace/switch`」，初版只更新客户端状态。已补：

- `DenovaClient.switchWorkspace(path)`（`POST /api/workspace/switch`）
- `EditorSession.flush()`（切换前保存未提交编辑并等待；冲突 / 失败返回 `false`，调用方中止切换）
- `BookShelfViewModel.switch(book)`（先服务端切换成功再更新客户端，失败不假装已切换）
- `MainActivity` 选书流程：flush → switchWorkspace → 更新 `currentProjectId`

验证（真机 + 真实服务器）：

| 时点 | `GET /api/workspace/current` |
|---|---|
| 切换前 | `project-b47902…` / `/data/.denova/projects/Wuthering Waves` |
| 选「灵渊劫-问道」后 | `project-efb2…` / `/data/.denova/projects/1` |

日志：`BookShelf: switched name=灵渊劫-问道 project=project-efb2… workspace=/data/.denova/projects/1`。

`flush` 由 `EditorSessionTest` 3 个单测覆盖（无编辑跳过 / 有编辑保存 / 冲突中止）；真机「编辑中切换」未单独构造。

## 结论

书籍切换 + `project scope` 切换 + 服务端 workspace 切换 + 编辑器 / 项目目录联动：**真机通过**。

## 未覆盖 / 缺口

- 书籍封面缩略图（`/api/books/cover`）、当前书章节数 / 字数统计（`bookDetail`）未显示。
- 「管理书籍」入口（`BOK-001` 的新建 / 编辑信息 / 封面、`BOK-002` 导入、`BOK-003` 导出排序移除）未实现。
- 切换后不自动打开上次文件：已核对 Web `refreshAll()` 执行 `setSelectedFile(null)`，基线本身不自动打开，**行为一致**。
- `BOK-001` 不回写（仅切换子集，缺新建 / 信息 / 封面）；`WRI-001` 的「从想法创建书籍」未实现。
