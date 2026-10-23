# API 路由登记清单

完整、可直接阅读的权威清单位于 [api-inventory.csv](api-inventory.csv)。该 CSV 是唯一维护版本；本文件只说明来源、字段与验证边界。

- 原始记录数：276 条静态 API 注册记录（按 85ec97c 重提取；671ef4eb 为 272 条）。
- 原始列：`method`、`path`、`scope`、`registered_gate`、`handler`、`source_path`、`source_line`、`verification_status`。
- 来源：固定基线 `SeraphHsing/denova@85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e`。
- 状态边界：清单记录静态路由注册；不等于 handler 的全部授权条件、DTO、调用位置或运行结果已经核实。

接口族和请求契约说明见 [03 接口、状态与恢复契约](03-api-and-state.md)。
