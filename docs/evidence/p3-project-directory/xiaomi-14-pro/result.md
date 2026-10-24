# P3 项目文件树（WRI-002）：真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（debug 构建，serial `135babde`）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 项目：`project-b47902c162fffb7983a95ab73cae37d0`
- 对应矩阵：`WRI-002`（写作编辑 / 项目文件树）
- 实现：`core/network/ProjectFileTree.kt`、`DenovaClient.resolveProjectFiles`、`core/project/ProjectFileTree.kt`、`feature/project/ProjectDirectoryViewModel.kt`、`feature/shell/RightDrawer.kt`

## 数据源核对

右抽屉「项目目录」按固定 Web 基线 `features/project-explorer` 使用 **`POST /api/projects/:id/files/resolve`**（`recursive: true` 一次铺开），**不是 `/book/tree`**（后者是书籍快照）。`RightDrawer` 旧注释「接 `/book/tree`」已为过时描述。

## 验证步骤与结果

1. 编辑器输入项目 ID → 打开右抽屉。
   - 日志：`ProjectDir: loaded dirs=1 rows=7`（根目录 1 页、7 个顶层条目）。
2. 文件树渲染：`assets / chapters / d03-writing / interactive / setting`（目录，`▸` 折叠）+ `CREATOR.md / ideas.md`（文件）。
3. 点击 `ideas.md`。
   - 日志：`Editor: opened project=project-b4790… path=ideas.md revision=sha256:459b244d…`
   - 右抽屉关闭并进入编辑器，正文加载。
4. 重新打开右抽屉：`ideas.md` 行以 `primaryContainer` 高亮（选中文件与编辑器当前文件对应）。
5. 刷新按钮：重新 `load(force=true)`（实现具备，测试项目数据小未单独计时）。

截图：`right-drawer-highlight.png`（含项目正文，按 AGENTS §5 只留本地、已加入 `.gitignore`，不入库）。

## 结论

- 加载、展开整行、选择高亮、刷新、点击打开、`project scope` 与路径正确：**真机通过**。
- **未覆盖**：大目录的懒加载分页（`children_state=partial` + `continuation` + 「加载更多」）。第一版为 `recursive: true` 一次铺开；测试项目无 `partial` 目录，无真机验证条件。因此 `WRI-002` 暂不回写为 `已完成`，分页补齐并验证后再回写。
- 后续缺口：文件新建/重命名/删除/移动（`/files/operations`）、git 状态标记（`/versions/status`）、目录展开状态持久化。
