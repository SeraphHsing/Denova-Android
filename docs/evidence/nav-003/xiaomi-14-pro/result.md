# NAV-003 左右面板互斥（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16
- **应用**：`com.denova.novara` Debug（本轮构建）
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b），真实往返
- **Web 对照**：`docs/evidence/web-baseline/2026-10-30/drawer-390.png`（左抽屉）、`workspace-mobile-layout.tsx`（`MobilePaneHost` 同一时刻只持有一个 `openPaneId`）

## 实现

- 左抽屉的所有入口收敛到 `openLeftDrawer()`：先 `showRightDrawer = false`，再 `drawerState.open()`。
- 打开右抽屉（`onOpenRightDrawer` / 右边缘手势）先 `closeLeftDrawer()`，再 `showRightDrawer = true`。
- 120ms 轮询真实 `DrawerState` 作兜底：左抽屉一旦实际打开就收起右抽屉，避免 scrim 点击/返回键等路径留下两侧同时 "打开" 的状态。

## 判据与实测

| 判据 | 结果 | 证据 |
|---|---|---|
| 右抽屉打开时打开左抽屉 | 通过（先关后开，不并存） | 右抽屉（项目目录/变更/版本/章节节点）→ 点 scrim 关闭 → 左边缘手势开左抽屉 → 节点只剩「关闭导航菜单 / 当前书籍 / 书单」，无「项目目录」 |
| 左抽屉打开时打开右抽屉 | 通过 | 右抽屉内容由页面按钮/右边缘手势触发，入口先 `closeLeftDrawer()`；左抽屉打开时其 scrim 覆盖右侧入口（与 Web `MobilePaneHost` 同一时刻只挂一个 pane 一致） |
| 同一时刻只有一个面板可见 | 通过 | 两次 uiautomator 节点集合互斥：左抽屉 20 个节点、右抽屉 8 个节点，从未同时出现 |

## 未覆盖

- 「左抽屉打开 → 点页面右侧按钮」在真机上不可达（scrim 遮挡），与 Web 相同，未构造该路径的强制并存对照；结论依据代码路径与节点集合互斥。
- 面板切换动画的逐帧重叠（结束帧 vs 开始帧）未做录屏证据（项目无原生录屏，[05 §3.1](../../android-web-parity/05-roadmap.md#31-p1-证据台账2026-10-24-核对) 已登记该证据形态缺口）。
