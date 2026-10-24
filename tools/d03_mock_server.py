#!/usr/bin/env python3
"""D03-S 固定负载 SSE 重放服务（仅用于客户端性能测量，不参与产品运行）。

口径来自 docs/android-web-parity/06-development-and-validation.md：
  流 D03-S：固定分块 1KB、速率 100 块/s、持续 60s（录制真实响应后重放）。
自定义实现按同一口径合成负载：每块 1KB（UTF-8 字节）+ 100 块/秒 + 60 秒，
总计 6000 块 / 6MB / 约 200 万字符。

用法：
  python tools/d03_mock_server.py --port 8765
  adb reverse tcp:8765 tcp:8765        # 手机可通过 http://127.0.0.1:8765 访问本机
  应用内服务器地址填 http://127.0.0.1:8765  → 连接 → 登录（任意账号）→ 游戏 → 挂接

实现的是最小契约面：
  GET  /api/auth/status
  POST /api/auth/login
  GET  /api/interactive/stories/{id}/history   -> {"turns": []}
  GET  /api/interactive/chat/active            -> running + task_id
  GET  /api/interactive/chat/stream            -> SSE（checkpoint → 6000 chunk → committed → done）
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

UNIT = "夜色沉入河谷，风穿过石桥，远处钟声敲过三下。"


def make_block(nbytes: int) -> str:
    """构造恰好 ≤ nbytes 个 UTF-8 字节的块内容（按字符边界截断，CJK 约 3 字节/字）。"""
    unit = UNIT.encode("utf-8")
    repeat = nbytes // len(unit) + 2
    return (UNIT * repeat).encode("utf-8")[:nbytes].decode("utf-8", "ignore")


class Handler(BaseHTTPRequestHandler):
    # SSE 用 HTTP/1.0 + Connection: close，客户端读到 EOF 即结束，避免 chunked 处理
    protocol_version = "HTTP/1.0"

    block_bytes = 1024
    rate = 100
    duration = 60
    block_text = ""
    requests_seen = 0

    def log_message(self, fmt, *args):  # 静默，避免刷屏影响时序
        pass

    def _send_json(self, obj, status=200, cookie=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if urlparse(self.path).path in ("/api/auth/login", "/api/auth/pair"):
            self._send_json(
                {"local": False, "authenticated": True, "lanUrl": None},
                cookie="denova_mock=1; Path=/",
            )
            return
        self._send_json({})

    def do_GET(self):
        path = urlparse(self.path).path
        Handler.requests_seen += 1
        if path == "/api/auth/status":
            self._send_json({"local": True, "authenticated": True, "lanUrl": None})
            return
        if path.startswith("/api/interactive/stories/") and path.endswith("/history"):
            self._send_json({"turns": []})
            return
        if path == "/api/interactive/chat/active":
            self._send_json(
                {
                    "active": True,
                    "task_id": "d03-mock-task",
                    "stream_cursor": 0,
                    "status": "running",
                    "phase": "running",
                    "recovery_actions": [],
                    "runtime_recoverable": False,
                    "recovery_paused": False,
                    "stream_attached": True,
                }
            )
            return
        if path == "/api/interactive/chat/stream":
            self._stream()
            return
        self._send_json({})

    def _stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        state = {"cursor": 0}

        def emit(event_type: str, data: dict, with_id: bool = True):
            if with_id:
                state["cursor"] += 1
            frame = ""
            if with_id:
                frame += f"id: {state['cursor']}\n"
            frame += f"event: {event_type}\n"
            frame += f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
            self.wfile.write(frame.encode("utf-8"))
            self.wfile.flush()

        try:
            emit("agent_cycle_started", {"turn_id": "d03-mock"}, with_id=False)
            emit("task_checkpoint", {"version": 1, "task_id": "d03-mock-task", "cursor": 0}, with_id=False)

            total = self.rate * self.duration
            interval = 1.0 / self.rate
            start = time.monotonic()
            for i in range(total):
                target = start + i * interval
                delay = target - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                emit("chunk", {"content": self.block_text})

            emit("task_checkpoint_committed", {"cursor": state["cursor"]})
            emit("done", {})
        except (BrokenPipeError, ConnectionResetError):
            pass  # 客户端提前断开（如 force-stop）属预期
        finally:
            self.close_connection = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--block-bytes", type=int, default=1024)
    parser.add_argument("--rate", type=int, default=100)
    parser.add_argument("--duration", type=int, default=60)
    args = parser.parse_args()

    Handler.block_bytes = args.block_bytes
    Handler.rate = args.rate
    Handler.duration = args.duration
    Handler.block_text = make_block(args.block_bytes)

    total = args.rate * args.duration
    print(
        f"D03-S mock ready: {args.host}:{args.port} "
        f"block={args.block_bytes}B chars={len(Handler.block_text)} rate={args.rate}/s "
        f"duration={args.duration}s total_blocks={total} total_bytes={total * args.block_bytes}",
        flush=True,
    )
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
