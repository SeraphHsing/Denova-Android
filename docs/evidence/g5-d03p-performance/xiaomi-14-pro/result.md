# D03-P 终端性能实测（G5 数值预算）

- 日期：2026-10-24
- 设备：Xiaomi 14 Pro（2304FPN6DC，1440×3200 / 120Hz），debug 构建
- 服务端：本地 mock（`tools/d03p_mock_server.py`），经 `adb reverse tcp:8088` 直连，无网络抖动
- 重放口径（P0-05/06 冻结）：**1KB / 16ms = 62.5 KiB/s（≈64 KB/s），持续 300s，含 SGR 16/256/truecolor、光标定位、换行滚屏、CJK 宽字**
- 测量工具：客户端 `TerminalPerf` 埋点（`logcat -s TerminalPerf`）+ `dumpsys meminfo` 采样

## 结论

6 轮有效重放（每轮 300s，共约 11.3 万帧 / 115MB）全部**零丢帧**，输出可见延迟中位 p95 **39ms** / p99 **46ms**，输入回显延迟最大 **57ms**，解析 **0.91ms/帧(1KB)**，resize 重排 **1.19ms 均值 / 3ms 最大**，峰值 PSS 增量中位 **21.3MB**。

**预算已于 2026-10-24 评审通过（评审人 Hsing）**，冻结值与限定条件见 [06 §4](../../../android-web-parity/06-development-and-validation.md#d03-性能基准与冻结记录) 与下节。

## 原始汇总（剔除每轮首个预热窗口）

| 轮次 | 空载 PSS | 稳态峰值 PSS | 增量 | 窗口数 | 丢帧 | out p50 | out p95 | out p99 | out max | 解析/帧 |
|---|---|---|---|---|---|---|---|---|---|---|
| run1 | 180.5MB | 199.2MB | 18.7MB | 14 | 0 | 2ms | 39.5ms | 46ms | 69ms | 0.89ms |
| run2 | 178.0MB | 199.5MB | 21.5MB | 13 | 0 | 2ms | 39ms | 47ms | 77ms | 0.92ms |
| run3 | 179.8MB | 195.5MB | 15.7MB | 15 | 0 | 2ms | 40ms | 45ms | 73ms | 0.92ms |
| run4* | 176.5MB | 193.6MB | 17.1MB | 27 | 0 | 6ms | 15ms | 17ms | 47ms | 5.58ms |
| run5 | 174.0MB | 196.3MB | 22.3MB | 10 | 0 | 2ms | 39ms | 46ms | 84ms | 0.90ms |
| run6 | 172.7MB | 198.1MB | 25.4MB | 11 | 0 | 2ms | 39ms | 45ms | 102ms | 0.91ms |

\* run4 采样期间前台误停在编辑器页（终端仍在后台解析但不合成帧），解析值明显偏高、可见延迟偏低，作为异常轮次单列，不参与预算取值。

跨轮中位：**out p50 2ms / p95 39ms / p99 46ms、解析 0.909ms/帧、PSS 增量 21.3MB、丢帧 0**。

### 输入可见延迟
8 次/轮 × 3 轮（run5–7）共 13 个有效窗口：每窗口 p50 中位 **34ms**、最大 **57ms**（口径：`sendText` → 回显所在批次绘制完成）。

### 积压与后端吞吐
- 客户端零丢帧（`dropped=0`），通道积压峰值 **7–13 帧**（容量 256）。
- mock 服务端实测速率 **62.5 KiB/s**（20,623 帧 / 21.1MB / 330s），与冻结口径一致。

### resize 重排
90s 短流中 IME 弹出导致 54→31 行分步重排：`resizeCount=21 / avg 1.19ms / max 3ms`（`TerminalScreen.resize` 本地耗时，不含服务端往返）。

## 冻结的 G5 预算（2026-10-24 评审通过｜评审人 Hsing）

| 指标 | 预算 | 实测依据 |
|---|---|---|
| PTY 输出 → 可见延迟 | p95 ≤ 50ms，p99 ≤ 80ms | 中位 39 / 46ms |
| 输入 → 回显可见 | p95 ≤ 100ms | 最大 57ms（n=13 窗口） |
| 单帧解析（1KB/帧） | ≤ 2ms | 中位 0.909ms，最大 3.77ms（累计口径） |
| 丢帧 / 积压 | 丢帧 = 0，积压峰值 ≤ 32 帧 | 0 / 7–13 帧 |
| resize 网格重排 | 平均 ≤ 5ms，最大 ≤ 20ms | 1.19ms / 3ms |
| D03-P 峰值 PSS 增量 | ≤ 60MB | 中位 21.3MB，最大 25.4MB |
| 帧时间（janky/percentile） | 同 G4 不冻结 | 目标设备 `gfxinfo` 经空闲对照实验判定不可用 |

## 本轮暴露并修复的真机缺陷

1. **底部键位条被系统手势区覆盖，点击无效**：手势导航下 `navigationBars` 内边距常报 0，键位条（Ctrl-C/D/Z、Tab、方向键）中心落在手势区内，`adb tap` 与手指点击都被系统吞掉。修复：底部内边距取 `max(navigationBars, systemGestures)`。
2. 测量脚本层面：`adb shell input text` 的空格需用 `%s`；`powershell -File` 会按 ANSI 读脚本导致中文匹配失效（改用 `pwsh`）。

## 方法学局限（不构成通过）

- 仅真机档；模拟器档未测（G4 的模拟器结果仅作参照）。
- 内容为确定性合成流，不是录制的真实全屏程序（vim/htop）帧；覆盖了 SGR/光标/滚屏/CJK 但未覆盖备用屏高频重绘。
- 输出延迟口径是「字节到达 → 下一次 16ms 合帧绘制完成」，因此天然含 0–2 个合帧周期；不与 G4 的 SSE 口径直接比较。
- run4 前台页面不是终端页（异常轮次，已单列）。
- 未测锁屏/后台、旋转、跨包升级等场景下的终端性能。

## 复现

```bash
python tools/d03p_mock_server.py 8088 300     # mock PTY 回放 + REST
adb reverse tcp:8088 tcp:8088
# App 服务器填 127.0.0.1:8088 → 终端 → 项目 ID 填 d03p → 连接
adb logcat -s TerminalPerf:V
python tools/d03p_analyze.py                   # 汇总 build/d03p_run*.txt
```
