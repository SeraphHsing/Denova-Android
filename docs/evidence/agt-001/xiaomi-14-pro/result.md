# AGT-001 完整消息部件

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 会话：`project-efb2ed8ad64c839df4b72c3a69772350` / `default`（57+ 条历史的真实会话）
- 对应矩阵：`AGT-001`（Agent消息与任务 / 完整消息部件；steps「读取流式和历史消息及工具详情」，expected_ui「文本推理工具图片来源均可访问」，expected_server_effect「保留完整parts和稳定身份」）
- 实现：`app/src/main/java/com/denova/novara/core/stream/AgentMessageParts.kt`、`feature/agent/AgentViewModel.kt`、`feature/agent/AgentScreen.kt`、`core/network/DenovaClient.kt`
- 单测：`app/src/test/java/com/denova/novara/AgentPartsTest.kt`（10 用例）

## 1. 变更要点

| 变更 | 说明 |
|---|---|
| 部件模型 | `AgentPart` 封装 `text` / `reasoning` / `dynamic-tool` / `file` / `data-agent-*`；未知类型落 `AgentUnknownPart`，一律保留原 JSON，不再压平成一段文本 |
| 稳定身份 | 消息 `id@位置` + 部件 `#序号`（历史重载后同一部件得到同一 key） |
| 历史 | `reloadCanonical()` 改用 `AgentPartLogic.parseMessages`，保留每条消息的完整 parts；流式正文单独作为「流式输出」尾块 |
| 工具详情 | 卡片显示工具名、状态（执行中/已完成/失败）、一行入参摘要；展开后是格式化后的输入与输出（超 1200 字符截断并标原长） |
| 图片 | 三类来源：工作区相对路径 → `GET /api/projects/:id/files/asset`（同鉴权客户端取字节）、绝对 http(s) → 直取、`data:` → 本地 base64 解码；解码失败明确显示「图片不可用 + 路径」 |
| 事件 | `data-agent-token-usage` / `-execution-summary` / `-ask` / `-workspace-change` / `-todo` / `-proposed-plan` 等给一行摘要，展开看原始 JSON |
| 贴底 | 新消息与流式增量到达时自动滚到底部 |

## 2. 真机验证

### 2.1 历史加载保留 parts

```text
I Agent: start session=default project=project-efb2
I Agent: canonical parts loaded messages=48 tools=22 session=default
```

（改造前该行只有 `len=<拼接文本长度>`，parts 已被压平；现在同时报消息数与工具部件数。）

### 2.2 部件渲染

[docs/evidence/.../tool-detail-expanded.png](tool-detail-expanded.png) 与运行中 dump：

```text
你
read the file /etc/hostname again
助手
工具 · read                    已完成
/etc/hostname
助手
agent-token-usage · cache_hit_rate=93.1% prompt_tokens=61500 cached_prompt_tokens=57244 … model_calls=2
助手
agent-execution-summary · completed duration_ms=15086                          展开
助手
工具 · read                    失败
/etc/hosts
agent-ask · tool_approval / cancelled                                          展开
```

- 工具部件：`已完成`（正确执行）、`失败`（`/etc/hosts` 被运行时拒绝）两种状态都如实显示；
- 事件部件：用量与执行摘要按服务端 `content`/`run_status`+`duration_ms` 摘要，展开可看完整 JSON；
- 点击工具卡展开后出现 `输入` / `输出` 两段，内容为格式化 JSON（`resource.read.v1`、`process.result.v1` 等原样保留）。

### 2.3 图片来源可访问

真机核对发现：生成图片**不以 `file` 部件下发**，而是放在工具结果的 `generated_image.v1`：

```json
{"schema":"generated_image.v1","status":"success","target_path":"assets/redpanda.png",
 "images":[{"path":"assets/writing/3d0a59ab-…-6b6150a303ed.jpeg","markdown":"![Generated image](…)"}]}
```

因此图片渲染改为「工具结果里取图片路径」，并按工作区路径经鉴权客户端取字节后本地解码。
[image-part-rendered.png](image-part-rendered.png) 是 `generate_image` 卡片下的实际渲染结果：
`图片 · 3d0a59ab-….jpeg` 与 `图片 · redpanda.png` 两处都显示为真实位图（同一张图存了两份：`images[].path` 与 `target_path`）。

### 2.4 流式与历史的关系

本轮运行期间状态为 `运行中 · 已连接`，正文以「流式输出」尾块增长；运行结束后 `reloadCanonical()`
用 canonical 历史重建部件（消息数 45 → 48），流式正文清空，不出现同一段内容重复展示。

## 3. 真机发现并修复的缺陷

1. **重复 key 崩溃**：`token_usage` 与 `execution_summary` 两条消息共用 run id 作消息 id，
   `LazyColumn` 用 `it.id` 作 key 直接
   `FATAL EXCEPTION: IllegalArgumentException: Key "run-20261024T172543…" was already used`（连崩两次）。
   修复：消息身份改为 `id@位置`、部件 id 追加 `#序号`，并补单测 `duplicate message ids get unique stable keys`。
2. **事件摘要重复**：服务端 `content` 已是整句，再拼 `total_tokens=… model_calls=…` 出现
   「… model_calls=2 · model_calls=2」。修复：有 `content` 时只用它。
3. **生成图不渲染**：初版只处理 `file` 部件，而服务端不下发该部件 → 图片完全看不到。改为从工具结果提取路径（`images[].path` / `image_path` / `target_path`）。
4. **历史很长时看不到最新内容**：列表从顶部开始，需要手动下滑几十屏。补「新内容贴底」。

## 4. 自动化覆盖

`AgentPartsTest`（10 用例）：text/reasoning 稳定 id 与 streaming 标记；dynamic-tool 的名称/调用 id/入参摘要/输出状态；
输出信封 `status` 优先于 AI SDK state（`running` 不算成功）、`output-error`/`output-denied` 判失败；
图片部件三类来源分类；`data-agent-*` 摘要（用量、执行摘要、提问）；未知部件保留原始 JSON；
`prettyText` 截断与原文长度、非 JSON 原样；图片路径提取（`generated_image.v1`、`image_path`、非 JSON 返回空）。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 48 suites / 315 tests / 0 failures。

## 5. 结论与未覆盖

**通过**：历史消息按 part 完整保留并渲染（文本 / 工具 / 事件），工具详情可展开看输入输出与错误，
图片（生成图）能按工作区路径实际加载显示，部件身份稳定且不重复；流式正文与历史不重复。

**未覆盖（登记缺口）**：

- **推理部件未在真机构造**：该会话历史只有 `text` / `dynamic-tool` / `data-agent-ask` / `-token-usage` / `-execution-summary` 五类，没有 `reasoning` 部件（模型或后端未落库）；渲染路径与单测已就绪。
- `file` 部件同理未出现（服务端改用工具结果承载图片），远程 `http(s)` 与 `data:` 图片分支未真机触发。
- 工具详情是通用 JSON 展示，未做 Web 那种按工具类型定制的 adapter（`toolDetailAdapters`）。
- 历史分页（`AGT-010`）、会话切换迟到回包（`AGT-011`）、上下文/Todo 展示（`AGT-012`）不在本切片。
- 仪器测试未执行（`connectedDebugAndroidTest` 受本机网络/策略阻塞，已在 WRI-016 证据中登记）。
