# 项目状态

**更新日期：** 2026-10-23  
**阶段：** P0 已完成并退出（GitHub Actions CI 全绿：testDebugUnitTest / lintDebug / assembleDebug + APK 工件）；进入 P1/G1。  
**产品实现状态：** 已建立最小 Android 工程与空白 Compose 首屏（Novara）；业务功能尚未实现。

## 当前基线

- Android 目标：Kotlin + Jetpack Compose 原生客户端，最低支持版本规划为 Android 8.0 / API 26。
- 应用身份（P0-01 已定）：应用名 **Novara**，applicationId **`com.denova.novara`**；签名归属 = 项目负责人个人持有（自持 release keystore，私钥离线备份、不进仓库，CI 用加密 secret 注入）；分发渠道 = 纯侧载 / 内部测试。
- 后端边界：不修改 Denova 后端、不新增服务端接口，使用既有认证、REST、Agent UIMessage、游戏 SSE 和终端 WebSocket 协议。
- Web 源码基线：[`SeraphHsing/denova@85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e`](https://github.com/SeraphHsing/denova/tree/85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e)。
- 测试服务器基线（P0-02 部分确定）：地址 `http://192.168.2.102:18081`，后端版本 `0.6.3`，commit `85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e`（版本号已重置对齐上游）；隔离测试账号已提供（凭证本地保存、不进仓库），数据快照与复现步骤待补。
- 文档来源：`Denova-Android-开发规划-2026-10-22.zip`，规划日期为 2026-10-22。

## 已完成

- 已整理项目级协作约束和文档阅读顺序。
- 已建立规格索引、项目状态入口和 Android 开发方案文档目录。
- 已从固定 Web 提交静态核对并登记首批资料：118 条复刻场景、281 条 API 注册记录（85ec97c）、35 类游戏流事件（25 类 handled、10 类 ignored）。
- 已记录架构决策、风险、证据边界和阶段退出条件。
- 2026-10-23 已补充命令与草稿的本地事务边界、统一 G6 退出条件、加入性能基准冻结记录，并同步 P0 / 纯文档检查规则；失效归档引用已清理，链接、锚点与结构化清单已静态核对。
- 2026-10-23 已在 `AGENTS.md` 登记：2.1 架构与事实来源摘要（后端零改动与固定基线、协议权威来源、稳定身份 key、三套协议、P0–P6 / G1–G6 路线）、6.1 常用命令与工具链（本地固定 Gradle 9.5.1 与 CI 对齐；当前无 Gradle 工程 / Wrapper / CI，命令在 P0-07 后适用）、6.2 验证纪律、变更流程中的分支前缀与 Conventional Commits 约定。本次为纯文档维护，未改动业务矩阵行或实现状态。
- 2026-10-23 完成 P0-03 首轮静态盘点：新增 [08 页面/表面全量清单](android-web-parity/08-surface-inventory.md)，从后端 `web/src` 与 `internal/api/routes.go` 枚举 14 个顶层表面、子表面、条件入口与 API 族映射；Web 基线由 671ef4eb 升级至 85ec97c（仅 AGENTS.md / routes.go / settings-sections.ts 三文件变化，routes.go 净增 4 条项目作用域 interactive/stories 路由）；api-inventory.csv 已按 85ec97c 重提取为 281 条（routes.go 276 + remote_access.go 认证 5；671ef4eb 为 277 条），parity-matrix 源文件未变。逐字段展开矩阵待续。
- 2026-10-23 完成 P0-04 API 契约 handler 级核对：`03-api-and-state.md` 新增 §2 认证精确契约、§4.1 写作 surfaces、§4.2 设置/模型/对话配置、§4.3 长尾族、§5.3 Agent 聊天+游戏流（含 SSE 两套帧格式）、§9 终端 WS 帧，覆盖 G1 认证、files/book/versions/changes、Agent/游戏流、设置模型、终端及 books/skills/automations/messages/market/lore/styles/images/update 长尾族。
- 2026-10-23 完成 P0-05/06 性能冻结：`06-development-and-validation.md` D03 冻结可重现输入（D03-W 文档 / D03-A 消息 / D03-S 流 / D03-P PTY 数据规模）、最低设备（模拟器 Pixel 6 / API 33 / 8GB）与测量方法；G2/G4/G5 数值预算按用户决定全部待实测，不编造。
- 2026-10-23 完成 P0-07 工程建立：固定工具链（AGP 9.3.0 内置 Kotlin + Compose 编译器插件 2.4.21、Gradle Wrapper 9.5.1、Compose BOM 2026.09.00、compileSdk/targetSdk 37、minSdk 26、JDK 17），创建单 `app` 模块 + Version Catalog + `gradle/libs.versions.toml` + Compose 空白首屏 + 1 个 JVM 单测 + `.github/workflows/android.yml`；本地 `testDebugUnitTest`/`lintDebug`/`assembleDebug` 全绿（debug APK 11MB）。`AGENTS.md` §6.1 同步为 `./gradlew`。
- 2026-10-23 完成 G1 首轮（连接与认证原型）：`ServerKey` 连接身份规范化、`DenovaClient`(OkHttp + 内存 CookieJar 按服务器隔离 + 错误映射 + 共享 `okHttpClient` 供图片/SSE/WS)、status/login/pair/logout、`ConnectionStore`(DataStore 持久化服务器地址)、`ConnectionScreen`(服务器输入/登录/配对/状态/退出)；本地 + CI 全绿；真机（小米14 Pro / Android 16）对 `http://192.168.2.102:18081` 完成连接 + status + 登录 + 退出真实往返，DataStore 重启后服务器地址持久化验证通过。
- 2026-10-23 完成 G2 首轮（Markdown 编辑器）：`MarkdownDocument`(commonmark 0.30.0 + GFM 表格扩展，parse/toHtml/toPlainText) + 8 个 JVM 序列化测试（标题/加粗斜体/列表/代码/引用/图片/表格/纯文本）；`EditorScreen` 源码模式 + WebView 预览双模式切换，认证后路由进入；修复 Android 15+ edge-to-edge 顶栏被状态栏遮挡问题（两屏均加 statusBars inset）。真机验证：登录后进入编辑器、源码模式正常、顶栏按钮手指可点（adb 注入触摸在顶部区域被设备悬浮层拦截，属设备环境非应用问题）。

以上数字来自规划包的静态提取结果，不代表完整盘点已结束或运行测试已通过。

## 未完成 / 尚未验证

| 项目 | 当前状态 | 退出条件 |
|---|---|---|
| Web 与服务端运行基线 | 部分完成：已登记 `http://192.168.2.102:18081`、版本 0.6.3、commit `85ec97c`、隔离账号（本地保存）；Web 基线已升级至 `85ec97c`（与服务端一致） | 补数据快照与复现步骤 |
| 全量页面与字段盘点 | 部分完成；118 条为首批场景 | 展开每个页面、控件、字段、状态、权限与条件入口并冻结范围 |
| API 契约 | 281 条已按 85ec97c 重提取；handler→DTO 契约已按族核对（认证/写作/Agent流/游戏流/设置模型/终端/长尾族） | 逐字段嵌套 DTO 与双端读回验证在 P1 按需展开 |
| Android 工程 | 已创建；本地 + CI `testDebugUnitTest`/`lintDebug`/`assembleDebug` 全绿（APK 11MB） | 固定 JDK17、AGP 9.3.0（内置 Kotlin）、Gradle Wrapper 9.5.1、Compose 编译器插件 2.4.21、Compose BOM 2026.09.00、compileSdk/targetSdk 37、minSdk 26；CI 构建通过 |
| G1 认证与网络 | 完成：连接 + status + 登录 + 退出 已真机真实往返通过；DataStore 服务器地址持久化完成 | 配对、受保护图片、SSE、WS、HTTPS/WSS、迟到 401 完成真实往返 |
| G2 Markdown 编辑器 | 两轮完成：序列化层（commonmark 0.30.0 + GFM 表格，8 测试）+ 源码/富文本/预览三模式；富文本 = 标记隐藏(零宽替换) + 内联样式 + 格式化工具栏，纯逻辑层 4 测试；本地 + CI 全绿；真机验证富文本渲染 | 图片/表格编辑、中文 IME、选区增强、D03 性能预算 |
| G3 保存并发 | 未开始 | 按[正式关卡](android-web-parity/05-roadmap.md#3-p1高风险原型先形成技术证据)验收 revision、草稿事务、进程恢复与并发冲突 |
| G4 Agent / Game 流恢复 | 未开始 | 按[正式关卡](android-web-parity/05-roadmap.md#3-p1高风险原型先形成技术证据)验收双流、命令持久化与发送边界、D03 性能预算 |
| G5 原生终端 | 未开始 | 按[正式关卡](android-web-parity/05-roadmap.md#3-p1高风险原型先形成技术证据)验收 ANSI / VT、输入、resize、owner 生命周期与 D03 性能预算 |
| G6 远程权限与双端共存 | 未开始 | 以[路线中的G6](android-web-parity/05-roadmap.md#3-p1高风险原型先形成技术证据)为准；误删对方PTY或丢失会话内容时未通过，仅记录限制不能退出 |
| CI 与 APK | 已配置并通过（GitHub Actions 全绿，APK 工件 `novara-debug-apk` 上传成功） | GitHub Actions 运行构建、测试、Lint 并保存报告与 APK 工件 |

## 下一步顺序

1. **P0-01 / P0-02：** P0-01 已定（应用名 Novara、applicationId `com.denova.novara`、签名个人持有、纯侧载/内部测试）；P0-02 已登记 `http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）与隔离账号，剩余补数据快照与复现步骤。
2. **P0-03 / P0-04：** 表面清单（08）与 API 契约（handler 级）已完成；剩余将 118 条首批场景逐字段展开为全量矩阵，并按需补齐嵌套 DTO。
3. **P0-05 / P0-06：** 已冻结性能数据规模、最低设备（固定模拟器 Pixel 6 / API 33 / 8GB）与测量方法；G2/G4/G5 数值预算全部待实测（P1 以 Web 基线 + Android 实测回填）；测试数据 D01-D10 与视觉宽度基准（320/390/430/768/1023）沿用 06 §4/§7。
4. **P0-07：** 已固定工具链（AGP 9.3.0 / Gradle Wrapper 9.5.1 / Kotlin 2.4.21 / Compose BOM 2026.09.00 / SDK 37）并创建最小 `app` 工程（Compose 空白首屏 + 1 个 JVM 单测 + `android.yml` CI）；本地全绿，待 CI 验证。
5. **P1 / G1：** 实现连接与认证原型，验证 Cookie、图片资源、SSE / WebSocket、迟到 401 与退出后的迟到回包。
6. **G2–G6：** 按路线图验证 Markdown、保存并发、双流恢复、终端及远程共存；高风险原型通过后再铺开页面。

## 状态记录规则

- “静态核对”只表示源码定义已阅读；“fixture 验证”只表示确定性样本通过；“真实服务器”“模拟器”“真机”分别登记。
- 只有执行了对应检查并保留结果，才能更新为通过。
- 业务实现提交关联矩阵 ID；P0准备、工程和规划任务可关联 P0任务 ID 或 ADR编号，纯文档维护注明规格章节。只有业务变化才更新对应矩阵状态；阶段、阻塞项或下一步变化时同步本文件。
- 纯文档任务执行链接、锚点、JSON / CSV及事实源一致性检查；不以尚未建立的Gradle工程或CI作为完成前置，也不将文档检查当作应用验证。
- 遇到后端契约缺口，先检查固定 Web 的实际能力；不得直接修改后端来满足 Android。
