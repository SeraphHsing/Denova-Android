# G3 保存链路：自动保存与读回真机记录

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建）
- 服务器：`http://192.168.2.102:18081`（0.6.3 @ `85ec97c`）
- 目标文件：`project-b47902c162fffb7983a95ab73cae37d0` / `ideas.md`
- 对应矩阵：`WRI-010`（自动保存）、`WB-004`（文件打开编辑保存）

## 完整往返（一次编辑 + 一次恢复）

| 阶段 | 客户端日志 / 读回结果 |
|---|---|
| 打开 | `opened … revision=sha256:459b244dc0ad18ed0571e45c1f0754303ccaef5bb61ccf144da0b6d10c4e8b79`，正文 390 字符 |
| 编辑 | 在正文插入 10 个 ASCII 字符，停止输入后由 800ms 防抖触发保存 |
| 自动保存 | `saved path=ideas.md revision=sha256:9773c6f63742f139a0b5d31159870c7eb960df5d7409f741dbaa6a2147200cb2` |
| 服务端读回 | `GET /api/projects/:id/files/file?path=ideas.md` → `len=400`，revision `sha256:9773c6f63…` 与客户端一致，插入内容存在 |
| 恢复编辑 | 删除插入的 10 个字符，再次防抖保存 |
| 再次读回 | `saved … revision=sha256:459b244dc0ad18ed0571e45c1f0754303ccaef5bb61ccf144da0b6d10c4e8b79`，内容与原始完全一致（revision 回到原值） |

`adb logcat -s Editor:I` 摘录：

```text
I Editor  : opened project=project-b47902c162fffb7983a95ab73cae37d0 path=ideas.md revision=sha256:459b244d…
I Editor  : saved path=ideas.md revision=sha256:459b244d…   # 切模式时的一次幂等保存
I Editor  : saved path=ideas.md revision=sha256:9773c6f6…   # 插入后自动保存
I Editor  : saved path=ideas.md revision=sha256:459b244d…   # 删除后恢复原内容
```

## 覆盖范围与限制

- 覆盖：revision 校验下的打开、防抖自动保存、服务端读回一致、编辑可逆恢复。
- **未覆盖**：Web 与 App 双端并发触发的 409 → 三方合并（目前只有 `ThreeWayMerge`/`FileSaveCoordinator` 单测）、迟到回包、草稿事务提交前后终止进程。这些是 `G3` 关卡退出条件的剩余项。
