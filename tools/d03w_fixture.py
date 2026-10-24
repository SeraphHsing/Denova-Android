#!/usr/bin/env python3
"""D03-W 写作性能 fixture 生成与上传（G2 判据用）。

口径来源：docs/android-web-parity/06-development-and-validation.md#d03-性能基准与冻结记录
    - 中英文各 1 本、每本 10 章，标准章 ~50KB UTF-8
      （每章含 1 个 GFM 表格 + 1 个图片引用 + 1 个代码块）
    - 压力文档 1 个 ~500KB
    - fixture 标识：d03-writing-{zh,en}-ch01..ch10、d03-large-500k

内容由固定词表按确定性伪随机序列拼接，同一参数重复执行得到逐字节相同的文件，
因此 SHA-256 可回填 06 §4 作为可复现性校验值。

用法：
    # 本地生成（默认输出到系统临时目录，不写入仓库）+ 打印大小与 SHA-256
    python tools/d03w_fixture.py gen

    # 生成并上传到真实服务器（凭证只从环境变量读取，不落盘）
    DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \\
        python tools/d03w_fixture.py upload <project_id> --dir d03-writing

上传使用 `PUT /api/projects/:id/files/file`，请求体 {path, content, base_revision}；
`base_revision` 留空表示按新建处理，已存在同名文件时服务端按 revision 冲突返回 409。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import tempfile
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

CHAPTER_TARGET = 50 * 1024
LARGE_TARGET = 500 * 1024

ZH_WORDS = (
    "灵渊 山川 剑气 宗门 长夜 雨声 旧梦 孤城 灯火 渡口 归人 江湖 断崖 迷雾 星火 "
    "寒潭 铁马 书信 舟楫 碑文 松风 荒原 更漏 春汛 沧浪 石阶 尘埃 弦月 檐角"
).split()
EN_WORDS = (
    "lantern harbor ledger manuscript current ember corridor threshold quiet "
    "harborwatch parchment currentless riverbed foldglass seldom threshold"
).split()

ZH_SENTENCE = "他记得{0}落在{1}的{2}上，像一句没有说完的{3}。"
EN_SENTENCE = "The {0} crossed the {1} before the {2} could answer, and the {3} stayed quiet."


def _zh_paragraph(rnd: random.Random, sentences: int) -> str:
    out = []
    for _ in range(sentences):
        out.append(ZH_SENTENCE.format(rnd.choice(ZH_WORDS), rnd.choice(ZH_WORDS),
                                      rnd.choice(ZH_WORDS), rnd.choice(ZH_WORDS)))
    return "".join(out)


def _en_paragraph(rnd: random.Random, sentences: int) -> str:
    out = []
    for _ in range(sentences):
        out.append(EN_SENTENCE.format(rnd.choice(EN_WORDS), rnd.choice(EN_WORDS),
                                      rnd.choice(EN_WORDS), rnd.choice(EN_WORDS)))
    return " ".join(out)


def _chapter(lang: str, index: int, target: int) -> str:
    rnd = random.Random(f"d03-writing-{lang}-ch{index:02d}")
    para = _zh_paragraph if lang == "zh" else _en_paragraph
    title = f"第 {index} 章 渡口与灯" if lang == "zh" else f"Chapter {index}: Harbor and Lantern"
    parts = [f"# {title}\n\n"]
    table = ("\n| 场景 | 人物 | 线索 |\n|---|---|---|\n| 渡口 | 沈砚 | 断剑 |\n| 长街 | 阿箬 | 残信 |\n\n")
    image = "![插图](../assets/d03-cover.png)\n\n"
    code = "\n```kotlin\nfun chapter(n: Int): String = \"ch\" + n.toString().padStart(2, '0')\n```\n\n"
    inserted = False
    while len("".join(parts).encode("utf-8")) < target:
        parts.append(para(rnd, 6))
        parts.append("\n\n")
        if not inserted and len("".join(parts).encode("utf-8")) > target // 2:
            parts.append(table)
            parts.append(image)
            parts.append(code)
            inserted = True
    if not inserted:
        parts.append(table)
        parts.append(image)
        parts.append(code)
    return "".join(parts)


def _large(lang: str, target: int) -> str:
    rnd = random.Random(f"d03-large-500k-{lang}")
    para = _zh_paragraph if lang == "zh" else _en_paragraph
    parts = ["# 压力文档 / Stress document\n\n"]
    while len("".join(parts).encode("utf-8")) < target:
        parts.append(para(rnd, 8))
        parts.append("\n\n")
    return "".join(parts)


def build(out_dir: str) -> list[dict]:
    os.makedirs(out_dir, exist_ok=True)
    records = []
    specs = []
    for lang in ("zh", "en"):
        for i in range(1, 11):
            specs.append((f"d03-writing-{lang}-ch{i:02d}", f"{lang}/ch{i:02d}.md",
                          _chapter(lang, i, CHAPTER_TARGET)))
    specs.append(("d03-large-500k", "zh/d03-large-500k.md", _large("zh", LARGE_TARGET)))
    for fid, rel, content in specs:
        path = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = content.encode("utf-8")
        with open(path, "wb") as f:
            f.write(data)
        records.append({
            "fixture": fid,
            "file": rel,
            "path": path.replace("\\", "/"),
            "bytes": len(data),
            "chars": len(content),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    return records


def _login(base: str, user: str, password: str) -> urllib.request.OpenerDirector:
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    body = json.dumps({"username": user, "password": password}).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=15) as resp:
        if resp.status != 200:
            raise SystemExit(f"login failed: {resp.status}")
    return opener


def upload(records: list[dict], base: str, project_id: str, prefix: str) -> None:
    user = os.environ.get("DENOVA_USER", "")
    password = os.environ.get("DENOVA_PASS", "")
    if not user or not password:
        raise SystemExit("DENOVA_USER / DENOVA_PASS 未设置（凭证不得写入仓库）")
    opener = _login(base, user, password)
    for rec in records:
        rel = rec["fixture"] + ".md"
        target = f"{prefix.rstrip('/')}/{rel}" if prefix else rel
        with open(rec["path"], "rb") as f:
            content = f.read().decode("utf-8")
        # 后端要求每次保存都带 base_revision：已存在用当前 revision，不存在时必须传 "missing"。
        rev = _current_revision(opener, base, project_id, target)
        body = json.dumps({"path": target, "content": content, "base_revision": rev}).encode("utf-8")
        req = urllib.request.Request(f"{base}/api/projects/{project_id}/files/file", data=body,
                                     method="PUT", headers={"Content-Type": "application/json"})
        try:
            with opener.open(req, timeout=60) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            print(f"uploaded {target} revision={payload.get('revision')} (base={rev})")
        except urllib.error.HTTPError as exc:
            print(f"FAILED {target} {exc.code} {exc.read().decode('utf-8', 'replace')[:200]}")
            raise SystemExit(1)


def _current_revision(opener, base: str, project_id: str, path: str) -> str:
    url = f"{base}/api/projects/{project_id}/files/file?path={path}&optional=true"
    with opener.open(url, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    document = payload.get("document") or {}
    return document.get("revision") or "missing"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["gen", "upload"])
    ap.add_argument("project_id", nargs="?")
    ap.add_argument("--out", default=os.path.join(tempfile.gettempdir(), "d03w-fixtures"))
    ap.add_argument("--dir", default="d03-writing", help="服务器上的目标目录前缀")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出记录，便于回填文档")
    args = ap.parse_args()

    records = build(args.out)
    if args.json:
        # 清单不记录本机绝对路径，避免把个人环境写进仓库
        print(json.dumps([{k: v for k, v in r.items() if k != "path"} for r in records],
                         ensure_ascii=False, indent=2))
    else:
        for rec in records:
            print(f"{rec['fixture']:<24} {rec['bytes']:>7}B {rec['chars']:>7}chars {rec['sha256']}")
    print(f"{len(records)} fixtures in {args.out}", file=sys.stderr)

    if args.mode == "upload":
        if not args.project_id:
            raise SystemExit("upload 需要 project_id")
        upload(records, os.environ.get("DENOVA_BASE", "http://192.168.2.102:18081").rstrip("/"),
               args.project_id, args.dir)


if __name__ == "__main__":
    main()
