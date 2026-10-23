# AGENTS.md — Denova Android

## 1. 每次开始工作的阅读顺序

必须按以下顺序读取并遵守：

1. 本文件：项目约束和开发流程。
2. [docs/STATUS.md](docs/STATUS.md)：当前状态、阻塞项和下一步。
3. [文档索引](docs/README.md)：定位规格、接口清单与验收矩阵。
4. 与本次任务对应的规格、矩阵行和基线来源；需要时阅读完整 [开发方案总览](docs/android-web-parity/README.md)。

不要只根据 README 摘要、旧对话或函数名实现功能。业务实现任务确认矩阵 ID；立项、工程准备和规划任务可使用 P0任务 ID 或 ADR编号；纯文档维护注明修改的规格章节。随后核对 Web 基线、相关调用路径和当前状态。

## 2. 项目目标与不可越过的边界

- 目标是对固定基线中的全部手机 Web 页面、子页面、条件入口、操作流程、状态和权限表现进行原生 Android 对应实现。
- 使用 Kotlin、Jetpack Compose、单 Activity；复杂编辑和终端控件可在 P1 原型验证后通过 Android View 互操作。
- 后端代码零修改。不新增后端接口、不改变服务端 DTO、权限规则或既有协议来迁就客户端。
- 固定 Web 基线：`SeraphHsing/denova@85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e`。基线变化必须先登记行为差异并更新相关文档和矩阵。
- 规格中的技术选择、接口形状与验收条件是开发约束；标记为“建议”或“待验证”的内容不能被描述为已经实现。

## 2.1 架构与事实来源（摘要）

- 后端零改动；固定 Web 基线 `SeraphHsing/denova@85ec97c`。协议与字段的权威来源是后端 Go 源码（本地副本优先：`E:\Work\Git\denova\denova`，内层目录才是 git 仓库；GitHub 仅作兜底）。仓库内 `docs/` 是冻结快照，与 Go 源码冲突时以源码为准并回写文档。
- 目标形态：Kotlin + Jetpack Compose、单 Activity、单 `app` 模块，内部按 feature package 隔离所有权；OkHttp + kotlinx.serialization 处理 REST / SSE / WebSocket，Room 存草稿与命令身份，DataStore 存轻量偏好，AppContainer 手动注入。详细目录与状态所有权见 [02 技术架构](docs/android-web-parity/02-architecture.md)。
- 服务端是业务事实源；本地只持久化连接偏好、轻量界面状态、草稿、命令身份与恢复锚点。
- 稳定身份为 Android 内部 key，传输字段仍用现有接口：`ServerKey` → `ProjectKey` → `DocumentKey`，另有 `WritingSession` / `WorkbenchChat` / `GameSession` / `TaskBinding` / `TerminalOwner`。禁止把显示名、列表下标或“当前项目指针”当作持久身份。
- Agent UIMessage、游戏 SSE、终端 WebSocket 是三套不同协议，必须分别解析、测试和恢复。
- 路线：P0 基线与全量盘点 → P1 原型（G1 认证 / G2 编辑器 / G3 保存并发 / G4 双流恢复 / G5 终端 / G6 远程与双端共存）→ P2 骨架 → P3 写作链路 → P4 游戏·工作台·共通 → P5 全量双端对照 → P6 真机与发布候选。

## 3. 变更流程

1. 阅读对应规格和任务记录；业务实现同时读取矩阵行，确认前置条件、请求契约、作用域、预期服务端副作用及验收证据。
2. 用最小范围的修改完成一条可验证用户流程；不做无关重构，不为未证实的未来需求增加抽象。
3. 为协议、并发、恢复、权限和关键状态编写能复现风险的测试；UI 变更提供对应截图或设备证据。纯文档修改执行下述静态检查，不为它增加程序测试。
4. 业务变更更新对应矩阵行的实现位置、状态和证据；规划或文档维护不改写业务实现状态。只有实际执行后才可将“未执行”改为通过。
5. 提交关联矩阵 ID、P0任务 ID、ADR编号或所改规格章节，记录适用的验证命令、环境、结果和未解决问题。阶段、阻塞项或下一步发生变化时同步更新 `docs/STATUS.md`。
6. 分支使用 `feat/` `fix/` `docs/` 前缀，提交信息采用 Conventional Commits；业务变更同步中英文字符串与文档，未按此执行的提交在评审中视为未完成。

## 4. 状态、作用域与恢复

- 服务器是业务事实源；本地持久化仅保存连接偏好、轻量界面状态、草稿、命令身份和恢复锚点。
- 所有持久身份必须包含正确的服务器与实体作用域。服务器切换时隔离 Cookie、缓存、草稿、任务游标和凭证。
- 迟到响应只有在请求作用域及世代仍有效时才能更新当前状态。
- 写入结果不明时先读取服务端 canonical 状态判断是否已接受，不创建新命令盲目重发。
- 可恢复 Agent / Game 命令必须先完成本地事务再发送；草稿在编辑期间持续保存，后台回调仅补充刷新。持久化边界遵循 [03 写入与任务状态机](docs/android-web-parity/03-api-and-state.md#6-写入与任务状态机)；不得依赖进程终止时的清理回调。
- Agent UIMessage、游戏 SSE、终端 WebSocket 是不同协议，必须分别解析、测试和恢复。
- 保存必须处理 revision、编辑世代、迟到回执和三方合并；冲突时保留用户内容，不静默覆盖。
- 只释放由本客户端明确持有的终端 owner；跨客户端终端清理风险须按 G6 实测记录。

## 5. 安全与隐私

- 不在日志、测试产物、截图或提交中写入密码、Cookie、配对 token、终端 attach token、API key 或私人正文。
- 真实服务器与测试账号必须由项目负责人提供并隔离；不得在文档中编造服务器地址、applicationId、签名信息或设备结果。
- HTTP / HTTPS、WS / WSS 按实际部署配置验证；不关闭 TLS 校验来规避证书问题。
- 远程客户端不得把局域网地址视为服务器本机。以服务端认证和权限响应为准。

## 6. 构建与验证

### 6.1 常用命令

P0-07 起仓库已有 Gradle 工程与 Wrapper。工具链固定：AGP 9.3.0（内置 Kotlin）、Compose 编译器插件 2.4.21、Compose BOM 2026.09.00、compileSdk/targetSdk 37、minSdk 26、JDK 17、Gradle Wrapper 9.5.1。本地与 CI 均用 `./gradlew`。

- 主检查（应用代码、构建配置或依赖变更时）：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- 单个测试：`./gradlew --no-daemon :app:testDebugUnitTest --tests "*ExampleUnitTest*"`
- 仪器测试（需已连接的模拟器或真机）：`./gradlew --no-daemon :app:connectedDebugAndroidTest`
- 纯文档变更：只检查 Markdown 相对链接与锚点、JSON / CSV 格式、清单计数、状态与事实源一致性，不运行 Gradle，也不得据此宣称构建通过。

### 6.2 验证纪律

- 构建工具链、插件与依赖版本必须固定；不得使用动态 `+` 版本。
- 工程与 CI 建立后，应用代码、构建配置和依赖变更运行受影响的 JVM 测试、Lint 和 Debug 构建；涉及界面、恢复或协议时运行对应检查。CI 结果与本地结果分别记录。
- 纯文档变更只检查 Markdown 相对链接与锚点、JSON / CSV 格式、清单计数、状态与事实源一致性；不运行 Gradle 或 APK 构建。P0 工具链核对、源码盘点等任务按自身退出条件验证；Wrapper、工程或 CI 尚不存在时标记相关检查“不适用（尚未建立）”，不能因此阻止独立文档任务完成，也不能宣称构建或 CI 通过。
- 真实服务器往返、模拟器仪器测试、真机测试、构建成功和发布是不同验证等级，分别记录，不得互相替代。
- 失败需读取具体日志、定位原因、修复并重新验证。没有执行的测试明确标为“未执行”。

## 7. 完成标准

业务功能只有在对应矩阵行的实现、错误与权限路径、必要自动测试、双端对照和服务端读回证据齐备后才能标记完成。单纯页面可见、HTTP 200、APK 可安装或 mock 通过均不足以证明完整流程通过。

P0准备、工程基础和纯文档任务按各自交付物与退出条件判断，不套用尚不存在的业务流程验收。文档检查通过只完成文档任务，不推进业务矩阵或 P1关卡。
