# 07 证据、决策与待验证事项

## 1. 证据边界

本包于2026-10-22读取固定GitHub提交完成源码核对。它验证了代码中的导航、样式、接口、事件和部分状态处理定义。没有运行该Web提交、没有执行其测试、没有连接用户服务器，也没有构建或运行Android。

正文中的“应 / 必须 / 通过条件”为开发要求；“建议 / 拟定”为方案选择；“待验证”为实际能力尚未获得运行证据。基线来源文件的blob SHA及验证状态见 [baseline.json](baseline.json)。

## 2. 固定源码索引

以下路径全部相对于 [固定提交](https://github.com/SeraphHsing/denova/tree/671ef4eb609523f07d1c234cdcd242d63ce12652)。点击路径可直接检查。

| 来源 | 用于核对 |
|---|---|
| [WorkbenchShell.tsx](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/workbench/WorkbenchShell.tsx#L189) | 常规、开发、插件与设置入口 |
| [workspace-mobile-layout.tsx](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/layout/workspace-mobile-layout.tsx#L45) | 共享顶栏、导航与右抽屉 |
| [MobileWritingWorkspace.tsx](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/workbench/MobileWritingWorkspace.tsx#L13) | 正文 / Agent保留状态 |
| [mobile.css](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/mobile.css) | 断点、尺寸、触控、字体与键盘布局 |
| [use-mobile-drawer-swipe.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/layout/use-mobile-drawer-swipe.ts#L23) | 手势阈值和排除区域 |
| [remote-access/api.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/remote-access/api.ts#L11) | status、登录、配对、退出和local身份 |
| [remote_access.go](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/internal/api/remote_access.go#L120) | 认证注册、原生来源与Cookie |
| [routes.go](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/internal/api/routes.go#L20) | 主API路由与注册中间件 |
| [client.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/lib/api-client/client.ts#L180) | 错误与401复核 |
| [chat.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/lib/api-client/chat.ts#L173) | 命令、任务、游标和恢复身份 |
| [agent-ui.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/lib/agent-ui.ts#L151) | UIMessage数据和附接恢复 |
| [游戏api.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/interactive/api.ts#L331) | 游戏发送、分支、暂停恢复 |
| [story-stage-stream-events.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/interactive/components/story-stage/story-stage-stream-events.ts#L8) | 35类事件及处理分类 |
| [project-files.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/lib/api-client/project-files.ts#L154) | 文件保存和revision |
| [useEditorDraftPersistence.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/Editor/useEditorDraftPersistence.ts#L121) | 自动保存、队列、三方合并及冲突材料 |
| [MarkdownContentEditor.tsx](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/components/Editor/MarkdownContentEditor.tsx#L49) | Markdown双表示和撤销语义 |
| [settings/api.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/settings/api.ts#L70) | 分层、revision及部分保存 |
| [settings-sections.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/settings/settings-sections.ts#L19) | 14个设置section |
| [platform/api.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/platform/api.ts#L148) | 本机限定和远程允许的管理操作 |
| [terminal/api.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/agent-chat/terminal/api.ts#L38) | 终端owner、创建和附接 |
| [终端生命周期](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/src/features/agent-chat/terminal/use-terminal-session-lifecycle.ts#L71) | detached PTY清理与owner归属 |
| [终端handler](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/internal/api/handlers/handler_terminal.go#L49) | 认证下的终端列表及附接token |
| [mobile-workspace.spec.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/tests/browser/mobile-workspace.spec.ts) | 手机导航、草稿、游戏、横屏和键盘的测试定义 |
| [mobile-reading.spec.ts](https://github.com/SeraphHsing/denova/blob/671ef4eb609523f07d1c234cdcd242d63ce12652/web/tests/browser/mobile-reading.spec.ts) | 消息已读、编辑和标签交互测试定义 |

## 3. 架构决定

| ID | 决定 | 理由 | 验证阶段 |
|---|---|---|---|
| ADR-01 | Kotlin / Compose原生，必要时原生View互操作 | 产品路线；复杂输入与绘制仍保持原生 | P1 |
| ADR-02 | 单app模块，按package组织 | 先建立职责和完整链路，控制工程复杂度 | P2 |
| ADR-03 | 后端代码零改动 | 对接现有服务端与权限 | 全过程 / P6 |
| ADR-04 | Web commit固定 | 页面、协议与验收可复现 | P0 |
| ADR-05 | Agent / Game / Terminal协议分别适配 | 事件、恢复与生命周期有不同契约 | P1 |
| ADR-06 | canonical Markdown与revision统一 | 双表示、并发和恢复需共享事实 | P1 / P3 |
| ADR-07 | 服务器+实体构成稳定作用域 | 响应与数据只投射到对应会话 | P1 / P5 |
| ADR-08 | 先技术关卡，再完整写作链路，再并行页面 | 早期获得关键可行性证据 | P1→P4 |
| ADR-09 | 客户端只回收自身终端owner | 独立客户端持有不同本地标签记录 | P1 / P4 |
| ADR-10 | 先采用里程碑与验收关卡路线 | 用户已确认；阶段按依赖和退出条件推进 | 全过程 |
| ADR-11 | 发送前持久化可恢复命令，编辑中持续保存草稿 | 本地提交边界按[03契约](03-api-and-state.md#6-写入与任务状态机)，不依赖进程终止回调 | P1 G2 / G3 / G4 |

## 4. 需要运行证据的事项

| ID | 事项 | 当前证据 | 处理与退出条件 |
|---|---|---|---|
| R01 | 富文本完整原生编辑 | 已核对Web节点和双模式逻辑；未选定通过的原生控件 | G2语料、操作、输入与序列化全通过 |
| R02 | 精确视觉参数 | 已提取CSS定义；未采集computed style或实际截图 | P0 / P1锁定字体、宽度、动态区域和阈值 |
| R03 | 真实服务器基线 | 已固定源码提交；未读取部署版本 | P0确认版本、配置、账号与数据 |
| R04 | 全部字段与条件分支 | 已建立功能族、路由与首批矩阵 | P0逐页展开及范围冻结 |
| R05 | 纯原生动态页面 | 远程Web存在loopback限制 | G6确认远程实际表现；有可用HTML运行页则登记能力缺口 |
| R06 | 双客户端终端共存 | Web会清理全局列表中本地不认识且attached=0的会话 | G6双端启动 / 后台 / 回收实测，不能仅靠App命名空间宣布解决 |
| R07 | 系统边缘手势 | Web有24px边缘识别规则 | 真机手势 / 三键导航验证并记录差异 |
| R08 | 日历工期 | 已确认先使用里程碑与验收关卡 | 后续需要日历排期时再补充容量与日期，当前不作为阶段启动前置条件 |
| R09 | 设备、签名与渠道 | 本轮未获取具体值 | P0 / P6形成可执行构建与交付记录 |
| R10 | 本地持久化与性能预算 | 已补充开发契约；数据规模、设备、数值预算与运行结果尚未获得 | P0冻结测量条件与预算，相关P1关卡按[06验证规范](06-development-and-validation.md)提供结果 |

【推测，基于固定源码的交错执行推演，待实测】R06中，App终端脱离连接后，浏览器启动的全局清理可能释放该PTY。App只清理自身owner可以约束自身删除范围，不能改变浏览器的清理规则。若双端实测出现误删或内容丢失，G6保持未通过，在后端零改动边界内寻找可行处理；无法完成时登记阻塞，并在任何范围调整前取得明确决定。记录限制本身不能支持全量1:1完成，也不得用新增后台保活承诺掩盖进程回收情形。

## 5. 平台差异登记

每条记录包含ID、对应矩阵行、Web行为、Android行为、原因、证据、用户影响、替代操作和决定人。候选项为系统返回边缘、系统文件选择、IME、字体栅格化、系统分享 / 下载、进程重建及终端跨客户端归属。

候选项不是自动豁免。应用内容缺失、数据损失、错误副作用和未经解释的操作差异不能归入系统差异。

## 6. 官方开发依据

- [Compose UI架构](https://developer.android.com/develop/ui/compose/architecture)：状态与事件分离。
- [Compose状态保存](https://developer.android.com/develop/ui/compose/state-saving)：轻量保存与持久存储边界。
- [Android进程生命周期](https://developer.android.com/guide/components/activities/process-lifecycle)：系统终止进程时不能依赖最终清理回调。
- [Compose与View互操作](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis)：原生控件接入。
- [WindowInsets](https://developer.android.com/develop/ui/compose/system/insets)：系统栏、可用内容区与键盘。
- [网络安全配置](https://developer.android.com/privacy-and-security/security-config)：HTTP / TLS配置。
- [AGP发布说明](https://developer.android.com/build/releases/gradle-plugin)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)：P0核对工具链与依赖兼容关系。

官方文档用于指导实现，不证明本项目已经构建或具备相应运行能力。
