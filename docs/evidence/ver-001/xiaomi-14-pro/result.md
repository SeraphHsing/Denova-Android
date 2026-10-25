# VER-001 版本创建与列表 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug；服务器 `http://192.168.2.101:18098`
- 项目：`project-efb2ed8ad64c839df4b72c3a69772350`（灵渊劫-问道），原有 20 个版本（`timer` 自动版本）
- 时间：2026-10-25 23:58–2026-10-26 00:10
- 只读探针：`GET /versions/status`、`GET /versions?limit=8`（凭证只从本机记忆读取，不落仓库、不回显）

## 1. 入口与状态

右侧抽屉「版本」页显示：

- 状态行：`自上个版本以来工作区无改动`（`status.clean=true`）
- 最近版本：`自动版本：2026-10-25 03:07`（`status.latest.message`）
- 自动版本配置：`每 10 分钟自动创建`
- 列表：20 条，每条 `created_at · source · N 个文件 · N 字节`（`versions_files` 文案），即矩阵要求的「记录时间范围对应」
- 顶部新增：说明输入框（`版本说明（可留空）`）+「创建版本」按钮

## 2. 无变更时的拒绝路径

工作区干净时点「创建版本」：

- 面板出现服务端原话 `当前工作区没有可保存的变更`（后端 `ErrVersionClean`），输入内容保留，按钮从「创建中…」回到「创建版本」
- 不产生新版本：探针 `status clean=True changes=0`，历史条数不变

## 3. 创建成功路径

先恢复一个旧版本，让工作区产生 1 处改动（见 `docs/evidence/ver-002`），再创建：

```
Versions: created project=project-efb2ed8ad64c839df4b72c3a69772350 version=b4445315752b message=版本已保存
```

服务端读回：

- `status clean=True changes=0`（创建后工作区重新干净）
- 历史首行即为该 manual 版本（`ver b4445315752b manual 2026-10-26T00:07:33+08:00 …`）
- 列表随创建自动刷新（`load(force=true)`），无需手动点「刷新」

**输入残留缺陷**：真机上创建前输入框里还留着上次失败的 `e2e`，新输入被追加成 `e2ee2e-manual`，服务端按原样存了这个说明。功能正确、数据无害，但说明文字被拼脏；已在本轮修复「动作开始时清掉上一次的提示」，输入框本身的清空逻辑保持不变（失败时保留输入是刻意的，便于重试）。

## 4. 验证

- JVM：`VersionRestoreTest`（创建结果解析、diff 变更与文件正文、恢复计划字段、恢复结果、恢复范围判定）。
- 主检查：`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 全绿。
- 真机：§1–§3 在本机完成；仪器测试未执行。

## 5. 残留

- 服务端没有删除版本的接口（`routes.go` 只有 status / 历史 / 创建 / diff / restore-plan / restore），误建的 manual 版本只能等自动版本清理策略淘汰，客户端不提供删除入口。
- 自动版本配置（开关 / 间隔 / 保留数）本轮只读展示，不是 VER-001 的写入口。
