# P4-A 第三切片（舞台快照 / 行动选项 / 状态台账）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 舞台快照投影、行动选项与状态台账`）

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 快照加载与窗口语义 | 通过 | 38 轮故事：`snapshot turns=10 total=37 start=27 actors=10 world=5 changes=10 choices=5 stateStatus=ready`；9 轮故事：`turns=9 total=9 start=0 actors=4 world=0 changes=6 choices=8` |
| 行动选项展示 | 通过 | 输入框上方 chips（9 轮故事 8 个、38 轮故事 5 个） |
| **点击选项只填入、不提交（GAM-005）** | 通过 | 点击后输入框出现选项全文，`logcat` 无 `send story=` 记录、无新快照 |
| 选项可编辑后提交（GAM-005） | 通过 | 末尾追加 ` go` → 发送 `send story=st_dmc2q… cmd=8c57c0b9…`，首帧 2883ms |
| 角色台账（GAM-006） | 通过 | 右抽屉「角色」tab：4 / 10 个角色、模板字段名与顺序（位阶 / 关系 / 基本身份 / 外貌与装扮 / 姓名 / 当前处境 / 性格与背景 / 技能与能力 / 重要物品） |
| 世界事实与世界侧空态（GAM-006） | 通过（空态） | 该部署 `state` 的世界侧键（`events` / `on_stage` / `rules` / `threads` / `world_flags`）为空，过滤后显示「状态里还没有世界事实」 |
| 本轮变更摘要（GAM-006） | 通过 | `· 故事状态.当前时间: 帝国深秋·暴雪渐歇黎明破晓时分`、`· 维丝娜·纯欲.对主角好感度: 85`（角色字段用显示名，普通路径原样） |
| 以快照随事件更新（GAM-006） | 通过 | 发送一轮后 `total=37→38`、`start=27→28`、`choices` 换成新一轮；顶部行同步为 `38 轮 · 10-27 15:50` |

## 2. 关键日志

```
15:47:35 snapshot turns=10 total=37 start=27 actors=10 world=5 changes=10 choices=5 stateStatus=ready
15:48:59 send story=st_dmc2p6amro52de34e38f cmd=8a412619-…
15:50:53 snapshot turns=10 total=38 start=28 actors=10 world=5 changes=10 choices=5 stateStatus=ready
16:09:34 snapshot turns=9 total=9 start=0 actors=4 world=0 changes=6 choices=8 stateStatus=ready
```

`start=27` 即快照窗口之前被省略的 27 轮（`has_earlier_turns=true`），与 `/history` 的分页游标同源（GAM-007 将在下一片接上）。

## 3. 真机发现并修复的四点

1. **挂接时快照请求重复**：`recover` 在无活动任务时立刻结束，`finishStream` 又触发一次快照 → 每次挂接 2 次 `GET /snapshot`。
   改为：有轮次落库时只重取列表（`loadStories`），无落库且确实跑过流时才补快照。复测挂接仅 1 次。
2. **运行中/暂停中仍显示上一轮选项**：会误导用户点击过期选项。改为 `phase != RUNNING && phase != SUSPENDED` 才展示（与 Web 一致）。
3. **切故事取消发送会吞掉 `CancellationException`**：取消被 `catch (Exception)` 捕获，记 UNCERTAIN 并写错误提示（与 AGT-011 同类问题）。改为先重抛取消。
4. **世界侧空骨架键显示一排「—」**：`events` / `rules` / `threads` 等以空数组/空对象存在，逐行渲染 `—` 让人误以为有值读不出。
   改为过滤空值（`null`/空串/空数组/空对象），全空时显示空态文案；`world` 计数由 5 降为 0。

## 4. 未覆盖 / 后续

- **世界侧实际数据**：该部署三个故事的 `state` 世界侧均为空骨架，地点 / 物品 / 事件实体未出现；空态已验，实体渲染待有数据的快照复核（P5 全量对照）。
- `state_status=pending` 与 `failed` 分支未在真机构造（补取重试逻辑已实现，单测未覆盖）。
- 快照窗口之前的翻页（GAM-007）、分支切换与 `graph`（GAM-008）、分支方案（GAM-009）属 A4。
- 第二次发送（编辑后提交）后服务端任务超过 6 分钟未收尾（LLM 侧无输出），其落库刷新未观察到；同路径已由第一次发送证明。
- 因此 **GAM-005 回写 `已完成`，GAM-006 维持 `未开始`**（缺世界侧实体数据与 pending/failed 分支）。
