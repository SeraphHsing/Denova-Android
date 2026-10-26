# WRI-001 首次开写：从想法创建书籍进入写作

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 对应矩阵：`WRI-001`（写作编辑 / 首次开写；steps「从想法创建书籍进入写作」，expected_ui「引导和选中章节对应」，expected_server_effect「书籍项目关联正确」）
- 契约来源：后端 Go 源码 `internal/api/handlers/handler_books.go:35-74`（`POST /api/books/create`，只接受 `title/author/description`，目录已存在 409，**创建会切换服务端当前 workspace**）、`web/src/i18n/locales/zh-CN/onboarding.ts`（三步核心 + 提示词文案）
- 实现：
  - `core/onboarding/OnboardingLogic.kt`（新增：三步步进、进度、提示词、展示门槛；对齐 Web `OnboardingGuide.tsx` 与 `writingAgent.initPrompt` / `onboarding.firstPrompt`）
  - `core/network/BookShelf.kt` + `DenovaClient.createBook`（`POST /api/books/create` 契约）
  - `feature/bookshelf/BookShelfViewModel.kt`（`createBook`：成功后把当前书籍指向新 `project_id`，并记 `justCreatedName`）
  - `feature/onboarding/CreateBookDialog.kt`（新增：三字段 +「从想法开始」/「只建书」）
  - `feature/shell/AppShell.kt`（抽屉引导卡 + 常驻「＋ 新建书籍」入口）
  - `MainActivity` + `AgentScreen`（`agent?…&prompt=` 预置正文，`start()` 之后再注入，避免被状态重置吞掉）
- 单测：`OnboardingLogicTest`（6 例，新增）

## 1. 真机流程

### 1.1 从「＋ 新建书籍」到建书

抽屉 →「＋ 新建书籍」→ 填书名 `writestbook` →「从想法开始」：

```text
I BookShelf: created project=project-c15785a0e466a3915ed01d7ee52a89a2 workspace=/data/.denova/projects/writestbook
I Agent   : start session=default project=project-c157
```

- 服务端返回 `project_id` 与绝对路径 `workspace`，客户端把当前书籍切到它（**书籍项目关联正确**：Agent 页的 `project` 作用域就是这个新 id，不是上一本书）；
- 页面自动进入写作 Agent，输入框已预填启动提示词，`状态：空闲` —— **只填不发**，与基线一致（Web 明确不自动消耗模型调用）：

```text
EditText[39,2982][1135,3187] = 请和我一起启动一本新书：先读取 ideas.md 和 CREATOR.md，通过对话梳理灵感、题材、冲突、世界观、人设和
```

### 1.2 引导步进与「填入示例」

第二次建书（`writestbook2`，走「只建书」）后回到抽屉，引导卡出现第 3 步：

```text
新手引导 3 / 3
用创作 Agent 写第一章开头
[填入示例]   [跳过引导]
```

点「填入示例」→ 跳转 Agent 并预填该书的第一章请求（书名对应）：

```text
EditText = 请基于《writestbook2》创建第一章 Markdown 文件，并写一个可继续扩展的开头。…
状态：空闲
```

即「引导 → 选中书籍 → 第一章请求」三者对应。截图见 [agent-prefill.png](agent-prefill.png)。

### 1.3 章节与新书对应

在 `writestbook` 的 `chapters/` 下写入两章后，右抽屉「章节」显示：

```text
I Chapters: summary loaded project=project-c15785a0e466a3915ed01d7ee52a89a2 chapters=2 words=28
2 章 · 共 28 字 / 第001章 / 第002章
```

章节列表来自 `GET /api/projects/:id/book/summary`，指向新建的书而不是上一本（承接 §1.1 的 project id）。章节状态切换见 `docs/evidence/wri-015/xiaomi-14-pro/result.md`。

## 2. 测试数据清理

`writestbook` / `writestbook2` 为本次验证创建，测完用 `POST /api/books/remove` 移除：

```text
/data/.denova/projects/writestbook2 200 {"message":"已移除书籍记录"}
/data/.denova/projects/writestbook  200 {"message":"已移除书籍记录"}
```

## 3. 未覆盖 / 已知边界

- **MODEL 步未接探测**：`GET /api/settings` 的 `hasUsableLanguageModel` 尚未解析，Android 侧当前把「模型已就绪」视为真，因此只有「没有书 → BOOK 步」与「刚建书 → AGENT 步」会展示，MODEL 步不会出现（`OnboardingLogic.firstStep` 已支持该分支并有单测）。
- **引导状态不持久化**：Web 写 `localStorage nova:onboarding:v1`，Android 目前只在内存里（`justCreatedName` + `books` 数量），重启后不恢复也不记录「已完成」。
- Web 的 11 步导览（设置/书籍/写作/Agent/游戏/资料库/…）未实现。
- 未做：封面上传/AI 生成、书籍管理（重命名/排序/归档）、`/books/import-*` 导入系列。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
