#!/usr/bin/env python3
"""P5 双端读数比对（09 §3 标准 2：尺寸差 ≤1dp、字号 ≤1sp）。

Web 侧读 `tools/web_parity_capture.py` 产出的 `metrics.json`（computed style，css px ≈ dp）；
Android 侧读 `adb shell uiautomator dump` 的 XML（`bounds="[x1,y1][x2,y2]"`，物理 px），
按设备 density（`adb shell wm density`）换算成 dp 后逐项比较，输出 Markdown 表与退出码。

用法：
    python tools/p5_compare.py --web metrics.json --scene editor-390 \
        --dump ui.xml --density 590 --spec spec.json --out report.md

spec（每屏一份，随证据一起提交）：
    {
      "screen": "editor-390",
      "tolerance_dp": 1.0,
      "items": [
        {"label": "正文段落", "web": "editor.paragraph", "dim": "h",
         "match": {"text": "山雨落在旧城墙上"}},
        {"label": "顶栏", "web": "topBar", "dim": "h", "match": {"desc": "导航菜单"}}
      ]
    }

- `web` 为相对 scene 的取值路径（`a.b.c`），取值为带 `w`/`h` 等字段的对象；
- `dim` 取 `w` 或 `h`（也接受 `font-size` 这类以 px 结尾的字段，按字号口径比较）；
- `match` 支持 `text` / `desc` / `rid`，先精确后包含匹配；
- 退出码 0 表示全部在容差内，1 表示存在超差项（便于 CI / 脚本闸门）。

只读工具，不改业务数据。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    """`[x1,y1][x2,y2]` → (x1, y1, x2, y2)；格式不符返回 None。"""
    m = BOUNDS_RE.search(raw or "")
    if not m:
        return None
    return tuple(int(v) for v in m.groups())  # type: ignore[return-value]


def px_to_dp(value: float, density_dpi: float) -> float:
    """物理像素 → dp（Android 口径 160dpi = 1dp）。"""
    if density_dpi <= 0:
        raise ValueError(f"density 必须为正数：{density_dpi}")
    return value * 160.0 / density_dpi


def load_nodes(dump_path: Path) -> list[dict]:
    """解析 uiautomator XML，返回可见节点的文本 / 描述 / 资源 id / bounds（px）。"""
    root = ET.parse(dump_path).getroot()
    nodes: list[dict] = []
    for el in root.iter("node"):
        bounds = parse_bounds(el.get("bounds", ""))
        if bounds is None:
            continue
        x1, y1, x2, y2 = bounds
        nodes.append(
            {
                "text": (el.get("text") or "").strip(),
                "desc": (el.get("content-desc") or "").strip(),
                "rid": (el.get("resource-id") or "").strip(),
                "w": x2 - x1,
                "h": y2 - y1,
            }
        )
    return nodes


def pick_node(nodes: list[dict], match: dict) -> dict | None:
    """按 text / desc / rid 找节点：先精确匹配，再包含匹配（忽略大小写）。"""
    for key in ("text", "desc", "rid"):
        needle = (match.get(key) or "").strip()
        if not needle:
            continue
        for node in nodes:
            if node[key] == needle:
                return node
        lowered = needle.lower()
        for node in nodes:
            if lowered in node[key].lower():
                return node
    return None


def web_value(scene: dict, path: str) -> float | None:
    """按 `a.b.c` 从 Web 读数里取值；叶子可以是数字，也可以是带 px 后缀的字符串。"""
    current: object = scene
    for part in path.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(part)  # type: ignore[assignment]
        if current is None:
            return None
    if isinstance(current, (int, float)):
        return float(current)
    if isinstance(current, str):
        m = re.match(r"^\s*(-?\d+(?:\.\d+)?)px\s*$", current)
        if m:
            return float(m.group(1))
    return None


def compare_item(scene: dict, nodes: list[dict], item: dict, density: float) -> dict:
    """单条比较：返回 {label, web, android, diff, ok, reason}。"""
    label = item.get("label") or item.get("web", "?")
    dim = item.get("dim", "h")
    if dim not in ("w", "h"):
        return {"label": label, "web": None, "android": None, "diff": None, "ok": False, "reason": f"不支持的 dim：{dim}"}
    path = item.get("web", "")
    web = web_value(scene, path)
    if web is None:
        # 允许 spec 只写到对象（如 `editor.paragraph`），此时按 dim 自动取 `w` / `h`。
        web = web_value(scene, f"{path}.{dim}")
    node = pick_node(nodes, item.get("match") or {})
    if web is None:
        return {"label": label, "web": None, "android": None, "diff": None, "ok": False, "reason": "Web 读数缺失"}
    if node is None:
        return {"label": label, "web": web, "android": None, "diff": None, "ok": False, "reason": "真机节点未匹配"}
    android = px_to_dp(node[dim], density)
    diff = abs(android - web)
    return {"label": label, "web": web, "android": android, "diff": diff, "ok": True, "reason": ""}


def render_markdown(rows: list[dict], tolerance: float, screen: str) -> str:
    """输出 Markdown 表 + 结论行；超差项标注「超差」。"""
    lines = [
        f"# P5 双端读数比对 · {screen}",
        "",
        f"容差：尺寸 ≤{tolerance}dp；以下数值单位为 dp（Web 为 computed style 的 css px）。",
        "",
        "| 项目 | Web | Android | 差值 | 判定 | 备注 |",
        "|---|---|---|---|---|---|",
    ]
    failures = 0
    for row in rows:
        if not row["ok"]:
            verdict = "无法比对"
            failures += 1
        elif row["diff"] <= tolerance:
            verdict = "一致"
        else:
            verdict = "**超差**"
            failures += 1
        web = "-" if row["web"] is None else f"{row['web']:.2f}"
        android = "-" if row["android"] is None else f"{row['android']:.2f}"
        diff = "-" if row["diff"] is None else f"{row['diff']:.2f}"
        lines.append(f"| {row['label']} | {web} | {android} | {diff} | {verdict} | {row['reason']} |")
    lines += ["", f"结论：{len(rows) - failures}/{len(rows)} 项在容差内。"]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="P5 双端读数比对（Web metrics vs 真机 uiautomator）")
    parser.add_argument("--web", required=True, help="web_parity_capture 产出的 metrics.json")
    parser.add_argument("--scene", required=True, help="metrics.json 里的场景 key，如 editor-390")
    parser.add_argument("--dump", required=True, help="adb shell uiautomator dump 得到的 XML")
    parser.add_argument("--density", type=float, required=True, help="设备 density（adb shell wm density），如 590")
    parser.add_argument("--spec", required=True, help="比对项 spec JSON")
    parser.add_argument("--out", default="", help="Markdown 报告输出路径；留空打印到 stdout")
    args = parser.parse_args()

    metrics = json.loads(Path(args.web).read_text(encoding="utf-8"))
    scene = metrics.get(args.scene)
    if not isinstance(scene, dict):
        print(f"error: metrics.json 缺少场景 {args.scene}", file=sys.stderr)
        raise SystemExit(2)
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    nodes = load_nodes(Path(args.dump))
    tolerance = float(spec.get("tolerance_dp", 1.0))
    rows = [compare_item(scene, nodes, item, args.density) for item in spec.get("items", [])]
    report = render_markdown(rows, tolerance, spec.get("screen") or args.scene)
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"report -> {args.out}")
    else:
        print(report)
    raise SystemExit(0 if all(row["ok"] and row["diff"] is not None and row["diff"] <= tolerance for row in rows) else 1)


if __name__ == "__main__":
    main()
