# 07 证据、决策与待验证事项

## 1. 证据边界

本包于2026-10-22读取固定GitHub提交完成源码核对。它验证了代码中的导航、样式、接口、事件和部分状态处理定义。没有运行该Web提交、没有执行其测试、没有连接用户服务器，也没有构建或运行Android。

正文中的“应 / 必须 / 通过条件”为开发要求；“建议 / 拟定”为方案选择；“待验证”为实际能力尚未获得运行证据。基线来源文件的blob SHA及验证状态见 [baseline.json](baseline.json)。

## 2. 固定源码索引

以下路径全部相对于 [固定提交](https://github.com/SeraphHsing/denova/tree/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e)。点击路径可直接检查。

| 来源 | 用于核对 |
|---|---|
| [WorkbenchShell.tsx](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/workbench/WorkbenchShell.tsx#L189) | 常规、开发、插件与设置入口 |
| [workspace-mobile-layout.tsx](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/layout/workspace-mobile-layout.tsx#L45) | 共享顶栏、导航与右抽屉 |
| [MobileWritingWorkspace.tsx](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/workbench/MobileWritingWorkspace.tsx#L13) | 正文 / Agent保留状态 |
| [mobile.css](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/mobile.css) | 断点、尺寸、触控、字体与键盘布局 |
| [use-mobile-drawer-swipe.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/layout/use-mobile-drawer-swipe.ts#L23) | 手势阈值和排除区域 |
| [remote-access/api.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/remote-access/api.ts#L11) | status、登录、配对、退出和local身份 |
| [remote_access.go](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/internal/api/remote_access.go#L120) | 认证注册、原生来源与Cookie |
| [routes.go](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/internal/api/routes.go#L20) | 主API路由与注册中间件 |
| [client.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/lib/api-client/client.ts#L180) | 错误与401复核 |
| [chat.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/lib/api-client/chat.ts#L173) | 命令、任务、游标和恢复身份 |
| [agent-ui.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/lib/agent-ui.ts#L151) | UIMessage数据和附接恢复 |
| [游戏api.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/interactive/api.ts#L331) | 游戏发送、分支、暂停恢复 |
| [story-stage-stream-events.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/interactive/components/story-stage/story-stage-stream-events.ts#L8) | 35类事件及处理分类 |
| [project-files.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/lib/api-client/project-files.ts#L154) | 文件保存和revision |
| [useEditorDraftPersistence.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/Editor/useEditorDraftPersistence.ts#L121) | 自动保存、队列、三方合并及冲突材料 |
| [MarkdownContentEditor.tsx](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/components/Editor/MarkdownContentEditor.tsx#L49) | Markdown双表示和撤销语义 |
| [settings/api.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/settings/api.ts#L70) | 分层、revision及部分保存 |
| [settings-sections.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/settings/settings-sections.ts#L19) | 14个设置section |
| [platform/api.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/platform/api.ts#L148) | 本机限定和远程允许的管理操作 |
| [terminal/api.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/agent-chat/terminal/api.ts#L38) | 终端owner、创建和附接 |
| [终端生命周期](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/src/features/agent-chat/terminal/use-terminal-session-lifecycle.ts#L71) | detached PTY清理与owner归属 |
| [终端handler](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/internal/api/handlers/handler_terminal.go#L49) | 认证下的终端列表及附接token |
| [mobile-workspace.spec.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/tests/browser/mobile-workspace.spec.ts) | 手机导航、草稿、游戏、横屏和键盘的测试定义 |
| [mobile-reading.spec.ts](https://github.com/SeraphHsing/denova/blob/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e/web/tests/browser/mobile-reading.spec.ts) | 消息已读、编辑和标签交互测试定义 |

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
| ADR-12 | 登录会话 Cookie 持久化到应用私有存储（按 origin 隔离，退出登录清空） | 取代 G1 期「Cookie 仅内存」：R06 实测 Web 会回收 `attached=0` 的会话，持久化后进程被杀重启可免登录并在秒级重挂，缩短暴露窗口（用户 2026-10-24 批准）；需在发布前补安全复核 | P1 G6 / P6 |

## 4. 需要运行证据的事项

| ID | 事项 | 当前证据 | 处理与退出条件 |
|---|---|---|---|
| R01 | 富文本完整原生编辑 | 已核对Web节点和双模式逻辑；未选定通过的原生控件 | G2语料、操作、输入与序列化全通过 |
| R02 | 精确视觉参数 | 已提取CSS定义；未采集computed style或实际截图 | P0 / P1锁定字体、宽度、动态区域和阈值 |
| R03 | 真实服务器基线 | 已固定源码提交；未读取部署版本 | P0确认版本、配置、账号与数据 |
| R04 | 全部字段与条件分支 | 已建立功能族、路由与首批矩阵 | P0逐页展开及范围冻结 |
| R05 | 纯原生动态页面 | 远程Web存在loopback限制；终端相关契约已由 G6 对齐（见 [05 §3.1](05-roadmap.md#31-p1-证据台账2026-10-24-核对) G6-b） | 剩余「权限表现一致」（14个设置section分层）随 P4-D 验证；有可用HTML运行页则登记能力缺口 |
| R06 | 双客户端终端共存 | **已实测确认（2026-10-24，真实 Chrome + 脚本第二客户端）**（缓解 B+C 已落地：持久化登录 Cookie 免登录重挂 + 本地 64KB 历史只读回放；不改变基线回收行为）：① 双端 attach 共享同一输出（`attached` 1→2）；② 一端 DELETE 时对端静默断 TCP，App 7–9s 内落到「已在其他端释放」且不静默重建；③ 同 owner 跨项目 409 ownerConflict 时立即停手；④ **真实浏览器挂载「工作台」时，删除所有 `attached=0` 且不在其持久化标签里的会话，不区分 owner/项目** —— 直接删掉了 `owner=android-main` 的 Android PTY。**时序排查**：切页回编辑器 `attached` 保持 1、回前台 2s 内重挂；HOME 后 30s 即 `attached=0`（进程冻结，客户端无日志），窗口随时长变长，进程死亡后更长。G6 据此判为未通过 | 需范围决策（后端零改动、不得用后台保活）：① 接受限制并调整范围；② 客户端本地留存近期 scrollback（降级为可回放）；③ 持久化登录 Cookie 缩短进程死亡后的窗口（与 G1「Cookie 仅内存」冲突，需批准）。详见 `docs/evidence/g6-dual-client/xiaomi-14-pro/result.md` |
| R07 | 系统边缘手势 | Web有24px边缘识别规则 | 真机手势 / 三键导航验证并记录差异 |
| R08 | 日历工期 | 已确认先使用里程碑与验收关卡 | 后续需要日历排期时再补充容量与日期，当前不作为阶段启动前置条件 |
| R09 | 设备、签名与渠道 | 本轮未获取具体值 | P0 / P6形成可执行构建与交付记录 |
| R10 | 本地持久化与性能预算 | 已补充开发契约；数据规模、设备、数值预算与运行结果尚未获得 | P0冻结测量条件与预算，相关P1关卡按[06验证规范](06-development-and-validation.md)提供结果 |

【已实测确认，2026-10-24】R06中，App终端脱离连接后，浏览器挂载工作台的全局清理**确实**释放了该PTY：实测捕获到 `DELETE /api/terminal/sessions/<id>` 同时删掉 `owner=android-main` 与一个非 Web 标签 owner 的会话，列表随即清空；触发点只在挂载工作台时，单纯登录/首页加载只读取列表。App 侧在现有边界内已做到「不静默重建空 PTY、7–9s 内如实提示、只 DELETE 自身会话」，但**无法阻止**对方删除，也无法保住 PTY 内的 scrollback。按 G6 退出条件（误删对方 PTY 或丢失会话内容即未通过），**G6 保持未通过并登记阻塞**：在后端零改动、且不得以新增后台保活掩盖进程回收的前提下，可选方向只有「接受限制并调整范围」或「客户端本地留存近期 scrollback，把内容丢失降级为可回放」，需明确决定后再实施。记录限制本身不能支持全量1:1完成。

### 范围决定（Scope Decision）

| ID | 决定 | 依据 | 决定人 / 日期 | 影响与替代操作 |
|---|---|---|---|---|
| SD-01 | Web 端（固定基线 `85ec97c`）在挂载工作台时回收所有 `attached=0` 且不在其本地持久化标签中的终端会话，**属基线行为，Android 侧不作对抗**。Android 保证：不静默重建空 PTY、7–25s 内如实提示、只 DELETE 自身会话、被回收后保留最近 64KB 只读历史、进程被杀重启免登录并秒级重挂（ADR-12） | [R06](#4-需要运行证据的事项) 真实 Chrome 实测（对照 + 隔离变量） | Hsing / 2026-10-24 | 影响：PTY 被回收后无法在原 shell 继续操作；替代操作：重新连接新建会话 + 通过「查看历史」回看本地留存输出。范围仅限终端 PTY 生命周期，不影响其余权限表现；不得据此放宽其他判据 |
| SD-02 | 固定基线**没有「文档内图片上传」**：Web 编辑器的图片能力是「生成插画」与引用项目内相对资源（`assets/…`），后端的上传接口只有 `POST /api/books/cover/upload`、`POST /api/projects/:id/book/lore/items/:id/materials/upload`、`POST /api/update/upload`，**没有项目文件上传**。Android 侧因此不实现上传，改为「从项目 `assets/` 选择既有素材插入」，相对路径 / 渲染 / 失败保留 alt 与基线一致 | 基线源码 `web/src/components/Editor/WritingDocumentEditor.tsx`、`MarkdownContentEditor.tsx`（TipTap + TableKit，无上传入口）；`internal/api/routes.go` 路由清单 | Hsing / 2026-10-26 | 影响：不能从相册直接往正文插图；替代操作：先把图片放进项目 `assets/`（走 Web 端既有入口或外部工作流），再在编辑器「图」里选择。矩阵 WRI-007 的验收口径按此调整（[证据](../evidence/g2c-table-images/xiaomi-14-pro/result.md)），不得据此放宽其它判据 |

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
