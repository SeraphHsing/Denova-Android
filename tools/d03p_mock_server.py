#!/usr/bin/env python3
"""D03-P 终端性能重放服务（mock）。

按 `docs/android-web-parity/06-development-and-validation.md` §4 冻结口径回放：
- PTY 输出速率 64 KB/s（1 KB / 16 ms）
- 持续 300 s
- 内容含颜色（SGR 16/256/truecolor）、光标定位、换行滚屏、CJK 宽字

同时把客户端需要的 REST 端点也 mock 掉（登录状态 / 会话列表 / 创建 / 删除），
因此可以完全离线跑：`adb reverse tcp:8088 tcp:8088`，App 里服务器填 `127.0.0.1:8088`。

用法：
    python tools/d03p_mock_server.py [port] [duration_seconds]

服务端会打印 pacing 统计（实际字节数 / 实际速率）与收到的 resize / input 时间点，
用于和客户端埋点（`TerminalPerf`）对账。
"""

from __future__ import annotations

import asyncio
import json
import sys
import time

from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.http11 import Response

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8088
DURATION = float(sys.argv[2]) if len(sys.argv) > 2 else 300.0

CHUNK = 1024
INTERVAL = 0.016  # 1 KB / 16 ms = 62.5 KiB/s ≈ 64 KB/s
SESSION_ID = "d03p-session"
OWNER = "android-main"
PROJECT = "d03p"
TOKEN = "d03p-token"

COLS, ROWS = 80, 24


def build_payloads() -> list[bytes]:
    """生成若干个 1024 字节的帧，循环发送。

    每帧都是完整可解析的片段（SGR + 文本 + 光标定位 + 换行），
    其中一部分故意跨越 CJK 宽字边界，用来压增量 UTF-8 解码。
    """
    payloads: list[bytes] = []
    palette = [31, 32, 33, 34, 35, 36, 91, 92, 93, 94]
    for i in range(16):
        parts: list[str] = []
        row = (i % ROWS) + 1
        parts.append(f"\x1b[{row};1H\x1b[{palette[i % len(palette)]}m")
        line = 0
        while True:
            line += 1
            body = (
                f"d03p line {i:02d}-{line:03d} "
                f"color={palette[(i + line) % len(palette)]} "
                f"中文宽字测试·终端性能 "
                f"\x1b[38;5;{100 + (line % 100)}m256色\x1b[0m "
                f"\x1b[38;2;{line % 255};{(line * 7) % 255};{(line * 13) % 255}mtruecolor\x1b[0m"
            )
            parts.append(body)
            parts.append("\r\n")
            chunk = "".join(parts).encode("utf-8")
            if len(chunk) >= CHUNK:
                break
        data = chunk[:CHUNK]
        # 补齐到 1024 字节（用空格，保持字节对齐）
        if len(data) < CHUNK:
            data = data + b" " * (CHUNK - len(data))
        payloads.append(data)
    return payloads


PAYLOADS = build_payloads()


def json_response(status: int, payload: dict) -> Response:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = Headers()
    headers["Content-Type"] = "application/json; charset=utf-8"
    headers["Content-Length"] = str(len(body))
    headers["Set-Cookie"] = "denova_d03p=1; Path=/"
    return Response(status, "OK", headers, body)


def session_info() -> dict:
    return {
        "id": SESSION_ID,
        "owner_tab_id": OWNER,
        "profile_id": "shell",
        "title": "d03p",
        "command": "/bin/bash",
        "cwd": "/d03p",
        "cols": COLS,
        "rows": ROWS,
        "project_id": PROJECT,
        "attached": 1,
        "exited": False,
        "exit_code": 0,
        "exit_error": None,
        "token": TOKEN,
    }


def process_request(connection, request):
    path = request.path.split("?")[0]
    if path == "/api/auth/status":
        return json_response(200, {"local": False, "authenticated": True, "lanUrl": None})
    if path == "/api/auth/login":
        return json_response(200, {"local": False, "authenticated": True})
    if path == "/api/auth/pair":
        return json_response(200, {"local": False, "authenticated": True})
    if path == "/api/auth/logout":
        return Response(204, "No Content", Headers(), b"")
    if path == "/api/terminal/sessions":
        return json_response(
            200,
            {
                "enabled": True,
                "shell": "/bin/bash",
                "commands": [{"id": "shell", "name": "Shell"}],
                "default_cwd": "/d03p",
                "max_sessions": 8,
                "scrollback_kb": 1024,
                "sessions": [session_info()],
            },
        )
    if path.startswith("/api/projects/") and path.endswith("/terminal/sessions"):
        return json_response(200, session_info())
    if path.endswith("/attach"):
        # 交给 WebSocket 握手处理
        return None
    if path.startswith("/api/terminal/sessions/"):
        return Response(204, "No Content", Headers(), b"")
    return json_response(404, {"error": "not found", "code": "not_found"})


async def stream(connection) -> None:
    # 先按后端契约回放 ready（此时还未发 PTY 字节）
    await connection.send(json.dumps({"type": "ready", "session": session_info()}, ensure_ascii=False))

    started = time.monotonic()
    deadline = started + DURATION
    sent_frames = 0
    sent_bytes = 0
    next_slot = started

    while time.monotonic() < deadline:
        next_slot += INTERVAL
        payload = PAYLOADS[sent_frames % len(PAYLOADS)]
        await connection.send(payload)
        sent_frames += 1
        sent_bytes += len(payload)

        now = time.monotonic()
        if next_slot > now:
            await asyncio.sleep(next_slot - now)
        else:
            # 落后时不补发，避免突发；只把 slot 拉回当前时间
            next_slot = now

        if sent_frames % 625 == 0:  # 每 10 s
            elapsed = now - started
            rate = sent_bytes / elapsed / 1024 if elapsed > 0 else 0
            print(
                f"[d03p] t={elapsed:6.1f}s frames={sent_frames} bytes={sent_bytes} "
                f"rate={rate:6.1f}KiB/s",
                flush=True,
            )

    elapsed = time.monotonic() - started
    print(
        f"[d03p] stream done: frames={sent_frames} bytes={sent_bytes} "
        f"elapsed={elapsed:.1f}s rate={sent_bytes / elapsed / 1024:.1f}KiB/s",
        flush=True,
    )
    # 回放结束后保持连接（客户端心跳继续），不主动关闭，便于外部采样 PSS
    await asyncio.sleep(3600)


async def receive(connection) -> None:
    async for message in connection:
        if isinstance(message, bytes):
            preview = message[:40].decode("utf-8", "replace").replace("\r", "\\r").replace("\n", "\\n")
            print(f"[d03p] input {len(message)}B {preview!r}", flush=True)
            # 回显，供客户端测「输入 → 可见」延迟
            await connection.send(b"\r\n" + message + b"\r\n$ ")
            continue
        try:
            control = json.loads(message)
        except json.JSONDecodeError:
            continue
        kind = control.get("type")
        if kind == "ping":
            await connection.send(json.dumps({"type": "pong"}))
        elif kind == "resize":
            print(f"[d03p] resize cols={control.get('cols')} rows={control.get('rows')}", flush=True)
        elif kind == "input":
            data = str(control.get("data", ""))
            print(f"[d03p] input(text) {data!r}", flush=True)
            await connection.send(b"\r\n" + data.encode("utf-8") + b"\r\n$ ")


async def handler(connection) -> None:
    print(f"[d03p] attach from {connection.remote_address}", flush=True)
    sender = asyncio.create_task(stream(connection))
    receiver = asyncio.create_task(receive(connection))
    try:
        await asyncio.wait({sender, receiver}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in (sender, receiver):
            task.cancel()


async def main() -> None:
    print(
        f"[d03p] listening on {PORT}  chunk={CHUNK}B interval={INTERVAL * 1000:.0f}ms "
        f"duration={DURATION:.0f}s session={SESSION_ID}",
        flush=True,
    )
    async with serve(handler, "0.0.0.0", PORT, process_request=process_request, max_size=None):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("[d03p] stopped", flush=True)
