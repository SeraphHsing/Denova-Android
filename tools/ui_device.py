#!/usr/bin/env python3
"""真机 UI 自动化小工具（uiautomator dump + input）。

    python tools/ui_device.py nodes [--filter 文本]   # 列出可见节点的 text / bounds / clickable
    python tools/ui_device.py tap 文本 [--index N]     # 点第一个 text 完全等于该文本的节点（退化为包含匹配）
    python tools/ui_device.py tapxy X Y
    python tools/ui_device.py text 文本                # 输入 ASCII 文本（不含空格）
    python tools/ui_device.py key 4                    # 发一个 keyevent
    python tools/ui_device.py shot 文件名               # 截图到本地路径
    python tools/ui_device.py log [tag...]              # 抓 DenovaLog（默认 Lore）

前置：设备已连接；开发者选项已开启「允许通过 USB 调试修改权限或模拟点击」。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def adb(*args: str, capture: bool = True) -> str:
    result = subprocess.run(
        ["adb", *args],
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {result.stderr or result.stdout}")
    return result.stdout


def dump_nodes() -> list[dict]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    start = xml.find("<?xml")
    if start > 0:
        xml = xml[start:]
    root = ET.fromstring(xml)
    nodes = []
    for node in root.iter("node"):
        bounds = node.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            continue
        x1, y1, x2, y2 = (int(value) for value in match.groups())
        nodes.append(
            {
                "text": node.get("text", ""),
                "desc": node.get("content-desc", ""),
                "cls": node.get("class", ""),
                "clickable": node.get("clickable") == "true",
                "enabled": node.get("enabled") == "true",
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                "bounds": bounds,
            }
        )
    return nodes


def label(node: dict) -> str:
    return node["text"] or node["desc"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    nodes_cmd = sub.add_parser("nodes")
    nodes_cmd.add_argument("--filter", default="")

    tap_cmd = sub.add_parser("tap")
    tap_cmd.add_argument("label")
    tap_cmd.add_argument("--index", type=int, default=0)

    tapxy_cmd = sub.add_parser("tapxy")
    tapxy_cmd.add_argument("x", type=int)
    tapxy_cmd.add_argument("y", type=int)

    text_cmd = sub.add_parser("text")
    text_cmd.add_argument("value")

    key_cmd = sub.add_parser("key")
    key_cmd.add_argument("code")

    shot_cmd = sub.add_parser("shot")
    shot_cmd.add_argument("path")

    log_cmd = sub.add_parser("log")
    log_cmd.add_argument("tags", nargs="*", default=["Lore"])
    log_cmd.add_argument("--lines", type=int, default=300)

    args = parser.parse_args()

    if args.command == "nodes":
        for node in dump_nodes():
            text = label(node)
            if not text:
                continue
            if args.filter and args.filter not in text:
                continue
            flag = "C" if node["clickable"] else " "
            state = "" if node["enabled"] else " disabled"
            print(f"{flag} {text!r} {node['bounds']}{state}")
        return

    if args.command == "tap":
        candidates = [node for node in dump_nodes() if label(node) == args.label]
        if not candidates:
            candidates = [node for node in dump_nodes() if args.label in label(node)]
        if not candidates:
            raise SystemExit(f"no node labelled {args.label!r}")
        node = candidates[min(args.index, len(candidates) - 1)]
        x, y = node["center"]
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped {label(node)!r} at {x},{y}")
        return

    if args.command == "tapxy":
        adb("shell", "input", "tap", str(args.x), str(args.y))
        return

    if args.command == "text":
        adb("shell", "input", "text", args.value)
        return

    if args.command == "key":
        adb("shell", "input", "keyevent", args.code)
        return

    if args.command == "shot":
        with open(args.path, "wb") as handle:
            result = subprocess.run(
                ["adb", "exec-out", "screencap", "-p"],
                stdout=handle,
            )
        raise SystemExit(result.returncode)

    if args.command == "log":
        tags = args.tags or ["Lore"]
        command = ["logcat", "-d", "-v", "brief"]
        for tag in tags:
            command.extend(["-s", tag])
        output = adb("shell", *command)
        lines = output.splitlines()[-args.lines :]
        print("\n".join(lines))


if __name__ == "__main__":
    main()
