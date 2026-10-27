# P4-B 工作台 · 第三切片真机证据（WB-004 工作台宿主形态）

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 用例：`:app:testDebugUnitTest` 69 套件 / **495 用例** 0 失败（新增 2 条文件页签用例）；`lintDebug`、`assembleDebug` 通过

## 1. 本轮实现

| 能力 | 实现位置 |
|---|---|
| 文件页签（每项目唯一，记住选中文件） | `core/workbench/WorkbenchTabs.kt`（`Files(selectedPath)` + `withSelectedPath`）、`WorkbenchViewModel.openFiles/selectFile` |
| 页签内容宿主：文件选择器 + 内嵌编辑器 | `feature/workbench/WorkbenchScreen.kt`（`WorkbenchFilesHost`） |
| 编辑器内嵌模式（隐藏重复顶栏 / 文件页签条 / 项目与路径输入行） | `feature/editor/EditorScreen.kt`（`inline`） |
| 项目作用域隔离（不恢复「上次项目」的标签桶） | `feature/editor/EditorViewModel.kt`（`bindClient(restoreTabs = false)`） |
| ＋菜单「项目文件」入口 | `WorkbenchScreen.TabStrip` |

### 契约依据

对齐固定基线 `web/src/features/agent-chat/types.ts` 的 `AgentChatFilesTab`：
**一个项目一个文件工作区页签**，`selectedPath` 随页签保留，编辑器与写作页共用同一套打开 / 自动保存 / 冲突合并逻辑。

手机上一屏放不下「树 + 编辑器」两栏，所以树做成可折叠的文件选择器（默认在未选文件时展开），
选中后收起把高度让给编辑器；文件选择器直接复用 `/files/resolve` 的目录树与 `ProjectDirectoryViewModel`。

## 2. 真机验收

| 步骤 | 实测结果 |
|---|---|
| ＋ → 项目文件 | `Workbench: open files tab=files:project-4e55… path=-`，页签出现、文件选择器展开（`shots/116-files-picker.png`） |
| 未选文件时的空态 | 标题「未选择文件」，正文提示「用上方「选择文件」打开项目里的文件」，不再出现别的项目的正文（`shots/118-files-empty.png`） |
| 选择 `ideas.md` | 目录树 4 行（`interactive` / `setting` / `CREATOR.md` / `ideas.md`），点文件后树收起、编辑器加载正文，页签标题变为 `ideas.md`（`shots/119-file-opened.png`） |
| 编辑触发自动保存 | 插入测试文本 → `保存中…` → 日志 `Editor: saved path=ideas.md revision=sha256:722bd4fd…`、`editor-save kind=saved ms=466`，界面「已保存」（`shots/120-edited.png`、`121-saved.png`） |
| 撤销恢复并再保存 | 删除测试文本 + 保存 → `revision=sha256:10e9c1e2…`，正文回到原文（`shots/122-reverted.png`、`123-undo.png`） |
| 富文本 / 预览 | 预览把 Markdown 渲染为标题、段落、有序/无序列表，流程与其他模式一致（`shots/127-preview.png`） |
| 切走再回来 | 返回编辑器页再回工作台，文件页签仍在、`ideas.md` 仍打开且正文保留（`shots/125-back-to-workbench.png`） |

## 3. 同轮修复的真机缺陷

**跨项目串文件**：新建的文件页签复用了会「恢复上次项目标签桶」的 `EditorViewModel`，
首次进入时屏幕上是上一个项目（`project-efb2ed8ad6…` 的 `wri013.md`）的正文，
若继续编辑并按自动保存就会写回那个项目。改为文件页签 `bindClient(restoreTabs = false)`，
只按本页签的项目与 `selectedPath` 显式打开文件；未选文件时保持空态。

## 4. 未覆盖 / 缺口

- 工作台右抽屉仍只有「项目 · 会话」树，没有文件树：从抽屉点文件直接开页签的入口（Web 侧栏样式）未做，
  目前文件入口在「＋ → 项目文件」里。
- 页签跨进程恢复未做：进程被杀后文件页签与其 `selectedPath` 一起消失（Web 持久化 `tab-state`）。
- 关闭文件页签不清理该页签的 `EditorViewModel`（Activity 级 store 保留到进程结束），长期使用会有内存累积。
- 审阅页签（WB-005）、历史搜索导出（WB-006）未开始。
- 仪器测试未执行（沿用 WRI-016 登记的环境阻塞）。
