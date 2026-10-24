#!/usr/bin/env python3
"""G3 双端并发验证用的「第二客户端」：读文件、按 revision 保存、制造 409。

只做真实服务器上的读/写，用来与 Android 端并发修改同一文件：
    # 创建/重置一个测试文件
    python tools/g3_dual_editor.py reset <project_id> <path> --lines 5
    # 修改指定行（模拟 Web 端保存），打印新旧 revision
    python tools/g3_dual_editor.py edit <project_id> <path> --line 3 --text "脚本修改"
    # 只读查看当前内容
    python tools/g3_dual_editor.py show <project_id> <path>

凭证只从环境变量读取，不落盘：
    DENOVA_BASE / DENOVA_USER / DENOVA_PASS
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

BASE = os.environ.get("DENOVA_BASE", "http://192.168.2.102:18081").rstrip("/")

jar = CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def login() -> None:
    body = json.dumps({
        "username": os.environ.get("DENOVA_USER", ""),
        "password": os.environ.get("DENOVA_PASS", ""),
    }).encode()
    opener.open(urllib.request.Request(
        f"{BASE}/api/auth/login", data=body, method="POST",
        headers={"Content-Type": "application/json"}), timeout=15)


def read(project: str, path: str) -> dict:
    url = f"{BASE}/api/projects/{project}/files/file?path={path}&optional=true"
    payload = json.load(opener.open(url, timeout=20))
    return payload.get("document") or {}


def write(project: str, path: str, content: str, base_revision: str) -> dict:
    body = json.dumps({"path": path, "content": content, "base_revision": base_revision}).encode()
    req = urllib.request.Request(f"{BASE}/api/projects/{project}/files/file", data=body,
                                 method="PUT", headers={"Content-Type": "application/json"})
    try:
        return json.load(opener.open(req, timeout=30))
    except urllib.error.HTTPError as exc:
        print("HTTP", exc.code, exc.read().decode("utf-8", "replace")[:400])
        raise SystemExit(1)


def default_content(lines: int) -> str:
    out = ["# G3 并发测试文件", ""]
    for i in range(1, lines + 1):
        out.append(f"第 {i} 行：原始内容（不可改）。")
        out.append("")
    return "\n".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["reset", "edit", "show"])
    ap.add_argument("project_id")
    ap.add_argument("path")
    ap.add_argument("--lines", type=int, default=5)
    ap.add_argument("--line", type=int, default=3)
    ap.add_argument("--text", default="脚本在这一行写入的新内容")
    ap.add_argument("--heading", action="store_true", help="改写标题行（制造与标题处编辑的重叠冲突）")
    ap.add_argument("--prepend", action="store_true", help="在标题行开头插入文本（与 App 起始位置插入重叠）")
    args = ap.parse_args()

    login()

    if args.action == "reset":
        content = default_content(args.lines)
        doc = read(args.project_id, args.path)
        rev = doc.get("revision") or "missing"
        resp = write(args.project_id, args.path, content, rev)
        print(f"reset {args.path} rev={resp.get('revision')} sha={hashlib.sha256(content.encode()).hexdigest()[:16]}")
        return

    doc = read(args.project_id, args.path)
    if not doc:
        raise SystemExit("文件不存在，先执行 reset")
    content = doc.get("content") or ""
    rev = doc.get("revision") or ""

    if args.action == "show":
        print(f"rev={rev} len={len(content)}")
        print(content)
        return

    lines = content.split("\n")
    if args.prepend:
        for i, line in enumerate(lines):
            if line.startswith("# "):
                lines[i] = f"{args.text}{line}"
                break
        else:
            raise SystemExit("未找到标题行（以 '# ' 开头）")
    elif args.heading:
        # 改写标题行：与 App 在正文起始处的编辑落在同一行，制造重叠冲突
        for i, line in enumerate(lines):
            if line.startswith("# "):
                lines[i] = f"{line}（脚本改）"
                break
        else:
            raise SystemExit("未找到标题行（以 '# ' 开头）")
    else:
        # 目标行按「第 N 行：…」定位，避免空行索引歧义
        target = f"第 {args.line} 行："
        for i, line in enumerate(lines):
            if line.startswith(target):
                lines[i] = f"{target}{args.text}"
                break
        else:
            raise SystemExit(f"未找到从 '{target}' 开始的行")
    new_content = "\n".join(lines)
    resp = write(args.project_id, args.path, new_content, rev)
    print(f"edited line={args.line} old_rev={rev[:16]} new_rev={resp.get('revision', '')[:16]}")


if __name__ == "__main__":
    main()
