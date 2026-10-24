# G4 D03 流性能测量（真机档）

- 设备：Xiaomi 14 Pro（2304FPN6DC / ishtar），1440×3200，120Hz
- App：debug，服务端 `http://192.168.2.102:18081`（0.6.3 @ 85ec97c），局域网
- story：`st_dmc2p6amro52de34e38f` / branch `main`

## 一、第一轮（已作废）：gfxinfo 指标不可用

第一轮用 `dumpsys gfxinfo` 采到 p50 26ms / janky 62.55%，据此判断“帧时间超标”。

**对照实验推翻了该结论**：应用空闲（无流、无输入焦点）25 秒后重置并采样：

| 窗口 | 帧数 | Janky | p50 | p95 |
|---|---|---|---|---|
| 流式输出 | 220 | 58.18% | 22ms | 53ms |
| **空闲对照** | **49** | **100%** | **32ms** | **42ms** |

空闲帧同样被判 100% janky 且 p50 更高 → **该设备（120Hz + debug + MIUI）上 `gfxinfo` 的 janky/percentile 不反映应用渲染成本**，第一轮的 p50/janky 数值作废，不作为任何结论依据。

## 二、第二轮（有效）：可复现的计数量指标

在 ViewModel 内埋点（`GamePerf` tag），一次真实 turn 实测：

| 指标 | 值 | 说明 |
|---|---|---|
| 首帧延迟 | 2683–2949 ms | `send` → 首个流事件；**含服务端 LLM 首 token 等待** |
| 单事件投影耗时 | avg **3.32 ms** / max **12.44 ms** | 100 事件样本（reducer + 状态发布），均在主线程 |
| chunk 合批 | 50 次 flush × 平均 41 字 ≈ **2050 字**（85s 时） | 60ms 合批窗口 |
| 整轮吞吐 | 3667 字 / 261 事件 / ≈150s ≈ **24 字/s（≈12–16 tok/s）** | 平均 14 字/事件 |
| PSS | 169.3 MB → 174.9 MB | 随正文增长 +5.6MB，无异常增长 |

**关键事实（已用 2s 采样定论，非模型限速）**：对运行中的 turn 每 2s 采样 `cursor` / `active_output.content`，得到明确的速率结构：

| 阶段 | 时长 | 观测 |
|---|---|---|
| 首 token 前静默 | ≈40s（采样 1–20） | `cursor` 恒定 3116、`content`=0、`thinking`=0 |
| **正文爆发** | ≈26s（采样 21–33） | 3131 字，每 2s 增量 125–302 字 → **≈240 字/2s ≈ 120 字/s（≈70–80 tok/s）** |
| 中段停顿 | ≈16s（采样 34–41） | `cursor`/`content` 零增长 |
| 收尾 | ≈4s | `finish`，`status=done` |

结论：**不存在 TPS 上限**（爆发期 120 字/s 远高于均值）；整轮均值被「首 token 前 40s 静默 + 中段 16s 停顿」摊薄。客户端在这类负载下的真实峰值约为 **16 次/秒 UI 更新**（60ms 合批），与设计假设一致。

即便如此，**D03-S 冻结负载（1KB/块、100 块/s、60s ≈ 100KB/s）仍比真实流爆发期（≈360 B/s）高约 2 个数量级、比均值高约 3 个数量级**，真实流无法用于验证该预算，必须用固定重放。

## 三、本轮所做的性能改造（工程正确，但未经 D03-S 验证）

1. `NarrativeBuilder`：分块增量累积，替代 `String +=`（原实现每 chunk 复制整段文本，O(n²)）。
2. chunk 合批：60ms 合并发布，UI 更新频率与事件数解耦。
3. 状态拆分：`blocks` 独立 StateFlow，高频更新只重组叙事面板，不牵动输入框（IME 布局代价高）。
4. `LazyColumn(reverseLayout = true)`：新块天然锚底，无需 `scrollToItem` 触发重排。

## 四、D03-S 固定重放实测（mock）

**重放器**：`tools/d03_mock_server.py`（仓库内，仅用于测量）
- 参数：`--block-bytes 1024 --rate 100 --duration 60` → 6000 块 / 6,144,000 B / 每块 341 字（1024 UTF-8 字节）/ 累计约 205 万字
- 事件序列：`agent_cycle_started` → `task_checkpoint` → 6000×`chunk` → `task_checkpoint_committed` → `done`
- 接入方式：`adb reverse tcp:8766 tcp:8765`，应用服务器填 `http://127.0.0.1:8766`（USB 直连，不经 Wi-Fi、免防火墙）

口径说明：「可见」取 `SideEffect`（每次成功重组提交后结算），即「投影已提交到组合 → 可绘制」；不含 GPU 实际呈现时间。

### 4.1 真机档（Xiaomi 14 Pro，App debug，5 次取中位数，预热 1 次剔除）

| run | p50 | p95 | p99 | max | 投影 avg |
|---|---|---|---|---|---|
| 1（预热，剔除） | 7 | 14 | 19 | 26 | 0.134 ms |
| 2 | 8 | 24 | 33 | 53 | 0.133 ms |
| 3 | 7 | 16 | 20 | 30 | 0.139 ms |
| 4 | 9 | 28 | 36 | 46 | 0.110 ms |
| 5 | 7 | 16 | 19 | 23 | 0.122 ms |
| 6 | 8 | 30 | 34 | 42 | 0.108 ms |
| **中位数（2–6）** | **8 ms** | **24 ms** | **33 ms** | **42 ms** | **0.122 ms** |

- 每次均 6000 事件（≈100/s），终态 `完成`，无崩溃。
- PSS：空载 163,749 KB → 峰值中位数 **193,999 KB**（**+30.3 MB**，承载约 205 万字）。

### 4.2 Web 基线（Chrome Headless 156，1280×800，harness 页面，200 块滚动窗口）

| 指标 | 值 |
|---|---|
| 样本 | n=3599（chunks=6000） |
| 事件接收 → 下一帧（rAF） | p50 **5.9 ms** / p95 **10.0 ms** / p99 10.2 ms / max 10.5 ms |
| JS 堆 | 1.9 MB |

说明：这是浏览器端 harness（非 Web 产品代码），渲染策略为「最近 200 块」滚动窗口；Android 侧保留全部 5000 块并惰性渲染，两者并非同构实现，作**数量级参照**而非严格同构对照。

### 4.3 模拟器档（Pixel 6 / API 33 / x86_64，`-gpu swiftshader_indirect`，单次）

| 指标 | 值 |
|---|---|
| 事件 / 投影 | 6000 事件，avg 0.116 ms / max 14.8 ms |
| 事件接收 → 可见 | p50 **5 ms** / p95 **12 ms** / p99 18 ms / max 308 ms（单次离群） |
| PSS | 76,405 KB → 79,697 KB（+3.3 MB） |

说明：模拟器为**软件渲染**（swiftshader），其合成/绘制耗时与真机 GPU 不可比，仅作最低档可用性证据，不作性能结论来源。

### 4.4 恢复耗时（固定断线场景，真机 5 次）

场景固定：`force-stop` → 冷启 → 连接/登录 → 游戏 → `story=st_dmc2p6amro52de34e38f` → 挂接（Settle 路径，加载 canonical 历史并渲染，不含模型生成）。

埋点（`Perf` tag，logcat `-v epoch` 对齐）：
`process-start` → `login-start` → `login ok` → `Game: start story=` → `canonical baseline loaded` → `narrative-visible`

| run | 认证往返 | 附接 + canonical 合并 | 基线 → 可操作渲染 | 合计 |
|---|---|---|---|---|
| 1 | 158 ms | 2651 ms | 52 ms | 2861 ms |
| 2 | 126 ms | 2632 ms | 65 ms | 2823 ms |
| 3 | 101 ms | 2659 ms | 61 ms | 2821 ms |
| 4 | 166 ms | 2597 ms | 56 ms | 2819 ms |
| 5 | 126 ms | 2656 ms | 95 ms | 2877 ms |
| **中位数** | **126 ms** | **2651 ms** | **61 ms** | **2823 ms** |

口径：三段均为 app 内部埋点差值，排除人工/adb 导航耗时。**附接 + canonical 合并占 94%**（2651/2823），是后续优化重点（history 端点 ~95KB JSON 拉取 + 解析）。

### 4.5 恢复耗时优化（渐进式基线 + 回前台重挂，真机 6 次有效）

**根因定位**：`GET /api/interactive/stories/:id/history` 的服务端耗时随 `limit` 近似线性——`limit=3 → 223ms/28KB`、`limit=5 → 330ms/51KB`、`limit=10 → 681ms/104KB`、`limit=20 → 1309ms/179KB`、`limit=50 → 2096ms/302KB`。原先一次取 50 轮，故首屏必然等 2s+。

**改造**：
1. **渐进式基线**：先取最近 3 轮（≈285ms）立即渲染，全量 50 轮随后台协程补全并合并（保留流增量，`streamTail`）。
2. **批量替换后锚回最新**：基线整段替换时 `scrollToItem(0)`，避免停在旧端空白。
3. **回前台自动重挂**：后台被系统掐断连接（`SocketException`）后标记 `streamFailed`，`ON_RESUME` 时自动 `start()` 重新附接。

| run | 认证 | start→首屏基线 | start→可操作 | start→全量就绪 |
|---|---|---|---|---|
| 1 | 195 ms | 319 ms | 359 ms | 2745 ms |
| 2 | 125 ms | 315 ms | 355 ms | 2728 ms |
| 3 | 142 ms | 314 ms | 354 ms | 2744 ms |
| 4 | 267 ms | 271 ms | 314 ms | 2712 ms |
| 5 | 135 ms | 270 ms | 308 ms | 2694 ms |
| 6 | 141 ms | 267 ms | 303 ms | 2754 ms |
| **中位（去极值）** | **142 ms** | **315 ms** | **334 ms** | **2736 ms** |

**恢复至可操作 = 认证 + start→可操作 ≈ 488 ms**（原 2823 ms，**提升约 5.8×/-83%**）；全量历史约 2878 ms 后台完成，不阻塞操作。

**后台恢复用例（2 个）**：
- 保留内容：HOME 30s → 返回，叙事完整、无重取、无崩溃（`am start` 直接 bring-to-front，未重建 Activity）。
- 活跃流：发送后立即 HOME 40s → 连接被系统中断（`SocketException: Software caused connection abort`）→ 回前台日志 `foreground re-attach after stream failure` → 首屏基线 285ms 可操作、全量 2583ms；期间服务端 turn 照常完成（`status=done`），客户端重新挂接后结果与身份一致。

## 五、G4 数值预算（本轮冻结）

依据：真机 5 次中位数（主档）+ 模拟器与 Web 基线（参照）；各项留约 2× 余量，未在测量失败后放宽。

| 预算项 | 口径 | 上限 | 实测依据 |
|---|---|---|---|
| 事件接收 → 投影可见 p95 | 真机，D03-S 100 块/s | **≤ 50 ms** | 真机中位 24 ms；模拟器 12 ms；Web 10 ms |
| 事件接收 → 投影可见 p99 | 同上 | **≤ 80 ms** | 真机中位 33 ms |
| 单事件投影耗时 | reducer + 状态发布 | **≤ 2 ms** | 真机 0.122 ms、模拟器 0.116 ms |
| 峰值 PSS 增量 | D03-S 60s / 6MB / ≈205 万字 | **≤ 60 MB** | 真机 +30.3 MB；模拟器 +3.3 MB |
| **恢复至可操作**（认证 + 首屏基线 + 渲染） | 冷启 + 固定断线（`force-stop`） | **≤ 1000 ms** | 真机中位 **488 ms**（渐进基线后） |
| 其中：认证往返 | `login-start` → `login ok` | **≤ 500 ms** | 中位 142 ms |
| 其中：首屏基线（3 轮）+ 渲染 | `start story=` → `narrative-visible` | **≤ 600 ms** | 中位 334 ms |
| 全量历史就绪（后台，不阻塞） | `start story=` → `baseline-full` | **≤ 4000 ms** | 中位 2736 ms（含认证约 2878 ms） |
| 流式更新帧时间 | — | **暂不冻结** | 本机 `gfxinfo` janky/percentile 经对照实验判定不可用（见 §一），改用可见延迟作为替代口径 |

## 六、仍未达 D03 通过条件

1. Web 基线为 **harness**，非 Web 产品代码的真实同构对照。
2. 模拟器为软件渲染，其数值不参与预算判定；若需 GPU 模拟器档需另跑 `-gpu host`。
3. 预算为本轮首次冻结，需评审确认后方可作为关卡判据。

## 七、复现方式

```bash
python tools/d03_mock_server.py --port 8765
adb -s <serial> reverse tcp:8766 tcp:8765        # 真机；模拟器直接用 http://10.0.2.2:8765
# 应用内：服务器 http://127.0.0.1:8766 → 连接 → 游戏 → story=d03 → 挂接（60s）
adb -s <serial> logcat -d -s GamePerf | Select-String "latency|events="
# Web 基线：Chrome 打开 http://127.0.0.1:8765/d03/bench，结果回报在 mock 服务 stdout（WEBRESULT）
```
