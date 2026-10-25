# G2-c 表格行列操作与素材插入 · 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 应用：`com.denova.novara` debug（含本轮 `MarkdownTable` 与素材选择）
- 服务器 / 项目：`http://192.168.2.102:18081` / `project-efb2ed8ad64c839df4b72c3a69772350`
- 夹具：`g2c-table.md`（`# 表格操作测试` + 2×2 表 + `结束行`），验证后删除
- 时间：2026-10-26 01:41–01:44

## 1. 行列操作（WRI-006）

正文是 Markdown 源文本，因此把 TipTap 的表格节点操作实现为对源文本的确定性改写（`core/editor/MarkdownTable.kt`，10 个 JVM 用例）。
真机流程：`富文本` → 光标点进表格 → 工具栏第二行下方出现 `行+ 行− 列+ 列− 删表`（`table-ops-row.png`）。

| 操作 | 服务端读回（revision） | 结果 |
|---|---|---|
| 点 `行+`（光标在表头行） | `dbfe977a` | 插入一行空行，列数与表头一致：`|  |  |` |
| 点 `列+` | `a88fa1ec` | 三行同时补一列，分隔行补 `---`：`| A |  | B |` / `| --- | --- | --- |` |
| 点 `列−` | `9efa7aa9` | 删除光标所在列（列 0），其余列保持：`|  | B |` / `|  | 2 |` |

- 光标移出表格后该行工具条自动消失（`行+` 不再出现在 dump 里），说明「光标是否在表格内」判定生效。
- 每次操作后正文立即交给会话走自动保存，服务端 revision 连续前进，无冲突。
- `Tab` 在单元格间跳转（末格追加一行）由 `MarkdownTableTest` 覆盖；真机键盘未单独构造。

## 2. 从项目素材插入图片（WRI-007）

`图` 按钮改为先列项目 `assets/` 下的图片（`GET /api/projects/:id/files/resolve`，recursive），选中后按「当前文档目录」写成相对路径插入：

```
弹窗标题「选择项目素材」，列表项 assets/redpanda.png
点选后正文插入：![redpanda.png](assets/redpanda.png)
服务端读回 revision 5ce5b36a…（93 字节）
```

插入位置就是光标处（本次光标停在标题行内，插入后标题被拆成 `# ` + 图片行 + 正文，属于「在光标处插入块」的正常语义）。
路径换算与预览侧 `resolveAssetPath` 互为逆运算，双向往返有单测（`relative path for inserted image round-trips through resolve`）。

**没有实现「上传」**：固定基线与后端都没有文档图片上传入口（见 `07` SD-02），客户端不为此新增接口。

## 3. 验证

- JVM：`MarkdownTableTest` 10 例（定位、插行、删行、插列、删列、删表、Tab 跳格、单元格索引边界）＋ `EditorNodeSupportTest` 相对路径往返。
- 主检查：`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 全绿（54 套件 / 372 用例）。
- 真机：§1–§2 均在本机完成；夹具与截图以外的服务端内容已复原（夹具文件已删除）。
- 仪器测试：未执行。

## 4. 残留

- 合并单元格、列宽拖拽未做（基线 `TableKit.configure({ table: { resizable: false } })` 本身不开列宽拖拽）。
- 宽表横向滚动仍只有 CSS + 单测证据，缺真机宽表截图。
- 图片只能引用项目内既有素材（SD-02），无相册上传。
