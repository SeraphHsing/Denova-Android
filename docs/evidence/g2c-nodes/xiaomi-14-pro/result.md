# G2-c 编辑器节点：表格 / 图片 / 结构插入 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug
- 服务器：`http://192.168.2.102:18081`（**本轮改用该部署**：`192.168.2.101:18098` 的编辑器保存被上游以 501 `Unsupported method ('PUT')` 拒绝，无法落盘）
- 项目：`project-efb2ed8ad64c839df4b72c3a69772350`
- 夹具：`g2c-nodes.md`（标题 / 列表 / 引用 / 代码块 / 6 列表格 / `assets/redpanda.png`），经 `POST /files/operations`（kind=create）建立，验证后删除
- 时间：2026-10-26 00:27–00:44

## 1. 结构插入（WRI-005 / WRI-006）

富文本模式新增第二行工具栏：`H2` `•` `>` `表` `链` `图`，均为「在光标处插入 Markdown 模板」，序列化仍以源文本为准。

- 点「表」插入 2 行 3 列模板后保存：服务端 revision `sha256:605ff2…`，正文出现第二个分隔行（`separator rows: 2`），原有 6 列表格未受影响 → 插入与保存链路正确。
- 在标题行插入字符后保存：服务端 `headings: ['# G2-c 节点语料', '## 二级Q标题']`，revision `sha256:1b1c84…`（242→243 字节）→ 编辑落盘、revision 前进。
- 同样方式验证了在表格分隔行插入字符（`--Z-`）与删除，revision `sha256:777751…`；随后用 PUT 把夹具恢复原状（`sha256:c58599…`）。

## 2. 预览渲染

`预览` 截图（`preview-nodes.png`）显示：`h1` / `h2` / 圆点列表 / 引用条 / 代码块底色 / 带边框 6 列表格 / 红熊猫图片。

## 3. 图片（WRI-007）

预览里的 `<img src="assets/redpanda.png">` 按「当前文档目录」解析后改写成项目资源接口，再由 OkHttp 代取（WebView 拿不到 OkHttp 会话 Cookie）：

```
Editor: preview img raw=assets/redpanda.png resolved=http://192.168.2.102:18081/api/projects/project-efb2…/files/asset?path=assets%2Fredpanda.png
Editor: preview asset ok path=assets/redpanda.png mime=image/png bytes=511916
```

图片在预览中正常显示（`preview-image.png`），并按容器宽度等比缩放。取图失败时返回原状态码，浏览器保留 `alt` 文案（单测断言 `<img>` 带 `alt`）。

### 本轮修掉的两个真实缺陷

| 现象 | 原因 | 修复 |
|---|---|---|
| 预览图片一直是破图，且 WebView 完全没有发出请求 | `loadDataWithBaseURL(null, …)` 让文档处于不透明来源，http 子资源请求被丢弃 | base URL 改用服务器 origin（`PreviewAssetLoader.baseUrl`） |
| 修好请求后仍然破图，日志显示已取到 511916 字节 | 在 OkHttp 的 `use {}` 内返回 `body.byteStream()`，把响应关掉后 WebView 才去读 | 先读成 `ByteArray`，再用 `ByteArrayInputStream` 交给 WebView |

## 4. 表格横向滚动（WRI-006）

`<table>` 统一包进 `.table-scroll { overflow-x:auto }`（单测断言包裹结果）。本夹具 6 列在 1440px 下未溢出，所以真机没有触发滚动；宽度不足时的滚动行为只有 CSS 与单测证据，没有真机截图。

## 5. 中文输入法（WRI-008）—— 真机通过（人工输入）

实现：`BasicTextField.onValueChange` 把 `TextFieldValue.composition != null` 传下去，`EditorSession.edit(text, composing)` 在组合态只更新本地正文、取消待发的自动保存，组合结束后第一次回调才排保存；JVM 用例 `editing during IME composition does not autosave` 覆盖两个分支。

真机验证（`ime-check.md`，内容只有一行提示语，29 字符）：

| 时刻 | 状态 | 证据 |
|---|---|---|
| 01:23:42 | 最后一次落盘（`be325c5d…`，29 字符） | `Editor: saved …` |
| 01:23:5x–01:24:4x | 输入法切到中文拼音，编辑区显示 `ni'ha'd` → `ni'ha'c`，候选条列出「你哈才 / 你哈 / 你 / …」 | `ime-composing-no-save.png`；**期间无任何 `saved` 日志**，界面也没出现新的「已保存」 |
| 01:24:45 | 空格提交候选，正文变成 `你哈才` | `saved path=ime-check.md revision=sha256:c1b6ea60…`，服务端读到 `…# 输入法组合态检查\n\n请在这里用中文输入法敲一句拼音。\n你哈才`（32 字符） |

即：**组合期间完全不落盘、提交后立刻落盘**，与 `expected_server_effect`「未提交组合态不误发送」一致。截图 `ime-composing-no-save.png` / `ime-committed-saved.png`。

组合态由人工输入完成（脚本路径不可用）：`adb shell input text` 直接注入字符、不经过输入法组合；键盘中英切换只能点屏幕键，脚本实测始终英文直出。验证用的临时文件已从项目删除。

## 6. 部署问题（阻塞项）

`192.168.2.101:18098` 的 `PUT /api/projects/:id/files/file` 返回 501（HTML 页面 `Server does not support this operation`），编辑器保存、版本差异构造都走不通；`192.168.2.102:18081` 的同名接口正常（`已保存` + revision 前进）。这是部署差异，不是客户端代码路径问题；客户端不为此做任何特殊分支。

## 7. 验证

- JVM：`EditorNodeSupportTest` 6 例（相对路径解析、`..` 折叠与越界拒绝、外链/data 不重写、表格模板解析与包裹、结构语料渲染、图片 `alt` 保留）+ `EditorSessionTest` 组合态用例。
- 主检查：`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 全绿。
- 仪器测试：未执行。
