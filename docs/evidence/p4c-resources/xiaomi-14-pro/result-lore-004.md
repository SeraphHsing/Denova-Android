# P4-C · 资料库跨页草稿（LOR-004）真机证据

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 项目：`project-9a1ce5282872a3784807476ba907fedb`（`p4c-lore-apply`，取证后已移除书籍记录）
- 构建：JVM 73 套件 / 511 用例 / 0 失败 + `lintDebug` + `assembleDebug` 通过

## 1. 离开页面再返回

| 步骤 | 实测结果 |
|---|---|
| 形成草稿 | 打开条目 `p4c-class-alice`，把名称改成 `p4c-class-alice-draft`（不保存）；标题行同步显示新名 |
| 离开 | 左抽屉 →「写作 Agent」，停留数秒 |
| 返回 | 左抽屉 →「资料库」→ **同一份表单仍在**（名称仍是 `p4c-class-alice-draft`），选中条目未丢 |
| 不重复提交 | 离开 / 返回期间服务端条目名仍是 `p4c-class-alice`，`logcat -s Lore` 只有 `loaded project=…`，没有任何 `saved item`（切换页面不会触发保存或新会话） |

## 2. 进程被杀 / 覆盖安装后恢复

`LoreDraftStore` 按 `服务器 origin + 项目 ID` 把草稿写进 DataStore（400ms 合并写），页面加载后若内存里没有草稿则从盘上恢复。

| 场景 | 实测结果 |
|---|---|
| `force-stop` 后重启 | 连接 → 资料库 → `Lore: draft restored id=p4c-class-alice isNew=false`，界面直接回到同一份未保存表单 |
| `adb install -r` 覆盖安装后 | 同上：`draft restored id=p4c-class-alice`，草稿跨包可读（本次共观察到 3 次恢复） |
| 切换作品 | 打开临时书 `p4c-audio-test` 的资料库时不出现上一本书的草稿；草稿桶按项目隔离 |

## 3. 「Agent/外部编辑后返回」不覆盖本机草稿

在草稿打开的状态下，用第二客户端（模拟 Agent 侧写入）修改同一条目的名称与正文，然后返回资料库：

- 客户端仍显示本机草稿（`p4c-class-alice-draft`），没有被服务端内容覆盖；
- 服务端此时是外部写入的值（`p4c-class-alice`），即本地与服务端**各自保留**，由用户决定是否保存；
- 之后点一次「保存」：`logcat` 清空后仅出现 **1 条** `saved item=p4c-class-alice name=p4c-class-alice-draft`，服务端读回新名 —— 一次点击只发一个写请求，不重复提交。

（该轮保存只用于验证提交次数，随后用第二客户端把名称改回 `p4c-class-alice`，测试书已移除。）

## 4. 同轮修复

1. **草稿只在内存**：原实现把草稿放在 Activity 作用域的 ViewModel + `SavedStateHandle`，进程被杀后只剩系统偶然保存的实例状态；新增 `LoreDraftStore`（DataStore）按 `origin|project` 落盘，页面加载时恢复。
2. **媒体回包抹掉草稿**：上传 / 生成完成后用服务端条目重建表单，把正在输入的正文覆盖；改为只刷新列表条目（`LoreDraft.matches` 判定脏，只有确实没改动时才重建表单）。
3. **列表点击覆盖未保存草稿**：同一行重复点击会重置表单；改为「同一条目的草稿存在时不再重建」。
4. **切作品带草稿**：切换项目时清掉内存草稿与 `SavedStateHandle`，旧项目草稿留在盘上供回去时恢复。

## 5. 缺口

- 草稿只保留每项目一份（最后编辑的那条），不做多条目草稿队列；基线同样是单表单。
- 「草稿 → 保存」没有冲突合并（服务端用 `base_revision` 判 409 并提示失败），因为条目更新接口只做 revision 校验、不做三方合并。
