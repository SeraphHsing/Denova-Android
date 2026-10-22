# API 路由登记清单

完整原始清单以 gzip 压缩 CSV 保留：[`api-inventory.full.csv.gz`](api-inventory.full.csv.gz)。

- 原始记录数：277 条静态 API 注册记录。
- 原始列：`method`、`path`、`scope`、`registered_gate`、`handler`、`source_path`、`source_line`、`verification_status`。
- 来源：固定基线 `SeraphHsing/denova@671ef4eb609523f07d1c234cdcd242d63ce12652`。
- 状态边界：清单记录静态路由注册；不等于 handler 的全部授权条件、DTO、调用位置或运行结果已经核实。

## 解压

macOS / Linux：

```sh
gzip -dk api-inventory.full.csv.gz
```

PowerShell（需系统可用的 gzip）：

```powershell
gzip -dk .\api-inventory.full.csv.gz
```

接口族和请求契约说明见 [03 接口、状态与恢复契约](03-api-and-state.md)。
