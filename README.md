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

目前仓库处于文档规划与 P0 基线冻结阶段。文档中的源码静态核对结果不代表 Web 已运行、Android 已构建或真实服务器链路已通过。各阶段必须以可复现的测试结果和证据更新状态。

## 主要文档

- [当前状态](docs/STATUS.md)
- [规格与矩阵索引](docs/README.md)
- [复刻矩阵（CSV）](docs/android-web-parity/parity-matrix.csv)
- [API 路由登记说明](docs/android-web-parity/api-inventory.md)
- [完整 API 路由 CSV（gzip）](docs/android-web-parity/api-inventory.full.csv.gz)
- [完整复刻矩阵 CSV（gzip）](docs/android-web-parity/parity-matrix.full.csv.gz)
- [游戏流事件契约（CSV）](docs/android-web-parity/game-event-contract.csv)
- [固定基线元数据](docs/android-web-parity/baseline.json)
