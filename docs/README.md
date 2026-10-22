# 文档索引

按以下顺序阅读，避免把规划、源码静态核对和运行结果混为一谈。

## 1. 项目规则与当前状态

1. [`AGENTS.md`](../AGENTS.md) — 开发边界、工作流程、安全与完成定义。
2. [`STATUS.md`](STATUS.md) — 当前阶段、已完成事项、待验证项和下一步。
3. [本索引](README.md) — 规格、矩阵、证据和固定基线的导航。

## 2. 规格文档

完整开发方案位于 [`android-web-parity/`](android-web-parity/README.md)，按编号阅读：

| 顺序 | 文档 | 内容 |
|---|---|---|
| 01 | [需求与复刻规格](android-web-parity/01-product-and-parity.md) | 功能范围、导航、状态、操作契约、权限和 1:1 完成标准 |
| 02 | [技术架构](android-web-parity/02-architecture.md) | Kotlin / Compose 架构、身份、状态所有权、生命周期和安全 |
| 03 | [接口、状态与恢复契约](android-web-parity/03-api-and-state.md) | 认证、接口族、请求形状、UIMessage、Game SSE、保存与终端 |
| 04 | [界面与编辑器规范](android-web-parity/04-ui-and-editor.md) | 尺寸映射、组件、抽屉、IME、Markdown、消息和终端 |
| 05 | [开发路线与阶段验收](android-web-parity/05-roadmap.md) | P0–P6、G1–G6、依赖、任务包和退出条件 |
| 06 | [开发、测试与交付](android-web-parity/06-development-and-validation.md) | 测试数据、恢复组合、双端对照、CI、APK 与发布候选 |
| 07 | [证据、决策与待验证事项](android-web-parity/07-evidence-and-decisions.md) | 固定源码索引、ADR、风险、平台差异和官方依据 |

## 3. 验收矩阵与数据清单

| 文件 | 用途 | 当前边界 |
|---|---|---|
| [复刻矩阵（CSV，首批118场景）](android-web-parity/parity-matrix.csv) | 唯一维护的场景矩阵，保留步骤、预期 UI、服务端副作用和验收信息 | 118 条场景仍需在 P0 扩展全站盘点，尚未冻结完整范围 |
| [完整 API 路由清单（CSV）](android-web-parity/api-inventory.csv) | 完整可读的静态 API 注册记录 | 277 条注册记录；不等于完整 DTO / 权限审计 |
| [API 清单说明](android-web-parity/api-inventory.md) | 来源、字段、边界与核验方式 | 配套说明文档 |
| [游戏事件契约](android-web-parity/game-event-contract.csv) | 游戏 SSE 事件及 Web handled / ignored 分类 | 35 类事件；实现和测试尚未开始 |
| [基线元数据](android-web-parity/baseline.json) | 固定提交、源文件 blob SHA、盘点计数和验证状态 | Web 运行、测试、Android 构建和真机均未执行 |

## 4. 开发执行建议

- 业务实现先从矩阵 ID 和对应规格出发，再回到固定 Web 源码确认实际行为；P0、工程准备和纯文档任务按 `AGENTS.md` 使用对应任务编号、ADR或规格章节。
- 接口文档、页面实现与验收证据必须保持双向可追踪。
- 先完成 P0 基线与盘点，再完成 P1 的认证、编辑器、保存并发、双流恢复和终端原型。
- 任何平台差异必须记录证据、用户影响与决定；不得以“系统不同”作为未实现功能的默认豁免。
- 项目状态以 [`STATUS.md`](STATUS.md) 和实际 CI / 测试证据为准。
