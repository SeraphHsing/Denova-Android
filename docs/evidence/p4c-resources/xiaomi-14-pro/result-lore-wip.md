# P4-C · 资料库（LOR-001…004）验收进度（进行中）

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 构建：JVM 506 用例 / 0 失败 + `lintDebug` + `assembleDebug` 通过

> 本文件只记录**已经真机观察到的现象**。LOR-001…004 在矩阵里仍是 `未开始`，
> 因为每行的验收步骤尚未全部走完（见末节）。

## 1. 已实现

| 能力 | 实现位置 |
|---|---|
| 分类（增删改移）与条目 CRUD | `feature/lore/LoreScreen.kt`、`LoreViewModel.kt`、`core/network/LoreCatalog.kt`、`DenovaClient` 16 个方法 |
| 材料（上传 / 移除 / 设为封面）与配图、语音生成 | 同上（multipart + `MaterialMutation`） |
| 索引（读 / 预览 / 保存）与类型分类（预览 / 按勾选应用） | 同上（`IndexGuide` 快照 + `ClassificationPreview/Apply`） |
| 草稿跨页存活 | `LoreViewModel`（Activity 作用域 + `SavedStateHandle` 序列化草稿） |
| 纯逻辑 | `core/lore/LoreDraft.kt`（3 个 JVM 用例：标签解析、表单↔条目往返、分类→类型映射） |

## 2. 真机已确认

| 步骤 | 结果 |
|---|---|
| 列表读取 | `Lore: loaded project=project-4e55… items=103 categories=6`，页面渲染 6 个分类与 103 条条目（`shots/42-lore-list.png`） |
| 新建条目 | 填名称 `p4c-lore-test` → 保存 → `Lore: saved item=p4c-lore-test name=p4c-lore-test`，条目数 103→104，服务端自动补「简要描述」与 `important`（`shots/55-saved.png`） |
| 搜索 | 输入 `p4c-lore` → 列表只剩该条目（`shots/61-search.png`） |
| 材料上传 | 选 `cover-test.png` → `Lore: material uploaded item=p4c-lore-test name=cover-test.png`，界面「已上传材料」，材料计数 0→2（图片同时成为封面） |
| 材料移除 | 行内「删除」→「已移除材料」，材料 2→1（`shots/63-material-ok.png`、`64-material-removed.png`） |
| 草稿跨页恢复 | 表单填到一半返回编辑器页，再进资料库 → 表单与已填内容仍在（`shots/57-draft-restored.png`），对应 LOR-004 的核心行为 |

### 本轮修掉的三个缺陷

1. **材料/生成接口解码错误**：服务端这四个端点返回 `Item` 本体（不是 `{project_id,item}` 包装），
   我们按包装解码导致「材料操作失败」且没有可读原因。改为直接解码 `Item` 后上传/移除均成功。
2. **表单底部被键盘永久遮挡**：页面没有 `imePadding`，键盘弹出后「保存 / 上传材料」既看不见也滚不到
   （真实用户同样点不到）。补 `imePadding`，并从左边距拖动可把保存滚进可视区。
3. **草稿随页面销毁**：`LoreViewModel` 原先绑定导航目的地作用域，离开页面即清空；改为 Activity 作用域
   并把草稿序列化进 `SavedStateHandle`，Activity 重建后也能恢复。

## 3. 尚未验收（因此矩阵未回写）

- LOR-001：分类的新建 / 重命名 / 删除（条目会迁往目标分类）、条目编辑、启用开关、删除条目。
- LOR-002：配图生成与语音生成（需要可用的图像 / 语音 Agent，未构造成功；按钮与错误呈现已实现）。
- LOR-003：索引预览（token 预算）、保存索引（revision 冲突提示）、类型分类预览与按勾选应用。
- LOR-004：除「返回后草稿仍在」外，还需验证跨进程（进程被杀后）行为与「不重复提交」。

这些步骤没有跑完，因此 LOR-001…004 保持 `未开始`。
