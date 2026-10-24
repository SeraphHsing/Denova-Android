#!/usr/bin/env python3
"""G3-d 切文件用例的延迟注入代理：只用于在真机上构造「保存仍在途」的时间窗口。

用途：LAN 上真实服务器写文件通常 30–60ms，`adb shell input` 一次点击也要 ~100ms，
真机无法稳定地在保存返回前切文件。本代理把 `PUT .../files/file` 的转发延后
`--put-delay-ms`，其余请求（读文件、鉴权）原样立即转发，于是「编辑 → 保存进行中 →
点打开切文件」可以在真机上按秒级节奏稳定复现。

它只做转发，不改变任何请求/响应内容，也不属于后端改动：

    # 在 18099 上监听，转发到真实服务器，PUT 保存延迟 3000ms
    python tools/g3d_slow_proxy.py --listen 192.168.2.101:18099 \\
        --target http://192.168.2.102:18081 --put-delay-ms 3000

然后把 App 的服务器地址改成代理地址（`192.168.2.101:18099`）做验证，验证完改回。
延迟只作用于保存，所以「打开文件」仍然是正常速度，切文件不必等慢请求。
"""

from __future__ import annotations

import argparse
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
}

CONFIG = {"target": "", "put_delay_ms": 0}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[proxy] " + (fmt % args) + "\n")

    def _relay(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        path = self.path
        is_save = self.command == "PUT" and "/files/file" in path
        if is_save and CONFIG["put_delay_ms"]:
            delay = CONFIG["put_delay_ms"] / 1000.0
            self.log_message("delaying %s %s by %.1fs", self.command, path, delay)
            time.sleep(delay)
        url = CONFIG["target"].rstrip("/") + path
        req = urllib.request.Request(url, data=body, method=self.command, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, payload, resp_headers = resp.status, resp.read(), list(resp.headers.items())
        except urllib.error.HTTPError as exc:
            status, payload, resp_headers = exc.code, exc.read(), list(exc.headers.items())
        except Exception as exc:  # 连不上真实服务器时把错误回给客户端，便于定位
            payload = f'{{"error":"proxy upstream failed: {exc}"}}'.encode()
            status, resp_headers = 502, [("Content-Type", "application/json")]
        self.send_response(status)
        for key, value in resp_headers:
            if key.lower() in HOP_BY_HOP:
                continue
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = _relay
    do_POST = _relay
    do_PUT = _relay
    do_PATCH = _relay
    do_DELETE = _relay


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="0.0.0.0:18099")
    ap.add_argument("--target", default="http://192.168.2.102:18081")
    ap.add_argument("--put-delay-ms", type=int, default=3000)
    args = ap.parse_args()

    host, _, port = args.listen.rpartition(":")
    CONFIG["target"] = args.target
    CONFIG["put_delay_ms"] = args.put_delay_ms
    server = ThreadingHTTPServer((host or "0.0.0.0", int(port)), Handler)
    print(
        f"proxy listening on {args.listen} -> {args.target} "
        f"(PUT /files/file delayed {args.put_delay_ms}ms)",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
