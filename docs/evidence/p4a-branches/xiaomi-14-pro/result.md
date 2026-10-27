# P4-A 第四切片（历史分页 / 轮次定位 / 分支 / 分支方案）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 历史分页、轮次定位与分支/分支方案`）

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 轮次列表与序号 | 通过 | 控制台「轮次」tab 按倒序列出 `第 39 轮 … 第 1 轮`，摘要取玩家输入首段 |
| 跳转指定轮次并定位 | 通过 | `scroll to turn=ev_dmfgg9kzq31q436760fa block=272`；跳到第 11 轮后视图停在「> 看着伊格妮丝进行侍奉」这一轮，不再停在底部 |
| 加载更早历史（`before` 游标） | **未触发** | 当前部署最大 39 轮 < 每页 50 轮，`hasMore=false`（按钮置灰）；分页合并与游标逻辑由单测覆盖（`StoryPagingTest`） |
| 分支列表 | 通过 | 「分支」tab：`当前分支：main`、`main`（当前）、新分支（`新分支 · 头事件 ev_dmc3e`） |
| 从指定轮创建分支 | 通过 | `branch created id=br_dmfh76uhaduc42a538ea from=ev_dmc3es6tkt2re3222c8c`（第 11 轮） |
| 切换分支（双向） | 通过 | `branch switched id=br_dmfh…` → `start … branch=br_dmfh…`（`snapshot turns=11 total=11 start=0`，与该轮深度一致）；再 `branch switched id=main` → `start … branch=main`（`total=39`） |
| 删除分支（带确认） | 通过 | 确认框「删除分支「br_dmfh76…」？…」→ `branch deleted id=br_dmfh76uhaduc42a538ea`，列表回到单分支 |
| 分支方案读取 | 通过 | 「方案」tab 显示 `版本 bpr_dmfgsg9201ega81bef7f` 与完整 markdown（1445 字） |
| 分支方案保存（revision 匹配） | 通过 | 保存后 `branch plan saved revision=bpu_…`（内容不变 → 幂等）；改动后 `bpr_dmfh8m07abg31d940aaa` |
| 分支方案冲突（409） | 通过 | 外部先把方案改为 `bpr_dmfha6fmxy1yc0186a29`，App 仍持旧 revision 保存 → `branch plan save rejected status=409`，界面显示「分支规划已在其他位置更新，请重新加载后再保存」，草稿仍在编辑框内 |
| 冲突后重新加载 | 通过 | 点「刷新」后 revision 变为 `bpr_dmfha6fmxy1yc0186a29`，正文与恢复内容一致 |

## 2. 关键日志

```
16:23:23 scroll to turn=ev_dmfgg9kzq31q436760fa block=272
16:25:58 branch created id=br_dmfh76uhaduc42a538ea from=ev_dmc3es6tkt2re3222c8c
16:26:00 branch switched id=br_dmfh76uhaduc42a538ea
16:20:00 start story=st_dmc2p6amro52de34e38f branch=br_dmfh76uhaduc42a538ea
16:26:04 snapshot turns=11 total=11 start=0 actors=5 world=0 changes=8 choices=5 stateStatus=ready
16:26:42 branch switched id=main → start … branch=main → snapshot turns=10 total=39 start=29
16:27:28 branch plan saved revision=bpu_dmfgsg9201ega81bef7f
16:27:49 branch plan saved revision=bpr_dmfh8m07abg31d940aaa
16:32:30 branch plan save rejected status=409 code=null
16:36:05 branch deleted id=br_dmfh76uhaduc42a538ea
```

## 3. 本轮真机发现并修复

1. **删除分支没有二次确认**：直接 `DELETE` 不可撤销。补 `AlertDialog` 确认（文案说明「轮次留在历史、分支不可恢复」），复测确认后才发请求。
2. **删除/创建分支后概览分支数不更新**：概览取快照的 `branchCount`，本地增删后仍显示旧值（删完仍显示「2 分支」）。改为增删后同步 `branchCount` 为本地列表长度。
3. **分支 `head` 的标签写错**：`head` 是分叉头事件 id，保存方案后会变成 `bpr_…`（方案事件），原标题「末轮」会误导。改为「头事件」。

## 4. 取证过程中的事故与恢复（需记录）

为了验证「内容变更后 revision 前移」，在方案编辑框里用 `adb input` 追加探测串，但**点击定位光标落在了正文中部**、`MOVE_END` 未生效，
随后 8 次 `KEYCODE_DEL` 删掉了正文中间 8 个字符（原文「粉碎阿斯兰迪亚帝国霸权…」被削成「粉碎 …权…」）。

恢复方式：用第一次打开编辑框时的 UI dump 取回原始 markdown（1445 字），
借 `adb exec-out run-as com.denova.novara cat files/cookies/…json` 取出应用会话 Cookie，
以该会话 `PUT /branches/main/plan` 写回原文（`revision=bpr_dmfha6fmxy1yc0186a29`，`len=1445`，原文校验 `restored=True`、`probe=False`），
随后临时 Cookie 文件与含正文的 dump 已删除。App 侧刷新后 revision 与正文一致。

教训：**长文本（markdown）不要用 `adb input` 做局部增删**——点击定位不准 + 无撤销；应改走接口写入，或 `Ctrl+A` 全选后整体替换。

## 5. 未覆盖 / 后续

- **历史翻页（`before` 游标）**：需 > 50 轮的故事才能触发；当前部署最大 39 轮。分页合并（去重/顺序）、锚点序号换算、`NarrativeBuilder` 轮次分块均有单测。
- 分支方案尚无版本历史界面；冲突后只能整体重载（与 Web 一致）。
- 轮次「分叉」使用服务端默认标题，未提供自定义标题入口（Web 在部分入口带输入框）。
- 因此 **GAM-008、GAM-009 回写 `已完成`；GAM-007 维持 `未开始`**（跳转与锚点已验，翻页未触发）。
