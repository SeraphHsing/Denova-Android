#!/usr/bin/env python3
"""P5 逐屏真机采集：390dp 覆写下逐屏截图 + 控件树。

用法：
    python tools/p5_android_capture.py                 # 采集默认 10 屏
    python tools/p5_android_capture.py --only lore     # 只采某一屏
    python tools/p5_android_capture.py --keep-size     # 不覆写/不恢复分辨率

流程：覆写 `wm size 1463x3200` + `wm density 600`（390.13dp 宽）→ 逐屏「打开抽屉 → 点一级入口 →
截图 + dump」→ 恢复 `wm size reset` / `wm density reset`。

约束：
- 只做只读导航，不输入任何凭证或正文；
- 抽屉项可能不在首屏，找不到时先向上滚动再找；
- 单屏失败不中断整轮，最后统一汇总失败列表（便于补采）。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "evidence" / "p5-screens"
DUMP = "/sdcard/p5_capture.xml"
SCREEN = "/sdcard/p5_capture.png"

# 屏名 → 抽屉里的一级入口文案（与 values-zh/strings.xml 的 dest_* 对齐）。
SCREENS: list[tuple[str, str]] = [
    ("workbench", "工作台"),
    ("game", "游戏"),
    ("agent", "写作 Agent"),
    ("debug", "调试日志"),
    ("terminal", "写作"),
    ("lore", "资料库"),
    ("presets", "创作方案"),
    ("versions", "版本管理"),
    ("books", "书籍管理"),
    ("skills", "Skills"),
    ("agents", "Agents"),
    ("automations", "自动化"),
    ("market", "资源中心"),
    ("settings", "设置"),
]

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def dump_root() -> ET.Element:
    (ROOT / "build").mkdir(exist_ok=True)
    adb("shell", "uiautomator", "dump", DUMP)
    adb("pull", DUMP, str(ROOT / "build" / "p5_capture.xml"))
    return ET.parse(ROOT / "build" / "p5_capture.xml").getroot()


def center(bounds: str) -> tuple[int, int] | None:
    match = BOUNDS_RE.match(bounds or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(value) for value in match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_tap(root: ET.Element, predicate) -> tuple[int, int] | None:
    """按文案/语义找可点坐标。

    Compose 的图标按钮与抽屉行常把 `clickable` 放在父节点，带 `content-desc`/`text` 的子节点本身
    `clickable=false`（真机 dump 确认）。因此这里不要求 clickable，直接点文本节点中心即可命中父级。
    """
    fallback: tuple[int, int] | None = None
    for node in root.iter("node"):
        if not predicate(node):
            continue
        point = center(node.get("bounds", ""))
        if not point:
            continue
        if node.get("clickable") == "true":
            return point
        fallback = fallback or point
    return fallback


def open_drawer() -> bool:
    root = dump_root()
    point = find_tap(
        root,
        lambda node: "导航" in (node.get("content-desc") or "") or "菜单" in (node.get("content-desc") or ""),
    )
    if not point:
        return False
    adb("shell", "input", "tap", str(point[0]), str(point[1]))
    time.sleep(1.0)
    return True


def tap_drawer_item(label: str) -> bool:
    # 抽屉会保留上次滚动位置，先回顶部再向下找，避免「上一轮停在中部」导致漏项。
    for _ in range(2):
        adb("shell", "input", "swipe", "600", "1400", "600", "2900", "260")
        time.sleep(0.5)
    for _ in range(10):
        root = dump_root()
        point = find_tap(root, lambda node: (node.get("text") or "").strip() == label)
        if point:
            adb("shell", "input", "tap", str(point[0]), str(point[1]))
            time.sleep(1.6)
            # 抽屉项多、行高 52dp 时点击可能落在行间空隙：抽屉没关就再点一次同坐标。
            if find_tap(dump_root(), lambda node: (node.get("content-desc") or "") == "关闭导航菜单"):
                adb("shell", "input", "tap", str(point[0]), str(point[1]))
                time.sleep(1.6)
            return True
        # 目标在抽屉下方：整屏向上滚动后再找。
        adb("shell", "input", "swipe", "600", "2800", "600", "1400", "320")
        time.sleep(0.6)
    root = dump_root()
    seen = [ (node.get("text") or "").strip() for node in root.iter("node") ]
    print(f"  [debug] '{label}' not found; visible texts: "
          + " | ".join(t for t in seen if t)[:400], file=sys.stderr)
    return False


def tap_text(label: str, attempts: int = 3) -> bool:
    """在当前页面找文本并点击（用于编辑器顶栏「终端」这类非抽屉入口）。"""
    for _ in range(attempts):
        root = dump_root()
        point = find_tap(root, lambda node: (node.get("text") or "").strip() == label)
        if point:
            adb("shell", "input", "tap", str(point[0]), str(point[1]))
            time.sleep(1.8)
            return True
        time.sleep(0.8)
    return False


def capture(name: str) -> None:
    folder = OUT / name
    folder.mkdir(parents=True, exist_ok=True)
    adb("shell", "screencap", "-p", SCREEN)
    adb("pull", SCREEN, str(folder / "android-390.png"))
    adb("pull", DUMP, str(folder / "android-390.xml"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    parser.add_argument("--keep-size", action="store_true")
    args = parser.parse_args()

    targets = [item for item in SCREENS if not args.only or item[0] == args.only]
    if not targets:
        print(f"unknown screen: {args.only}", file=sys.stderr)
        return 2

    if not args.keep_size:
        adb("shell", "wm", "size", "1463x3200")
        adb("shell", "wm", "density", "600")
        time.sleep(1.0)

    failures: list[str] = []
    try:
        for name, label in targets:
            if not open_drawer():
                failures.append(f"{name}: drawer not opened")
                continue
            if not tap_drawer_item(label):
                failures.append(f"{name}: item '{label}' not found")
                adb("shell", "input", "keyevent", "4")
                continue
            # 终端没有抽屉一级入口：从编辑器顶栏「写作 → 终端」进入（NAV/SYS 口径同基线）。
            if name == "terminal" and not tap_text("终端"):
                failures.append(f"{name}: editor action '终端' not found")
                continue
            capture(name)
            print(f"captured {name} ({label})")
    finally:
        if not args.keep_size:
            adb("shell", "wm", "size", "reset")
            adb("shell", "wm", "density", "reset")
            time.sleep(1.0)
            print("size/density reset:", adb("shell", "wm", "size").strip(), adb("shell", "wm", "density").strip())

    if failures:
        print("failed:", *failures, sep="\n  ")
        return 1
    print("all screens captured")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
