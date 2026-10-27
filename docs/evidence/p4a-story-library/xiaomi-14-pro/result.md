# P4-A 第一切片（故事库 / 舞台入口）真机验证

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3，commit `85ec97c`）
- 应用：`com.denova.novara` debug 构建（含 `feat(game): 故事库列表切换与舞台入口列表化`）
- 登录态：Cookie 持久化，启动后 `Connect` 即 `authenticated=true`，无需重登

## 1. 覆盖范围与结论

| 场景 | 结论 | 证据 |
|---|---|---|
| 故事列表加载 | 通过 | `Game: stories loaded count=5 current=st_dmenvr92avexb464d1f0` |
| 进入游戏页自动挂接当前故事 | 通过 | `start story=st_dmenvr92… branch=main` → `canonical baseline loaded stage=fast turns=3` / `stage=full turns=9 len=27118` |
| 两个故事间切换并返回 | 通过 | 见 §2 |
| `POST .../select` 写服务端 index | 通过 | 切换后重启应用，`stories loaded … current=st_dmdd8q6gub6zf0dd1884`（上次选中的故事） |
| 改名（`PATCH /api/interactive/stories/:id`） | 通过 | `story renamed … title=TASK_RESULT_TMP` → 再改回 `title=Message Type: TASK_RESULT` |
| 删除确认对话框 | UI 通过（取消路径） | 标题 `Delete story` + `Delete` / `Cancel`；取消后回到列表，未发删除请求 |
| 删除真实往返 | **未执行** | 未擅自删除服务器既有故事；待 A2「故事创建」落地后用自建临时故事补测 |

## 2. 切换往返（GAM-002 核心链路）

```
09:40:40 story selected id=st_dmc2p6amro52de34e38f
09:40:40 start story=st_dmc2p6amro52de34e38f branch=main
09:40:43 canonical baseline loaded stage=full turns=37 len=100986
09:41:05 story selected id=st_dmdd8q6gub6zf0dd1884
09:41:05 start story=st_dmdd8q6gub6zf0dd1884 branch=main
09:41:06 canonical baseline loaded stage=full turns=8 len=23140
```

- 两次进入 8 轮故事的基线完全一致（`turns=8 len=23140`），37 轮故事为 `turns=37 len=100986`，与列表展示的轮次数一致。
- 每次切换都先 `POST .../select` 成功再 `start`，本地不自行改 `current_story_id`；切换同时推进 `storyEpoch` 并取消在途发送，迟到回包不写入新故事舞台（`GameViewModel.updateUiIfCurrent`）。

## 3. 真机暴露并修复的缺陷

**长标题把副标题挤成竖排单字**：服务端故事标题可能是长叙事句（如「深冷的大理石地面泛着刺骨寒意，下一秒…」），
原 `Row` 中 `TextButton` 无宽度约束，把「N 轮 · 更新时间」压到 1 个字符宽度，屏幕上出现一列竖排文字。

修复：标题 `Modifier.weight(1f, fill = false)` + `maxLines = 1` + `TextOverflow.Ellipsis`，副标题 `maxLines = 1`，
列表项同样单行省略；列表打开时 `scrollToItem(当前项)` 保证当前故事可见。修复后 5 项均单行完整显示。

## 4. 未覆盖 / 后续

- 删除真实往返（见 §1），随 A2 自建临时故事补测。
- GAM-002 的「输入和台账分别保留」：状态台账属 A3（GAM-006），按故事隔离的输入草稿尚未实现，因此 GAM-002 维持矩阵 `未开始`。
- 故事创建与高级配置（GAM-001）属 A2。
- 截图含服务端生成的成人向叙事正文，按 `AGENTS.md` §5 只留本地，不入库。
