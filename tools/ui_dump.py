#!/usr/bin/env python3
"""真机 UI 取证辅助：dump 当前界面并打印「文案 / 语义 / 坐标」，供 P5 双端对照使用。

用法：
    python tools/ui_dump.py                 # 打印全部带 text 或 content-desc 的节点
    python tools/ui_dump.py --clickable     # 只打印可点击节点
    python tools/ui_dump.py --grep 命令      # 按文案/语义过滤（不区分大小写）
    python tools/ui_dump.py --raw out.xml   # 保留原始 dump 到指定路径

设计取舍：只做只读查询，不注入点击；坐标由调用方显式使用，避免误触。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

# Windows 控制台默认 GBK，中文文案会变乱码；统一按 UTF-8 输出（调用方配合 chcp 65001）。
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass


def dump_to(path: str) -> None:
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui_dump.xml"],
                   check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["adb", "pull", "/sdcard/ui_dump.xml", path],
                   check=True, stdout=subprocess.DEVNULL)


def center(bounds: str) -> tuple[int, int] | None:
    match = BOUNDS_RE.match(bounds or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(value) for value in match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clickable", action="store_true")
    parser.add_argument("--all", action="store_true", help="连同没有文案的节点一起列出")
    parser.add_argument("--checkable", action="store_true", help="只列可勾选控件（开关/复选框）")
    parser.add_argument("--grep", default="")
    parser.add_argument("--y-range", default="", help="只保留中心 y 落在 min,max 区间内的节点")
    parser.add_argument("--raw", default="")
    parser.add_argument("--file", default="", help="解析已有 dump 文件，不再重新抓取")
    args = parser.parse_args()

    path = args.file or args.raw or os.path.join(tempfile.gettempdir(), "ui_dump.xml")
    if not args.file:
        dump_to(path)

    root = ET.parse(path).getroot()
    needle = args.grep.lower()
    y_min, y_max = (int(part) for part in args.y_range.split(",")) if args.y_range else (None, None)
    printed = 0
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        clickable = node.get("clickable") == "true"
        if args.clickable and not clickable:
            continue
        if args.checkable and node.get("checkable") != "true":
            continue
        if not text and not desc:
            # 图标按钮常常只有图标没有文案；列 clickable 时仍要能看见它的坐标。
            if not (args.all or args.checkable or (args.clickable and clickable)):
                continue
            text = f"<{node.get('class', '').rsplit('.', 1)[-1]}>"
        haystack = f"{text} {desc}".lower()
        if needle and needle not in haystack:
            continue
        point = center(node.get("bounds", ""))
        if y_min is not None and point is not None and not (y_min <= point[1] <= y_max):
            continue
        label = text or f"[{desc}]"
        if node.get("checkable") == "true":
            label = f"{label} ({'on' if node.get('checked') == 'true' else 'off'})"
        if len(label) > 60:
            label = f"{label[:57]}…"
        flags = "TAP" if clickable else "   "
        where = f"{point[0]},{point[1]}" if point else "-"
        print(f"{flags} {where:>10}  {label}")
        printed += 1
    print(f"-- {printed} node(s); dump={path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
