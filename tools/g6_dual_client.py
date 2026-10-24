#!/usr/bin/env python3
"""G6 双端共存验证用的「第二客户端」。

用真实后端（默认 http://192.168.2.102:18081）模拟另一台客户端：
列会话、attach 同一会话、发输入、删除会话、占用 owner。

凭证从环境变量读取，避免写进仓库/脚本：
    DENOVA_BASE=http://192.168.2.102:18081
    DENOVA_USER=...
    DENOVA_PASS=...

用法：
    python tools/g6_dual_client.py ls
    python tools/g6_dual_client.py attach <session_id> --seconds 15 --echo G6_B
    python tools/g6_dual_client.py delete <session_id>
    python tools/g6_dual_client.py create <project_id> --owner android-main
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

BASE = os.environ.get("DENOVA_BASE", "http://192.168.2.102:18081").rstrip("/")
USER = os.environ.get("DENOVA_USER", "")
PASSWORD = os.environ.get("DENOVA_PASS", "")

jar = CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def cookie_header() -> str:
    return "; ".join(f"{c.name}={c.value}" for c in jar)


def request(method: str, path: str, body: dict | None = None) -> tuple[int, str]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with opener.open(req, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def login() -> None:
    if not USER or not PASSWORD:
        sys.exit("需要 DENOVA_USER / DENOVA_PASS 环境变量")
    status, text = request("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if status != 200:
        sys.exit(f"登录失败 {status}: {text[:200]}")
    print(f"[g6] logged in as {USER}")


def sessions() -> list[dict]:
    status, text = request("GET", "/api/terminal/sessions")
    if status != 200:
        sys.exit(f"列会话失败 {status}: {text[:200]}")
    return json.loads(text).get("sessions", [])


def cmd_ls(_args: argparse.Namespace) -> None:
    items = sessions()
    print(f"[g6] sessions={len(items)}")
    for s in items:
        token = s.get("token") or ""
        print(
            f"  id={s['id']} owner={s.get('owner_tab_id')} project={s.get('project_id')} "
            f"attached={s.get('attached')} exited={s.get('exited')} "
            f"token={token[:8]}… size={s.get('cols')}x{s.get('rows')}"
        )


def cmd_projects(_args: argparse.Namespace) -> None:
    # 项目作用域的权威列表在 automations catalog 里（含 project_id）
    for path in ("/api/automations/catalog", "/api/projects"):
        status, text = request("GET", path)
        if status != 200 or not text.strip():
            print(f"[g6] {path} -> {status}")
            continue
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            print(f"[g6] {path} 非 JSON")
            continue
        items = data if isinstance(data, list) else data.get("projects", [])
        if not items:
            continue
        print(f"[g6] projects={len(items)} (from {path})")
        for p in items:
            print(f"  id={p.get('project_id') or p.get('id')} title={p.get('title') or p.get('name')}")
        return
    sys.exit("未能取得项目列表")


def cmd_delete(args: argparse.Namespace) -> None:
    status, text = request("DELETE", f"/api/terminal/sessions/{args.session_id}")
    print(f"[g6] delete {args.session_id} -> {status} {text[:120]}")


def cmd_create(args: argparse.Namespace) -> None:
    path = f"/api/projects/{args.project_id}/terminal/sessions"
    body = {
        "owner_tab_id": args.owner,
        "profile_id": "shell",
        "cols": 80,
        "rows": 24,
    }
    status, text = request("POST", path, body)
    print(f"[g6] create project={args.project_id} owner={args.owner} -> {status}")
    print(text[:400])


async def _attach(session_id: str, token: str, seconds: float, echo: str | None) -> None:
    import websockets

    scheme = "wss" if BASE.startswith("https") else "ws"
    host = BASE.split("://", 1)[1]
    url = f"{scheme}://{host}/api/terminal/sessions/{session_id}/attach?token={token}"

    headers = {"Cookie": cookie_header()}
    try:
        connector = websockets.connect(url, additional_headers=headers, max_size=None)
    except TypeError:  # 旧版 websockets 用 extra_headers
        connector = websockets.connect(url, extra_headers=headers, max_size=None)

    async with connector as ws:
        print(f"[g6] attached {session_id}")
        sent_echo = False
        binary_bytes = 0
        binary_frames = 0
        deadline = asyncio.get_event_loop().time() + seconds
        while asyncio.get_event_loop().time() < deadline:
            timeout = max(0.1, deadline - asyncio.get_event_loop().time())
            try:
                message = await asyncio.wait_for(ws.recv(), timeout=timeout)
            except asyncio.TimeoutError:
                break
            except Exception as exc:  # 包含静默关闭
                print(f"[g6] socket closed: {type(exc).__name__}: {exc}")
                break
            if isinstance(message, bytes):
                binary_bytes += len(message)
                binary_frames += 1
                continue
            print(f"[g6] text frame: {message[:160]}")
            if '"ready"' in message and echo and not sent_echo:
                await ws.send(f"echo {echo}\r".encode())
                sent_echo = True
                print(f"[g6] sent echo {echo}")
        print(f"[g6] binary frames={binary_frames} bytes={binary_bytes}")


def cmd_attach(args: argparse.Namespace) -> None:
    items = sessions()
    target = next((s for s in items if s["id"] == args.session_id), None)
    if target is None:
        sys.exit(f"会话不存在: {args.session_id}")
    asyncio.run(_attach(args.session_id, target.get("token", ""), args.seconds, args.echo))


def main() -> None:
    parser = argparse.ArgumentParser(description="G6 第二客户端")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("ls").set_defaults(func=cmd_ls)
    sub.add_parser("projects").set_defaults(func=cmd_projects)

    p_attach = sub.add_parser("attach")
    p_attach.add_argument("session_id")
    p_attach.add_argument("--seconds", type=float, default=15.0)
    p_attach.add_argument("--echo", default=None)
    p_attach.set_defaults(func=cmd_attach)

    p_delete = sub.add_parser("delete")
    p_delete.add_argument("session_id")
    p_delete.set_defaults(func=cmd_delete)

    p_create = sub.add_parser("create")
    p_create.add_argument("project_id")
    p_create.add_argument("--owner", default="android-main")
    p_create.set_defaults(func=cmd_create)

    args = parser.parse_args()
    login()
    args.func(args)


if __name__ == "__main__":
    main()
