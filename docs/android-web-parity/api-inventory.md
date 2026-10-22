# API 路由登记清单

完整、可直接阅读的权威清单位于 [api-inventory.csv](api-inventory.csv)。压缩归档 [api-inventory.full.csv.gz](api-inventory.full.csv.gz) 保留相同的完整数据；日常开发以 CSV 为准，不需要先解压，也不维护独立摘要版。

- 原始记录数：277 条静态 API 注册记录。
- 原始列：`method`、`path`、`scope`、`registered_gate`、`handler`、`source_path`、`source_line`、`verification_status`。
- 来源：固定基线 `SeraphHsing/denova@671ef4eb609523f07d1c234cdcd242d63ce12652`。
- 状态边界：清单记录静态路由注册；不等于 handler 的全部授权条件、DTO、调用位置或运行结果已经核实。

接口族和请求契约说明见 [03 接口、状态与恢复契约](03-api-and-state.md)。
