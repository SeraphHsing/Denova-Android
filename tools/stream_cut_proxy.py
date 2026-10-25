"""SSE 断线注入代理（AGT-009）。

把 `/api/.../chat/stream` 的响应转发 N 字节后直接断开，模拟「读流时网络中断」；
前 `--cuts` 次命中生效，之后正常透传，便于验证客户端「重连后再次断线仍能恢复」。

`--cut-mode` 区分两种断开方式，客户端表现并不相同，两种都要测：
- `close`：写完半帧后正常 FIN（中间设备 / 反代关闭连接的样子），客户端读到干净 EOF；
- `reset`：SO_LINGER=0 强制 RST（真断网 / 链路丢包的样子），客户端读到异常。

用法：
  python tools/stream_cut_proxy.py --listen 0.0.0.0:18098 --target http://192.168.2.102:18081 \
      --path /chat/stream --cut-after-bytes 2048 --cuts 2 --cut-mode reset
"""

from __future__ import annotations

import argparse
import http.client
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

CUTS_LEFT = 0
LOCK = threading.Lock()
LOG_PATH = None


def log(message: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {message}"
    print(line, flush=True)
    if LOG_PATH:
        with open(LOG_PATH, 'a', encoding='utf-8') as handle:
            handle.write(line + '\n')


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    target = ''
    cut_path = '/chat/stream'
    cut_after_bytes = 2048
    cut_mode = 'close'

    def do_GET(self) -> None:
        self._forward('GET')

    def do_POST(self) -> None:
        self._forward('POST')

    def do_PATCH(self) -> None:
        self._forward('PATCH')

    def do_DELETE(self) -> None:
        self._forward('DELETE')

    def log_message(self, fmt, *args):  # noqa: A003 - 静默默认访问日志
        return

    def _forward(self, method: str) -> None:
        parsed = urlparse(self.target)
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() != 'host'}
        conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=300)
        try:
            conn.request(method, self.path, body=body, headers=headers)
            upstream = conn.getresponse()
        except Exception as exc:  # 上游不可达时如实返回 502
            self.send_response(502)
            self.end_headers()
            log(f"upstream error {method} {self.path}: {exc}")
            return

        streaming = 'text/event-stream' in (upstream.getheader('Content-Type') or '')
        content_type = upstream.getheader('Content-Type') or '-'
        log(f"{method} {self.path} -> {upstream.status} ct={content_type} streaming={streaming}")
        should_cut = False
        if method in ('GET', 'POST') and self.cut_path in self.path and streaming:
            global CUTS_LEFT
            with LOCK:
                if CUTS_LEFT > 0:
                    CUTS_LEFT -= 1
                    should_cut = True
            if should_cut:
                log(f"cut stream {self.path} after {self.cut_after_bytes} bytes (cuts left={CUTS_LEFT})")

        self.send_response(200 if upstream.status < 400 else upstream.status)
        for key, value in upstream.getheaders():
            if key.lower() in ('transfer-encoding', 'connection', 'content-length'):
                continue
            self.send_header(key, value)
        self.send_header('Connection', 'close')
        self.end_headers()

        forwarded = 0
        try:
            while True:
                chunk = upstream.read(512)
                if not chunk:
                    break
                if should_cut and forwarded + len(chunk) >= self.cut_after_bytes:
                    remaining = max(0, self.cut_after_bytes - forwarded)
                    if remaining:
                        self.wfile.write(chunk[:remaining])
                        self.wfile.flush()
                    log(f"stream cut after {self.cut_after_bytes} bytes mode={self.cut_mode}")
                    self.close_connection = True
                    if self.cut_mode == 'reset':
                        try:
                            self.connection.setsockopt(
                                socket.SOL_SOCKET,
                                socket.SO_LINGER,
                                struct.pack('ii', 1, 0),
                            )
                        except OSError:
                            pass
                    try:
                        self.connection.close()
                    except Exception:
                        pass
                    return
                self.wfile.write(chunk)
                self.wfile.flush()
                forwarded += len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            log(f"client closed early {self.path}")
        finally:
            try:
                conn.close()
            except Exception:
                pass


def main() -> None:
    global CUTS_LEFT, LOG_PATH
    parser = argparse.ArgumentParser()
    parser.add_argument('--listen', default='0.0.0.0:18098')
    parser.add_argument('--target', default='http://192.168.2.102:18081')
    parser.add_argument('--path', default='/chat/stream')
    parser.add_argument('--cut-after-bytes', type=int, default=2048)
    parser.add_argument('--cuts', type=int, default=2)
    parser.add_argument('--cut-mode', choices=('close', 'reset'), default='close')
    parser.add_argument('--log', default='')
    args = parser.parse_args()

    CUTS_LEFT = args.cuts
    LOG_PATH = args.log or None
    host, port = args.listen.split(':')
    Handler.target = args.target
    Handler.cut_path = args.path
    Handler.cut_after_bytes = args.cut_after_bytes
    Handler.cut_mode = args.cut_mode
    server = ThreadingHTTPServer((host, int(port)), Handler)
    log(
        f"proxy listening {args.listen} -> {args.target} path={args.path} "
        f"cuts={args.cuts} bytes={args.cut_after_bytes} mode={args.cut_mode}"
    )
    server.serve_forever()


if __name__ == '__main__':
    main()
