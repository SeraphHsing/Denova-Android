# VER-002 版本 diff 与恢复 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug；服务器 `http://192.168.2.101:18098`
- 项目：`project-efb2ed8ad64c839df4b72c3a69772350`（灵渊劫-问道）
- 时间：2026-10-26 00:03–00:09
- 只读探针：`GET /versions/:id/diff?comparison=workspace`、`GET /versions/status`

## 1. 打开版本 diff

点列表中的 `自动版本：2026-10-25 02:34`：

```
Versions: diff loaded version=65760c1c4f54f6eaf6f238d7e61726792113369d changes=5 files=5 complete=true
```

面板顶部 `已选 5/5 个文件`，下面是 `changes` 全量目录：

- `added · assets/redpanda.png`
- `added · assets/writing/3d0a59ab-….jpeg`
- `modified · g3d-a.md`
- `modified · g3d-b.md`
- `modified · g3d-review.md`

## 2. 只恢复一个路径

点「全选」清空 → 勾 `g3d-a.md` → `已选 1/5` → 点「预览恢复计划」：

```
Versions: restore plan version=65760c1c4f54f6eaf6f238d7e61726792113369d scope=paths paths=1 backup=false dirty=false warnings=1
```

面板显示（`restore-plan.png`）：

- `范围：1 个文件`
- 服务端 warning：`文件恢复会作为未保存变更应用，不会移动当前版本`
- 未显示「恢复前会先创建备份版本」，因为 `will_create_backup=false`（工作区干净，无需备份）

点「确认恢复」：

```
Versions: restored version=65760c1c4f54f6eaf6f238d7e61726792113369d scope=paths paths=1 backup= message=已恢复所选文件
```

服务端读回：

```
status clean=False changes=[('g3d-a.md', 'modified')]
```

**只有目标路径发生变化**——其余 4 个路径没有被顺手改掉，说明 `paths` 真被服务端当作恢复范围。

## 3. 全选 = 整本范围

回到列表，打开最新版本 `2026-10-25 03:07`（此时 diff 只剩 `g3d-a.md` 一处，1/1 全选）→ 预览：

- 面板显示 `范围：整本书`（`paths` 为空 = `scope=workspace`，与后端约定一致）
- 确认恢复：`restored version=7ebdc303815f… scope=workspace paths=1 message=已恢复到所选版本`
- 服务端读回 `status clean=True changes=[]`，工作区回到恢复前的原始状态

即本轮的两处写操作都可逆，且第二次恢复没有引入额外改动。

## 4. 范围与结果对应（矩阵的「文件范围和结果对应」）

| 勾选 | 请求 paths | 服务端 scope | 实际变化 |
|---|---|---|---|
| 5 个中只勾 `g3d-a.md` | `["g3d-a.md"]` | `paths` | 只有 `g3d-a.md` modified |
| 1/1 全选 | `[]` | `workspace` | 工作区整体回到目标版本，`changes=[]` |

## 5. 环境缺陷（阻塞真实编辑构造）

本部署下编辑器保存返回 501（HTML `Unsupported method ('PUT')`），无法用「编辑并保存」制造差异，因此本轮用「恢复旧版本」构造了这处改动。已经在版本 diff 里得到验证，不影响 VER-002 结论；编辑器保存的 501 属于部署/入口问题，另行登记。

## 6. 验证

- JVM：`VersionRestoreTest` 5 例（创建结果、diff 变更与文件正文、恢复计划、恢复结果、全选→整本范围判定）。
- 主检查：`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 全绿。
- 真机：§1–§3 在本机完成；仪器测试未执行。

## 7. 残留

- 差异正文（`files[].original/modified`）本轮只用于判断范围，界面没有做行级 diff 展示；「查看具体改了什么」仍是缺口。
- `restore-plan` 计算较慢（本次约 35s，5 个路径），界面只有「正在计算…」，没有进度或取消。
