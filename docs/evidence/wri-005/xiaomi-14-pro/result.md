# WRI-005 Markdown 编辑：语料格式与序列化对应

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 文件：`project-efb2ed8ad64c839df4b72c3a69772350` / `wri013.md`（5 段夹具，测完已复位）
- 对应矩阵：`WRI-005`（写作编辑 / Markdown编辑；steps「修改标题列表引用代码等语料」，expected_ui「格式与命令对应」，expected_server_effect「序列化结果与Web语料对应」）
- 实现：`app/src/main/java/com/denova/novara/feature/editor/EditorScreen.kt`（富文本工具栏：内联 B/I/`</>`/S 选区包裹 + 块级 H2/列表/引用/表格/链接/图）、`app/src/main/java/com/denova/novara/core/editor/MarkdownDocument.kt`（commonmark + GFM 表格解析与序列化）
- 单测：`MarkdownDocumentTest`（8 例）、`EditorNodeSupportTest`（6 例）、`MarkdownInlineTest`、`MarkdownTableTest`

## 1. 本轮补齐的验证

此前登记为缺口的「加粗 / 斜体 / 删除线只有工具按钮与单测层面的包围逻辑、未逐项真机验证」本轮全部在真机上逐项验证（富文本模式长按选中标题里的词 → 内联工具栏出现 B / I / `</>` / S → 点按 → 读回服务端正文）：

```text
B     → # G3 **并发测试文件**
I     → # G3 ***并发测试文件***
S     → # G3 ***~~并发测试文件~~***
</>   → # G3 ***~~`并发测试文件`~~***
```

每次点按后 `PUT /files/file` 都落盘并前进 revision（`91db14a2…` → `0b13e627…` → `fd357f7a…` → `70fd5428…`），即**源文本就是序列化结果**（编辑器以 Markdown 源为唯一真相，富文本只是视图），与 Web 基线的 `**` / `*` / `~~` / `` ` `` 口径一致。界面见 [inline-marks.png](inline-marks.png)。

块级语料（H2 / 列表 / 引用 / 表格 / 链接 / 图片）已在 `docs/evidence/g2c-nodes/xiaomi-14-pro/result.md` 与 `docs/evidence/g2c-table-images/xiaomi-14-pro/result.md` 逐项真机验证（插入模板后落盘、预览渲染 h1/h2/ul/blockquote/pre/table/image 正常）。

## 2. 序列化侧自动化覆盖

| 用例集 | 覆盖 |
|---|---|
| `MarkdownDocumentTest`（8 例） | 标题 / 加粗斜体 / 列表 / 代码 / 引用 / 图片 / GFM 表格 / 纯文本的解析与渲染 |
| `EditorNodeSupportTest`（6 例） | H2 / 列表 / 引用 / 表格 / 链接 / 图片六种结构插入模板 |
| `MarkdownTableTest`（10 例） | 表格行列插删、分隔行同步、Tab 跳格 |
| `MarkdownInlineTest` | 内联标记隐藏与编辑偏移不变 |

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全绿（56 套件 / 393 用例）。

## 3. 未覆盖 / 已知边界

- **无撤销 / 重做栈**：这是编辑器整体缺口（Compose 文本框未接 undo 状态），不影响本行「格式与命令对应 / 序列化结果与 Web 语料对应」的判据，但会让 WRI-014「撤销按基线更新」无验证条件。
- 合并单元格、列宽拖拽按基线 `resizable:false` 不做（已登记在 WRI-006）。
- 预览模式选段后不做内联格式化（内联按钮只在富文本模式出现）。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
