#!/usr/bin/env python3
"""G1 一次性配对消费验证：消费同一个 token 两次，验证第二次被拒绝。

配对链接只能由服务器本机创建（`POST /api/auth/link` 受 localHostEffectMiddleware 限制），
因此本脚本只负责「消费」侧；token 需要先在服务器本机生成：

    curl -s -X POST http://127.0.0.1:18081/api/auth/link -H 'Content-Type: application/json' \
         --cookie '<登录后的 Cookie>' | jq -r .url   # 取 #pair= 后面的 token

用法（凭证走环境变量，不落盘）：
    DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \
        python tools/g1_pair_consume.py <pair_token>

预期：第一次消费 200（进入已认证态）；第二次消费 401 pairingInvalid。
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("DENOVA_BASE", "http://192.168.2.102:18081").rstrip("/")


def consume(token: str) -> int:
    body = json.dumps({"token": token}).encode()
    req = urllib.request.Request(f"{BASE}/api/auth/pair", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    token = sys.argv[1].strip()
    first = consume(token)
    second = consume(token)
    print(f"pair first={first} second={second}")
    ok = first == 200 and second in (400, 401, 403)
    print("PASS" if ok else "FAIL",
          "（期望第一次 200、第二次 4xx，验证 token 一次性消费）")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
