# G2 Markdown 编辑器：模式往返真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 目标文件：`project-b47902c162fffb7983a95ab73cae37d0` / `ideas.md`（390 字符，revision `sha256:459b244d…`）
- 对应矩阵：`WRI-004`（富文本源码往返）

## 目的

`WRI-004` 此前只有 `STATUS.md` 的文字记录，没有独立证据文件；本轮按「回写必须有真实证据文件」的要求，用真机重新执行一遍模式往返。

## 步骤与结果

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 编辑器输入项目 ID + `ideas.md`，点「打开」 | `Editor: opened project=… path=ideas.md revision=sha256:459b244d…` |
| 2 | 点「富文本」 | 正文按内联样式渲染；`**`/`*`/`` ` ``/`~~` 标记被隐藏，工具栏 B / I / `</>` / S 出现 |
| 3 | 点「预览」 | 切换为 WebView Markdown 渲染视图 |
| 4 | 点「源码」 | 回到带标记的原始 Markdown，内容与打开时一致 |

`adb logcat -s Editor:I` 摘录：

```text
I Editor  : opened project=project-b47902c162fffb7983a95ab73cae37d0 path=ideas.md revision=sha256:459b244dc0ad18ed0571e45c1f0754303ccaef5bb61ccf144da0b6d10c4e8b79
D EditorScreen: sync: loaded=true contentLen=390
```

## 覆盖范围与限制

- 覆盖：三模式切换、富文本内联样式渲染、切换后内容不丢失（返回源码模式内容一致）。
- **未覆盖**：标题（`#`）等块级标记仍以原字符显示（当前实现只隐藏内联标记）；表格与图片节点编辑；中文 IME 组合态；选区增强。这些属于该矩阵行后续阶段的范围，已写入矩阵 `gap_or_decision`。
- 截图（`g2-source.png` / `g2-rich.png` / `g2-preview.png`）含项目正文，只保留在本地取证目录，不提交入库。
