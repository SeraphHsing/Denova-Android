# NAV-004 导航顺序保存与重启保持（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建）
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b）

## 判据与实测

| 步骤 | 结果 | 证据 |
|---|---|---|
| 抽屉进入排序模式 | 通过 | 点「排序」后标题行变为「导航 / 完成 / 恢复默认」，每组前两条出现 ↑↓ |
| 调整顺序 | 通过 | 点「游戏」↑ → logcat `NavOrder: move id=game delta=-1 -> [game, editor, agent, workbench, lore, teller, versions, books, skills, agents, automations, market]`；界面顺序变为「游戏」在上、「写作（当前）」在下 |
| 完成并写盘 | 通过 | 点「完成」退出排序模式；`PreferencesStore.setActivityOrder()` 写入 DataStore `activity_order` |
| 重启后保持 | 通过 | `am force-stop` + 重新启动 + 重新连接后，抽屉顺序仍为「游戏 → 写作（当前）」，与调整结果一致 |
| 缺项按默认位插入 | 通过（JVM） | `ActivityNavTest`：`insertMissing` 覆盖前项存在/不存在/未知 id 三种插入位置 |

## 说明

- 顺序表只含 `ActivityNav.DEFAULT_ORDER`（12 项），设置条目固定在设置组、不参与排序（与基线 `activityOrder` 不含 settings 一致）。
- 排序交互是「↑↓ 按钮」，基线是设置/自定义内的拖拽重排（dnd-kit），已登记 [09 VG-004](../../android-web-parity/09-visual-gap-backlog.md) 待 P5 对齐；本轮按矩阵 `steps`（改变顺序并重启）判定。

## 未覆盖

- 恢复默认按钮只验证可点击与写回默认值，未单独截图对照。
- 排序模式下的长按拖拽（基线能力）未实现，同上登记为差异。
