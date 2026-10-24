import re
import statistics
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "build"
runs = sorted(root.glob("d03p_run*.txt"))

window_re = re.compile(
    r"WINDOW flushes=(\d+) frames=(\d+) bytes=(\d+) dropped=(\d+) parseAvg=([\d.]+)ms "
    r"out\[n=(\d+) p50=(\d+)ms p95=(\d+)ms p99=(\d+)ms max=(\d+)ms\] "
    r"input\[n=(\d+)(?> p50=(\d+)ms p95=(\d+)ms p99=(\d+)ms max=(\d+)ms)?\]"
)
pss_re = re.compile(r"t=(\d+) pss=(-?\d+)KB")


def med(values):
    return statistics.median(values) if values else None


rows = []
all_input = []
all_pss_delta = []

for path in runs:
    text = path.read_text(encoding="utf-8", errors="replace")
    name = path.stem
    idle = None
    m = re.search(r"pssIdle=(-?\d+)", text)
    if m:
        idle = int(m.group(1))
    samples = [(int(a), int(b)) for a, b in pss_re.findall(text)]
    peak = max((v for _, v in samples), default=None)
    # 剔除前 3 个采样点（连接/尺寸稳定期）
    steady = [v for t, v in samples if t >= 45]
    peak_steady = max(steady) if steady else peak
    delta = (peak_steady - idle) if (peak_steady and idle and idle > 0) else None
    if delta:
        all_pss_delta.append(delta)

    windows = []
    for w in window_re.finditer(text):
        g = w.groups()
        windows.append(
            {
                "flushes": int(g[0]),
                "frames": int(g[1]),
                "dropped": int(g[3]),
                "parse": float(g[4]),
                "n": int(g[5]),
                "p50": int(g[6]),
                "p95": int(g[7]),
                "p99": int(g[8]),
                "max": int(g[9]),
                "in_n": int(g[10]),
                "in_p50": int(g[11]) if g[11] else None,
                "in_p95": int(g[12]) if g[12] else None,
                "in_max": int(g[14]) if g[14] else None,
            }
        )
        if g[11]:
            all_input.append((int(g[11]), int(g[12]), int(g[14])))

    steady_windows = windows[1:] if len(windows) > 1 else windows  # 丢掉首个预热窗口
    rows.append(
        {
            "run": name,
            "idle": idle,
            "peak": peak_steady,
            "delta": delta,
            "windows": len(steady_windows),
            "dropped": sum(w["dropped"] for w in steady_windows),
            "p50": med([w["p50"] for w in steady_windows]),
            "p95": med([w["p95"] for w in steady_windows]),
            "p99": med([w["p99"] for w in steady_windows]),
            "max": max((w["max"] for w in steady_windows), default=None),
            "parse": med([w["parse"] for w in steady_windows]),
            "in_p50": med([w["in_p50"] for w in steady_windows if w["in_p50"] is not None]),
            "in_p95": med([w["in_p95"] for w in steady_windows if w["in_p95"] is not None]),
        }
    )

print("run            idleKB   peakKB   deltaMB  win  drop  outP50  outP95  outP99  outMax  parseMs  inP50  inP95")
for r in rows:
    print(
        f"{r['run']:<14} {str(r['idle']):>7} {str(r['peak']):>8} "
        f"{(r['delta'] / 1024 if r['delta'] else 0):>8.1f} {r['windows']:>4} {r['dropped']:>5} "
        f"{str(r['p50']):>7} {str(r['p95']):>7} {str(r['p99']):>7} {str(r['max']):>7} "
        f"{(r['parse'] or 0):>7.3f} "
        f"{str(r['in_p50']):>6} {str(r['in_p95']):>6}"
    )

valid = [r for r in rows if r["p95"] is not None]
print()
print("median across runs:")
print(f"  out p50 = {med([r['p50'] for r in valid])} ms")
print(f"  out p95 = {med([r['p95'] for r in valid])} ms")
print(f"  out p99 = {med([r['p99'] for r in valid])} ms")
print(f"  parse   = {med([r['parse'] for r in valid]):.3f} ms/frame(1KB)")
print(f"  PSS delta = {(med(all_pss_delta) / 1024 if all_pss_delta else 0):.1f} MB  (n={len(all_pss_delta)})")
print(f"  dropped total = {sum(r['dropped'] for r in valid)}")

if all_input:
    p50 = [s[0] for s in all_input]
    p95 = [s[1] for s in all_input]
    mx = [s[2] for s in all_input]
    print()
    print(f"input latency windows n={len(all_input)}")
    print(f"  per-window p50: median={med(p50)}ms max={max(p50)}ms")
    print(f"  per-window p95: median={med(p95)}ms max={max(p95)}ms")
    print(f"  per-window max: median={med(mx)}ms max={max(mx)}ms")
