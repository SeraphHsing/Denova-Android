# AGT-003 审批及记忆规则：允许本次 / 始终允许（工作区）/ 拒绝

- 日期：2026-10-25
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 会话：写作 Agent `session_id=default`，项目 `project-efb2ed8ad64c839df4b72c3a69772350`
- 对应矩阵：`AGT-003`（Agent 消息与任务 / 审批及记忆规则；steps「同意拒绝并选择服务端允许规则」，expected_ui「审批内容和可选作用域对应」，expected_server_effect「只解决精确 ask 或 approval 身份」）
- 实现：`app/src/main/java/com/denova/novara/core/stream/AgentApproval.kt`、`AgentAsk.kt`、`app/src/main/java/com/denova/novara/feature/agent/AgentViewModel.kt`、`AgentScreen.kt`
- 单测：`app/src/test/java/com/denova/novara/AgentApprovalTest.kt`（8 用例）

## 1. 契约与实现口径

| 环节 | 依据 | Android 实现 |
|---|---|---|
| 下发 | `kind=tool_approval` 的提问，**questions 为空**，决策在 `approval` | `AgentAskInteraction.approval` + `isToolApproval` |
| 审批内容 | `tool_name` / `mode` / `risk` / `cwd` / `command`‖`details` / `rule_id` / `can_remember` / `rule_display_pattern` | 卡片按同序展示：工具、模式、风险、目录、命令、原因 |
| 可选作用域 | `allow-workspace` 仅在 `can_remember=true` 且 `rule_display_pattern` 非空时提供（Web `canAlwaysAllow`） | `AgentApprovalLogic.canAlwaysAllow/options`，按钮按服务端能力裁剪 |
| 作用域文案 | 原文照抄 `rule_display_pattern` | `批准后写入工作区规则：<pattern>`，本地不构造规则 |
| 答案体 | `answers:[{question_id:"tool-approval", selected_option_ids:[<option>]}]`，option ∈ `allow-once`/`allow-workspace`/`deny` | `AgentApprovalLogic.answer`，未知 option 不产生请求 |
| 身份 | 只解决精确 ask id（`permission-tool-*`） | 提交路径带 `ask_id`，`pendingAsks` 内找不到即拒绝执行 |

## 2. 真机验证

### 2.1 触发审批（不可记忆）

把写作会话审批模式设为 `ask`（见 §3 的坑），要求执行不在白名单的 shell 命令 `rm -f /tmp/agt003_probe.txt`：

```text
I Agent: send session=default cmd=3eb5151b… refs=1 selections=0
I Agent: ask pending id=permission-tool-a0c7c9c5209c424b031f824a58612a42 questions=0
```

卡片（[approval-card-allow-once.png](approval-card-allow-once.png)）：

```text
工具调用审批                         等待批准
工具：bash   模式：逐次询问   高风险
rm -f /tmp/agt003_probe.txt
原因：该 shell 命令不在允许列表内
[拒绝] [允许本次] [取消]
```

只有两个决策项——服务端对该调用 `can_remember=false`，因此**不出现**「始终允许」，与「可选作用域对应」的要求一致。

点「允许本次」：

```text
I Agent: approval resolved id=permission-tool-a0c7… option=allow-once status=answered
```

服务端历史读回：

```json
{"kind":"tool_approval","id":"permission-tool-a0c7…","status":"answered",
 "answers":[{"question_id":"tool-approval","selected_options":[{"id":"allow-once","label":"allow-once"}]}]}
```

工具随后真的执行：`dynamic-tool` 的 output 为 `{"schema":"process.result.v1","status":"success","shell":"bash",…}`。

### 2.2 可记忆审批与工作区规则

要求读取工作区外文件 `/etc/hostname`：

```text
I Agent: ask pending id=permission-tool-1489a966baa84f8c20265ccfe53b2d92 questions=0
```

卡片（[approval-card-workspace-scope.png](approval-card-workspace-scope.png)）：

```text
工具调用审批                         等待批准
工具：filesystem_read   模式：逐次询问   中风险
/etc/hostname
原因：该调用会读取工作区外的文件
批准后写入工作区规则：/etc/hostname
[拒绝] [始终允许（工作区）] [允许本次] [取消]
```

点「始终允许（工作区）」：

```text
I Agent: approval resolved id=permission-tool-1489… option=allow-workspace status=answered
```

服务端设置读回（`GET /api/settings`）：

```text
rules 2
  approval-debdca30342b060… workspace filesystem_read | pattern: /tmp/devil_content.md   ← 既有规则
  approval-4d7d91912e539bc… workspace filesystem_read | pattern: /etc/hostname           ← 本次新增
```

### 2.3 记忆规则生效

再发一次「read the file /etc/hostname again」：**没有**新的 `ask pending` 日志，工具直接执行——规则命中后不再打断用户。

### 2.4 拒绝

要求读取另一个工作区外文件 `/etc/hosts` → 出现审批卡 → 点「拒绝」：

```text
I Agent: approval resolved id=permission-tool-6b8eaa56… option=deny status=answered
I Agent: ask resolved      id=permission-tool-6b8eaa56… status=cancelled
```

服务端历史把拒绝落成 `status=cancelled`，但保留答案：

```json
{"kind":"tool_approval","status":"cancelled",
 "answers":[{"question_id":"tool-approval","selected_options":[{"id":"deny"}]}]}
```

界面（[approval-denied.png](approval-denied.png)）：`已拒绝`。

### 2.5 恢复页面与身份

`/etc/hosts` 那次拒绝后 Agent 又发起新的审批（`permission-tool-f5222980…`）。此时执行 `install -r` + `force-stop` 重启，从编辑器进入写作 Agent：

- `pending_asks` 让卡片原样重现（含命令、原因、按钮）；
- 再点「拒绝」→ `approval resolved id=permission-tool-f5222980… option=deny`，随后 `phase=idle`、`pending_asks=null`。

审批只解决该 id（提交路径带 `ask_id`，本地列表里找不到就不发请求）。

## 3. 开发中修复的缺陷与踩到的坑

1. **拒绝被显示成「已取消」**：服务端把 `deny` 落成 `status=cancelled`（答案里仍有 `answers=[deny]`）。初版按 status 优先 → 显示「已取消」。
   改为**选中项优先**：`allow-workspace` → 已允许（工作区规则）、`allow-once` → 已允许（本次）、`deny` → 已拒绝，`cancelled` 且无 answers 才是「已取消」。单测覆盖，真机复验见 2.4。
2. **审批模式不在 `/api/settings`**：先改 `user.agent_approval_mode=ask` 后 bash 仍直接执行；真正的开关是**会话配置**
   `PATCH /api/projects/:id/conversation-config`，body `{binding:{mode:"writing"}, base_revision, changes:{approval_mode:"ask"}}`（`GET` 同路径 `?mode=writing`，`mode` 只接受 `writing`/`agent_chat`/`interactive`）。
   验证结束后已恢复 `approval_mode=write`（revision 3），环境回到原状。

## 4. 自动化覆盖

`AgentApprovalTest`（8 用例）：`tool_approval` 载荷解析（含 `questions=[]`、`can_remember`、`rule_display_pattern`、`rule_matcher_version`）、普通提问不误判为审批、
「始终允许」仅在服务端允许记忆且作用域非空时出现（否则只有两项）、答案体固定 `question_id="tool-approval"` 与三个 option、请求 JSON 字段名、
状态映射（含 deny→已拒绝 的优先级）、原因/风险/模式映射与未知值回落、命令优先于 details。

主检查：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`；
`testDebugUnitTest` 合计 45 suites / 279 tests / 0 failures。

## 5. 结论与未覆盖

**通过**：审批卡片内容（工具/模式/风险/命令/原因）与服务端下发一致；可选作用域随 `can_remember` 变化（两项 / 三项），作用域原文照抄；
允许本次、始终允许（工作区）、拒绝三条路径都能落到正确的服务端状态与答案；工作区规则真实写入并在下一次同类调用生效；未决审批在进程重启后恢复；
审批只解决对应 ask id。

**未覆盖（登记缺口，不改变本条状态）**：

- 规则管理（`DELETE /api/settings/agent-approval-rules/:id` 撤销、按项目层记忆）未做 UI。
- `browser_*` / `pwsh_*` 类原因只做文案映射，未真机触发；`args_hash` / `rule_match_key` 不展示（仅诊断用途）。
- 多端并发审批（另一端先解决导致 `ask_stale` / `ask_conflict`）只在单测层面覆盖文案。
- 审批卡片未在历史消息里内联展示，重启后只对未决审批可见。
- 仪器测试（`connectedDebugAndroidTest`）未执行。
