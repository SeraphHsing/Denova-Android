# Denova Android

Denova Android 是面向 Denova 手机 Web 工作区的原生 Android 客户端项目。目标是以 Kotlin 与 Jetpack Compose 实现固定 Web 基线中的页面、操作、业务状态和能力边界，并通过现有服务器接口工作。

**后端代码零修改。** 本仓库从独立 Android 工程开始；完整范围、协议和验收以文档及矩阵为准。

## 阅读顺序

1. [AGENTS.md](AGENTS.md) — 开发约束与协作规则
2. [docs/STATUS.md](docs/STATUS.md) — 当前进度、未验证事项与下一步
3. [文档索引](docs/README.md) — 规格、矩阵和基线入口
4. [开发方案总览](docs/android-web-parity/README.md) — 产品范围和整体路线
5. 按索引继续阅读各规格与验收矩阵

## 项目状态

P0 已完成并退出；P1 六个高风险原型均已出真机结论——G4 / G5 判据齐备，G6 通过（范围受限 SD-01），G1 / G2 / G3 仍各缺退出条件项。复刻矩阵按「P0–P1 已实现并验证」回写 6 条 `已完成`（WRI-004 / WRI-010 / AGT-002 / WB-004 / WB-007 / WB-009），其余 112 条 `未开始`，口径见 [01 §8](docs/android-web-parity/01-product-and-parity.md#8-全量完成标准)。目标形态的公共界面与业务页面尚未实现。各阶段必须以可复现的测试结果和证据更新状态，源码静态核对结果不代表真实服务器链路已通过。

## 主要文档

- [当前状态](docs/STATUS.md)
- [规格与矩阵索引](docs/README.md)
- [复刻矩阵（CSV，首批118场景）](docs/android-web-parity/parity-matrix.csv)
- [API 路由登记说明](docs/android-web-parity/api-inventory.md)
- [完整 API 路由清单（CSV）](docs/android-web-parity/api-inventory.csv)
- [游戏流事件契约（CSV）](docs/android-web-parity/game-event-contract.csv)
- [固定基线元数据](docs/android-web-parity/baseline.json)
