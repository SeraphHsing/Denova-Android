# NAV-008 边缘手势与排除区（真机）

- **日期**：2026-10-30
- **设备**：小米 14 Pro（`135babde`），Android 16，物理分辨率 1440×3200，density 600（1dp = 3.75px）
- **应用**：`com.denova.novara` Debug（本轮构建）
- **服务器**：`http://192.168.2.102:18081`（后端 0.6.3-b），隔离测试账号，真实往返
- **Web 基线**：`SeraphHsing/denova@85ec97c`，对照证据 `docs/evidence/web-baseline/2026-10-30/`

## 实现要点

- 判定逻辑移植自基线 `web/src/components/layout/use-mobile-drawer-swipe.ts`：边缘带 24dp、方向阈值 8dp、位移阈值 48dp、比例 1.35、纵向主导放弃、抽屉标题栏起手才可关闭、抽屉内容与交互控件不认领。
- 关闭 M3 `ModalNavigationDrawer` 默认手势（`gesturesEnabled = false`）：它整屏可拖、无比例与排除判定，会抢走页面横向控件。
- 指针采样走 `PointerEventPass.Initial`（父节点先于页面滚动容器），只有横向位移超过 8dp 才 `consume()`。
- 已打开的抽屉状态用本地 `leftDrawerOpen` 驱动（M3 `DrawerState` 读值不保证触发重组），并以 120ms 轮询真实状态兜底同步 scrim 点击、返回键与导航后自动收起。
- 只在已认证的抽屉壳上挂手势，连接页不响应（否则登录后首屏抽屉会带着打开状态）。

## 判据与实测

| 判据（矩阵 steps） | 结果 | 证据 |
|---|---|---|
| 左边缘右滑打开左抽屉 | 通过（三键导航） | logcat `DrawerSwipe: begin side=null zone=SURFACE x=6.0` → `end outcome=Open(side=LEFT) claimed=true`；uiautomator 出现「关闭导航菜单」与抽屉节点 |
| 右边缘左滑打开右抽屉 | 通过（三键导航） | `begin x=1434.0` → `end outcome=Open(side=RIGHT)`；节点出现「项目目录 / 变更 / 版本 / 章节」 |
| 抽屉标题栏左滑关闭 | 通过 | 起手 y=250（避开状态栏 123px）→ `zone=DRAWER_HEADER` → `end outcome=Close`，抽屉消失 |
| 抽屉内容区横滑不关闭（保留内部横向控件） | 通过 | `down openSide=LEFT zone=DRAWER_CONTENT`，`begin` 未认领，抽屉保持打开 |
| 垂直阅读不误触 | 通过 | 边缘垂直滑动 `begin` 后 `move` 判为纵向主导直接放弃，无 `end` 动作，页面保持原状 |
| 阈值/比例/边界 | 通过（JVM） | `EdgeSwipeGestureTest` 13 例：24dp 边界、8dp 认领、48dp 与 1.35 比例闭区间、重叠区、allowedSides、OVERLAY/交互控件排除 |

## 平台差异（系统边缘返回）

- **三键导航**（`settings put secure navigation_mode 0`）：上述判据全部通过。
- **手势导航**（`navigation_mode 2`，设备默认）：`adb shell input swipe` 从 x=6 起手直接触发系统返回，应用被切到桌面；`Modifier.systemGestureExclusion()` 在屏幕中部声明的 200dp×24dp 排除带实测**未生效**（MIUI/HyperOS 仍由系统抢走该手势）。关闭本应用手势后由 ☰ 按钮作为等价入口。
- 决定：保持系统返回优先权（[04 §5](../../android-web-parity/04-ui-and-editor.md#5-抽屉与手势)），登记为 [07 PD-01](../../android-web-parity/07-evidence-and-decisions.md#5-平台差异登记)，不通过关闭系统手势或降低安全策略规避。
- 排除带代码保留（对支持 `systemGestureExclusion` 的设备与三键导航无副作用），但不作为通过依据。

## 未覆盖

- 平板/折叠屏与横屏下的边缘手势未单独验证（随 NAV-010 自适应档位）。
- 手势动画跟手位移（拖动过程中抽屉跟手）未实现：当前只在抬起时开合，与基线「松手判定」一致，但没有基线的实时位移映射；按 P5 视觉/交互对齐登记。
