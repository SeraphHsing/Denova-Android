# P4-C · 书籍管理（BOK-001 / BOK-002 / BOK-003）真机证据

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 用例：`:app:testDebugUnitTest` **503 用例** 0 失败（新增 3 条 `BookOrder` 排序用例）；`lintDebug`、`assembleDebug` 通过

## 1. 实现

| 能力 | 实现位置 |
|---|---|
| 书籍管理页（当前书籍 + 书架 + 三个入口 + 排序菜单） | `feature/books/BooksScreen.kt` |
| 新建 / 切换 / 信息 / 移除 / 排序 / 导入 / 导出 / 封面状态机 | `feature/books/BooksViewModel.kt` |
| 书籍契约（info / remove / reorder / sort-mode / cover / 导入 / 导出） | `core/network/BookCatalog.kt`、`DenovaClient`（新增 16 个方法） |
| 手动排序纯逻辑（上移 / 下移 → 完整路径表） | `core/books/BookOrder.kt`（3 个 JVM 用例） |
| 通用下载落盘（会话导出与书籍导出共用） | `core/export/DownloadsSaver.kt` |

文案逐条取自基线 `web/src/i18n/locales/zh-CN/home.ts`（标题「书籍管理」、副标题「n 本书籍」、
「当前书籍」「书架」「新建书籍」「导入现有小说」「导入酒馆角色卡」「从书架移除」「导出 TXT」
「《x》会从书架移除，但磁盘目录和文件会保留。」等）。

## 2. BOK-001 创建 / 切换 / 信息 / 封面

| 步骤 | 实测结果 |
|---|---|
| 新建书籍 | 对话框（书名必填 / 作者 / 简介）→ 日志 `Books: created project=project-58a2… workspace=/data/.denova/projects/p4c-books-test`，列表 5→6 本，当前书籍切到新书并提示「已创建《p4c-books-test》」 |
| 编辑信息 | 打开对话框时从服务端读 `GET /api/books/info` 预填（书名「我的异世界生活」/作者 `root`），修改后 `PUT /api/books/info` 成功、列表重读一致 |
| 上传封面 | SAF 选图片 → `POST /api/books/cover/upload` → 提示「封面已更新」；重进页面 `covers=4`，当前书籍缩略图显示上传的 TEST 图，其他书籍显示各自服务端封面 |
| 切换书籍 | 目标行「切换」→ `POST /api/workspace/switch` → 提示「已切换到《我的异世界生活》」，当前书籍区与「当前」徽标同步（`shots/11-switched.png`） |

**同轮修复**：封面一开始完全不显示。日志是
`cover load failed … Unable to create a fetcher that supports: http://…` —— 我们把 okhttp `HttpUrl`
直接交给了 Coil，Coil 3 没有该类型的 fetcher；改成 `toString()` 后正常渲染（`shots/08-covers-ok.png`）。

## 3. BOK-002 小说与角色卡导入

| 步骤 | 实测结果 |
|---|---|
| 预览 | SAF 选 `novel-test.txt` → `POST /api/books/import-novel/preview` → 对话框显示「解析结果：5 章 · 63 字」，书名预填 `novel-test`，**此时服务端没有写入任何书籍** |
| 确认导入 | 点「确定」→ `POST /api/books/import-novel` → 日志 `novel imported workspace=/data/.denova/projects/novel-test chapters=5 chars=63`，列表 6→7 本，当前书籍切到 `novel-test`，提示「已导入 5 章」 |
| 读回 | 重读列表见新书；随后导出该书的 TXT 得到 97 字节文件，说明 5 个章节都已落盘且可导出 |

角色卡入口（`/api/imports/character-card/preview` → `/api/books/import-character-card`）已实现并有预览 →
确认 → 读回链路，但**真机未验证**：测试机文件选择器在 `image/* + application/json` 过滤下只展示图片视图，
我们手写的 JSON 卡（chara_card_v2）在「最近」里不可见，没有可用于导入的 PNG 卡资产。

## 4. BOK-003 导出 / 排序 / 移除

| 步骤 | 实测结果 |
|---|---|
| 导出 TXT | 对 `novel-test` 导出 → `Books: exported … bytes=97 name=novel-test.txt` + `Export: saved name=novel-test.txt scope=downloads`，`adb ls /sdcard/Download/Denova` 可见。对无章节的《我的异世界生活》导出时如实显示服务端理由「没有可导出的非空章节」（不假装成功） |
| 手动排序 | `manual` 模式下把《曼宫-十妃定九州》上移 → 本地立即换位 + `POST /api/books/reorder`（完整路径表），`刷新` 重读服务端后顺序保持（`shots/16-reordered.png`）；随后下移复原 |
| 切换排序模式 | 顶栏 ⇅ 菜单在「手动排序 / 最近打开」间切换，调用 `POST /api/books/sort-mode` 并重读列表 |
| 移除书籍 | 二次确认「《novel-test》会从书架移除，但磁盘目录和文件会保留。」→ `POST /api/books/remove` → 日志 `removed name=novel-test newWorkspace=…/我的异世界生活`，列表 7→6；移除当前书籍时服务端会切换工作区，界面据此更新当前书籍 |

验收后把两本测试书（`novel-test`、`p4c-books-test`）都从书架移除，书籍顺序已恢复原状，
测试机的 `Download/Denova/novel-test.txt` 只留在设备本地。

## 5. 未覆盖

- 角色卡导入真机步骤（原因见 §3）。
- 封面「生成」（`/api/books/cover/generate`，需要可用的图像 Agent 与预设）未在真机调用；入口按基线保留。
- 拖拽排序在手机端改为菜单「上移 / 下移」（同一 `reorder` 契约），未实现长按拖拽手势。
- 仪器测试未执行（沿用 WRI-016 登记的环境阻塞）。
