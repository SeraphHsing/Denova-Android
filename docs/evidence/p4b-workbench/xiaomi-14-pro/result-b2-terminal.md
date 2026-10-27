# P4-B 工作台 · 第二切片真机证据（WB-008，含 WB-007 工作台宿主）

- 日期：2026-10-27
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 用例：`:app:testDebugUnitTest` 69 套件 / **493 用例** 0 失败（新增 3 条终端页签用例）；`lintDebug`、`assembleDebug` 通过

## 1. 本轮实现

| 能力 | 实现位置 |
|---|---|
| 工作台终端页签（新建 / 内容宿主 / 关闭释放） | `feature/workbench/WorkbenchScreen.kt`、`WorkbenchViewModel.openTerminal` |
| 终端界面内嵌模式（去掉重复的标题行与项目 ID/连接行，只留状态行） | `feature/terminal/TerminalScreen.kt`（`inline`） |
| 每页签独立 PTY 归属（`owner_tab_id`） | `TerminalViewModel.bindOwner` + `terminalTabId()` |
| 页签 → 终端会话登记表（Activity 级 ViewModel，旋转后仍在） | `TerminalTabHolder` |
| 新建页签菜单（新建会话 / 新终端） | `WorkbenchScreen.TabStrip` |

### 契约依据

- `POST /api/projects/:id/terminal/sessions` 的 `owner_tab_id` 是**幂等键**，且同一 owner 不能跨项目复用
  （`internal/terminal/manager.go` 的 `ErrOwnerConflict`，HTTP 409 `api.terminal.ownerConflict`）。
- Web 基线每个终端页签用自己的 tab id 作 owner（`use-agent-chat-terminal-tabs.ts` 的 `createTabId('terminal')`），
  所以同一工作台可以并存多个终端；我们原先用固定 `android-main`，第二个项目的终端必然 409。

## 2. WB-008 旋转与重连

| 步骤 | 实测结果 |
|---|---|
| 打开终端页签 | 日志 `Workbench: open terminal tab=term:b938526 project=project-4e55 profile=shell` → `Terminal: attached session=cd7e1618… size=80x24`，界面 `已就绪 68×50`（`shots/86-wb-terminal.png`） |
| 竖 → 横 | 网格 `68×50` → `152×8`，PTY 收到 resize 后重画提示符；**无新的 `attached`/新建日志**（同一会话、同一 WS）（`shots/87-rotate-landscape.png`） |
| 横 → 竖 | 回到 `68×50`，内容与滚动位置保留（`shots/88-rotate-portrait.png`） |
| 回看（滚动） | 拖动后提示 `回看 +17 行 / 42`，scrollback 跨旋转保留（`shots/81-scrollback.png`） |
| 断网重连 | 关 Wi-Fi + 数据 → 界面 `连接失败，准备重连…`（`shots/95-offline.png`）→ 恢复网络 25s 内 `Terminal: attached session=dc2c0a89… size=68x50`，**session id 与断网前一致**（`shots/96-reconnected.png`） |
| 关闭页签 | 日志 `Terminal: released session=dc2c0a89…`；页签消失、激活相邻页签（`shots/97-closed.png`） |

结论：旋转只做 resize（不重建 PTY），断线重连附接**原会话**（不新建），关闭页签按 owner 精确释放。

## 3. 同轮修复的真机缺陷

1. **跨项目 409**：终端 owner 用固定 `android-main`，在项目 B 新建终端被 `ownerConflict` 拒绝
   （界面提示「该终端归属已被另一个项目占用」）。改为按页签 id 绑定 owner 后正常（`shots/66-terminal-tab.png` 为修复前）。
2. **「＋」被滚出屏幕**：新建页签菜单原来跟着页签一起横向滚动，标题一长入口就找不到了。改为页签区可滚动、「＋」固定在右端。
3. **激活页签不滚入视野**：切到后面的页签时条上仍停在最左边，看不到当前页签。改为 `LazyRow` + 激活页签自动滚动到可见。

## 4. 未覆盖 / 缺口

- 终端命令配置（`/api/terminal/runtime` 的 `commands`）还没有进新建页签菜单，当前只有 `shell`。
- 页签跨进程恢复（Web `tab-state`）仍未做：进程被杀后终端页签与其 PTY 归属一起消失，需要重新打开。
- 仪器测试未执行（沿用 WRI-016 登记的环境阻塞）。
