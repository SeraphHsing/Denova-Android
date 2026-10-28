#!/usr/bin/env python3
"""真实服务器的只读/写回探针（真机验收时核对服务端事实）。

凭证只从环境变量读取，不写入任何文件：

    DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \
        python tools/denova_http.py GET /api/projects/<id>/book/lore/categories

用法：
    python tools/denova_http.py GET  <path>
    python tools/denova_http.py POST <path> [--body <json|@file>]
    python tools/denova_http.py PUT  <path> [--body <json|@file>]

说明：
- `--path-only` 只打印响应里的指定键（点号分隔，如 `item.content`），便于核对大对象。
- `--status` 只打印 HTTP 状态码。
- 登录会建立一次性会话（内存 Cookie），不做持久化。
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import requests


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(2)


def pick(value, dotted: str):
    current = value
    for part in dotted.split("."):
        if isinstance(current, list):
            current = current[int(part)]
        elif isinstance(current, dict):
            current = current.get(part)
        else:
            return None
        if current is None:
            return None
    return current


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("method", choices=["GET", "POST", "PUT", "PATCH", "DELETE"])
    parser.add_argument("path")
    parser.add_argument("--body", default="")
    parser.add_argument("--path-only", dest="only", default="")
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--timeout", type=float, default=300.0)
    args = parser.parse_args()

    base = os.environ.get("DENOVA_BASE", "").rstrip("/")
    user = os.environ.get("DENOVA_USER", "")
    password = os.environ.get("DENOVA_PASS", "")
    if not base or not user or not password:
        fail("DENOVA_BASE / DENOVA_USER / DENOVA_PASS 必须由环境变量提供")

    session = requests.Session()
    login = session.post(
        f"{base}/api/auth/login",
        json={"username": user, "password": password},
        timeout=30,
    )
    if login.status_code >= 400:
        fail(f"login failed status={login.status_code} body={login.text[:200]}")

    payload = args.body
    if payload.startswith("@"):
        with open(payload[1:], "r", encoding="utf-8") as handle:
            payload = handle.read()
    headers = {"Content-Type": "application/json"} if payload else {}

    response = session.request(
        args.method,
        f"{base}{args.path}",
        data=payload.encode("utf-8") if payload else None,
        headers=headers,
        timeout=args.timeout,
    )
    if args.status:
        print(response.status_code)
        return
    text = response.text
    if args.only:
        try:
            print(json.dumps(pick(response.json(), args.only), ensure_ascii=False))
        except Exception:
            print(text)
        return
    try:
        print(json.dumps(response.json(), ensure_ascii=False, indent=2))
    except Exception:
        print(text)


if __name__ == "__main__":
    main()
