#!/usr/bin/env python3
"""Web 移动视口基线抓图与尺寸读数（P5 双端对照取证用）。

只用于取证，不进 APK。产物是可回查的截图 + computed 尺寸 JSON，供与真机截图逐项比对。

凭证只从环境变量读取，不写入任何文件、不回显：

    DENOVA_BASE=http://192.168.2.102:18081 DENOVA_USER=... DENOVA_PASS=... \
        python tools/web_parity_capture.py

用法：
    python tools/web_parity_capture.py                     # 默认全部场景 + 390/320 两档视口
    python tools/web_parity_capture.py --scene login       # 只跑登录页（匿名，不需要凭证）
    python tools/web_parity_capture.py --widths 390        # 只跑一档视口
    python tools/web_parity_capture.py --out <目录>        # 自定义产物目录

安全约束（AGENTS §5）：
- 不打印、不落盘用户名/密码/Cookie；登录成功只输出 ok 与当前 URL 路径。
- 不构造错误密码登录（避免制造无效 401 噪音），登录表单校验只做只读断言。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

from playwright.sync_api import Browser, Page, sync_playwright

# 基线（SeraphHsing/denova@85ec97c）手机断点为 max-width:1023px，验收宽度 320/390/430/768/1023 + 844 横屏。
DEFAULT_WIDTHS = (390, 320)
VIEWPORT_HEIGHT = {320: 640, 390: 844, 430: 932, 768: 1024, 1023: 768}
LANG_NAV = {"zh": "导航菜单", "en": "Navigation", None: "导航菜单"}

# 只读断言与读数都在页面内执行，避免每项一个 round-trip。
METRICS_JS = """
() => {
  const round = (v) => Math.round(v * 100) / 100;
  const rect = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: round(r.x), y: round(r.y), w: round(r.width), h: round(r.height) };
  };
  const style = (el, props) => {
    if (!el) return null;
    const c = getComputedStyle(el);
    const out = {};
    for (const p of props) out[p] = c.getPropertyValue(p);
    return out;
  };
  const TYPO = ['font-size', 'font-weight', 'line-height', 'letter-spacing', 'color'];
  const BOX = ['background-color', 'border-radius', 'border-top-width', 'padding-left', 'padding-right', 'box-shadow'];
  const sheet = document.querySelector('.nova-mobile-sheet[data-slot="sheet-content"]');
  const row = document.querySelector('.nova-mobile-nav-row');
  const creationRow = document.querySelector('[data-mobile-nav-group="creation"] .nova-mobile-nav-row');
  const header = document.querySelector('.nova-mobile-sheet [data-mobile-drawer-header]');
  const sectionLabel = document.querySelector('.nova-mobile-section-label');
  const navIcon = document.querySelector('.nova-mobile-nav-icon');
  const rowIcon = row ? row.querySelector(':scope > svg') : null;
  const topBar = document.querySelector('[data-nova-mobile-shell="true"] > :first-child');
  const card = document.querySelector('main[data-nova-app-shell="true"] [class*="max-w-sm"]') || document.querySelector('main[data-nova-app-shell="true"] > *');
  const loginInput = document.querySelector('#remote-username');
  const loginSubmit = document.querySelector('button[form="remote-access-login"]');
  const rows = [...document.querySelectorAll('.nova-mobile-nav-row')].map((el) => ({
    label: (el.getAttribute('aria-label') || el.textContent || '').trim(),
    current: el.getAttribute('aria-current') === 'page',
    ...rect(el),
  }));
  return {
    url: location.pathname + location.search,
    viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
    root: {
      bg: style(document.documentElement, ['--nova-bg', '--nova-surface', '--nova-text', '--nova-text-muted', '--nova-border-soft', '--nova-primary', '--nova-mobile-radius', '--nova-mobile-title-size', '--nova-mobile-label-size']),
      fontSize: style(document.body, ['font-size', 'font-family']),
    },
    login: {
      card: card ? { ...rect(card), ...style(card, BOX) } : null,
      title: style(card ? card.querySelector('h1') : null, TYPO),
      description: style(card ? card.querySelector('p') : null, TYPO),
      input: loginInput ? { ...rect(loginInput), ...style(loginInput, [...TYPO.filter((p) => p !== 'color'), ...BOX]) } : null,
      submit: loginSubmit ? { ...rect(loginSubmit), ...style(loginSubmit, TYPO), disabled: loginSubmit.disabled } : null,
      fields: [...document.querySelectorAll('main[data-nova-app-shell="true"] label, main[data-nova-app-shell="true"] [data-slot="field-description"]')]
        .map((el) => ({ text: (el.textContent || '').trim(), ...style(el, TYPO) })),
    },
    topBar: topBar ? { ...rect(topBar), ...style(topBar, [...BOX, ...TYPO]) } : null,
    sheet: sheet ? { ...rect(sheet), ...style(sheet, [...BOX, ...TYPO]) } : null,
    sheetHeader: header ? { ...rect(header), ...style(header, [...BOX, ...TYPO]) } : null,
    sheetTitle: header ? style(header.querySelector('[data-slot="sheet-title"]'), TYPO) : null,
    sectionLabel: sectionLabel ? { ...rect(sectionLabel), ...style(sectionLabel, TYPO) } : null,
    navIcon: navIcon ? { ...rect(navIcon), ...style(navIcon, [...TYPO]) } : null,
    rowIcon: rowIcon ? rect(rowIcon) : null,
    navRow: row ? { ...rect(row), ...style(row, [...BOX, ...TYPO]) } : null,
    creationRow: creationRow ? { ...rect(creationRow), ...style(creationRow, BOX) } : null,
    navRows: rows,
    bodyText: (document.body.innerText || '').slice(0, 4000),
  };
}
"""


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(2)


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def collect_credentials(require_login: bool) -> tuple[str, str, str]:
    base = os.environ.get("DENOVA_BASE", "").rstrip("/")
    user = os.environ.get("DENOVA_USER", "")
    password = os.environ.get("DENOVA_PASS", "")
    if not base:
        fail("DENOVA_BASE 必须由环境变量提供")
    if require_login and (not user or not password):
        fail("workspace 场景需要 DENOVA_USER / DENOVA_PASS 环境变量")
    return base, user, password


def dump(page: Page, out_dir: Path, name: str, records: dict) -> None:
    png = out_dir / f"{name}.png"
    page.screenshot(path=str(png), full_page=False)
    records[name] = page.evaluate(METRICS_JS)
    print(f"  captured {name} -> {png.name}")


def open_drawer(page: Page, lang: str | None) -> bool:
    label = LANG_NAV.get(lang, LANG_NAV[None])
    button = page.locator(f'button[aria-label="{label}"], button[aria-label="Navigation"]').first
    if button.count() == 0:
        print("  warn: navigation button not found")
        return False
    button.click()
    page.wait_for_timeout(600)
    return page.locator('.nova-mobile-sheet[data-slot="sheet-content"]').count() > 0


def run_scene(browser: Browser, base: str, width: int, out_dir: Path, records: dict,
              scenes: set[str], lang: str | None, scheme: str = "") -> None:
    height = VIEWPORT_HEIGHT.get(width, 844)
    context = browser.new_context(
        viewport={"width": width, "height": height},
        device_scale_factor=3,
        is_mobile=True,
        has_touch=True,
        locale="zh-CN" if lang in (None, "zh") else "en-US",
    )
    page = context.new_page()
    page.goto(base, wait_until="domcontentloaded", timeout=30000)
    # 基线主题靠 <html data-theme="light">；默认（无属性）= 深色 preset canvas。
    if scheme == "light":
        page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
    sfx = f"-{scheme}" if scheme else ""

    def tag(name: str) -> str:
        return f"{name}{sfx}"

    if "login" in scenes:
        # 匿名态：登录页（Web 无服务器地址输入，地址即浏览器 origin）
        page.wait_for_selector("#remote-username", timeout=20000)
        page.wait_for_timeout(400)
        dump(page, out_dir, tag(f"login-{width}"), records)

        # 只读校验：空表单提交按钮必须 disabled（对齐基线 disabled={submitting || !username || !password}）
        records[tag(f"login-{width}-submit-disabled-empty")] = page.eval_on_selector(
            'button[form="remote-access-login"]', "el => el.disabled"
        )

    if "workspace" in scenes:
        # 首屏可能是加载态：先等「登录表单」或「已认证外壳」出现其一，再判断需不需要登录。
        try:
            page.wait_for_selector('#remote-username, [data-nova-mobile-shell="true"]', timeout=25000)
        except Exception:  # noqa: BLE001 - 超时后按截图诊断
            print("  warn: neither login form nor app shell appeared")
        if page.locator("#remote-username").count() > 0:
            user = os.environ.get("DENOVA_USER", "")
            password = os.environ.get("DENOVA_PASS", "")
            page.fill("#remote-username", user)
            page.fill("#remote-password", password)
            page.click('button[form="remote-access-login"]')
            page.wait_for_selector("#remote-username", state="detached", timeout=40000)
            print(f"  login ok at {width}px")
        page.wait_for_timeout(1500)
        dump(page, out_dir, tag(f"workspace-{width}"), records)

        if open_drawer(page, lang):
            dump(page, out_dir, tag(f"drawer-{width}"), records)
            # 抽屉下半部分（工具组 + 设置组）与滚动可达性
            page.evaluate("""() => {
              const el = document.querySelector('.nova-mobile-navigation');
              if (el) el.scrollTop = el.scrollHeight;
            }""")
            page.wait_for_timeout(400)
            dump(page, out_dir, tag(f"drawer-bottom-{width}"), records)
            page.evaluate("""() => {
              const el = document.querySelector('.nova-mobile-navigation');
              if (el) el.scrollTop = 0;
            }""")

            # NAV-006 对照：Web 手机抽屉的「命令」按钮打开命令面板（不是内嵌搜索框）
            command_button = page.locator('button[aria-label="命令"], button[aria-label="Commands"]').first
            if command_button.count() > 0:
                command_button.click()
                page.wait_for_timeout(700)
                dump(page, out_dir, tag(f"command-panel-{width}"), records)
                page.keyboard.press("Escape")
                page.wait_for_timeout(400)
                if page.locator('.nova-mobile-sheet').count() > 0 and open_drawer(page, lang) is False:
                    page.wait_for_timeout(200)

            # 关闭：点标题栏关闭按钮（基线 data-mobile-drawer-header 内第一个 button）
            close = page.locator('.nova-mobile-sheet [data-mobile-drawer-header] button').first
            if close.count() > 0:
                close.click()
                page.wait_for_timeout(500)

    context.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Web 移动视口基线抓图与尺寸读数")
    parser.add_argument("--out", default="docs/evidence/web-baseline/" + "2026-10-30")
    parser.add_argument("--widths", default=",".join(str(w) for w in DEFAULT_WIDTHS))
    parser.add_argument("--scene", default="all", choices=["all", "login", "workspace"])
    parser.add_argument("--lang", default="", choices=["", "zh", "en"], help="空表示不干预（默认中文环境）")
    parser.add_argument("--scheme", default="", choices=["", "light"], help="空表示基线默认深色；light 走 data-theme=light")
    parser.add_argument("--headed", action="store_true", help="调试用：显示浏览器窗口")
    parser.add_argument(
        "--channel",
        default="",
        help="浏览器通道（本机未下载 Playwright 专用 Chromium 时用 chrome / msedge）",
    )
    args = parser.parse_args()

    widths = [int(w) for w in args.widths.split(",") if w.strip()]
    scenes = {"login", "workspace"} if args.scene == "all" else {args.scene}
    base, _user, _password = collect_credentials(require_login="workspace" in scenes)
    lang = args.lang or None

    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    records: dict = {}
    # 多次分场景运行时合并读数，避免后一次运行覆盖前一次截图对应的读数。
    existing = out_dir / "metrics.json"
    if existing.exists():
        try:
            records = json.loads(existing.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            print("  warn: existing metrics.json is not valid JSON, starting fresh")

    with sync_playwright() as playwright:
        launch_options: dict = {"headless": not args.headed}
        if args.channel:
            launch_options["channel"] = args.channel
        browser = playwright.chromium.launch(**launch_options)
        try:
            for width in widths:
                print(f"[viewport {width}x{VIEWPORT_HEIGHT.get(width, 844)}]")
                run_scene(browser, base, width, out_dir, records, scenes, lang, args.scheme)
        finally:
            browser.close()

    metrics = out_dir / "metrics.json"
    metrics.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"metrics -> {metrics}")
    print("note: 截图与读数不含凭证；Cookie 只在本次内存 context 内使用。")


if __name__ == "__main__":
    main()
