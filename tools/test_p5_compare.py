#!/usr/bin/env python3
"""`tools/p5_compare.py` 的回归测试（标准库 unittest，无第三方依赖）。

运行：
    python -m unittest discover -s tools -p "test_*.py"
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import p5_compare as p5  # noqa: E402


class ParseBoundsTest(unittest.TestCase):
    def test_parses_standard_bounds(self):
        self.assertEqual(p5.parse_bounds("[0,0][1080,2340]"), (0, 0, 1080, 2340))

    def test_parses_negative_origin(self):
        self.assertEqual(p5.parse_bounds("[-4,-8][100,200]"), (-4, -8, 100, 200))

    def test_rejects_garbage(self):
        self.assertIsNone(p5.parse_bounds(""))
        self.assertIsNone(p5.parse_bounds("bounds"))


class PxToDpTest(unittest.TestCase):
    def test_590_dpi(self):
        # 590dpi 设备上 1dp ≈ 3.6875px；1080px 宽 ≈ 292.88dp。
        self.assertAlmostEqual(p5.px_to_dp(590, 590), 160.0, places=4)
        self.assertAlmostEqual(p5.px_to_dp(1080, 590), 1080 * 160 / 590, places=4)

    def test_rejects_non_positive_density(self):
        with self.assertRaises(ValueError):
            p5.px_to_dp(100, 0)


class PickNodeTest(unittest.TestCase):
    def setUp(self):
        self.nodes = [
            {"text": "山雨落在旧城墙上", "desc": "", "rid": "com.denova:id/body", "w": 1080, "h": 220},
            {"text": "", "desc": "导航菜单", "rid": "com.denova:id/menu", "w": 160, "h": 160},
        ]

    def test_exact_text_first(self):
        node = p5.pick_node(self.nodes, {"text": "山雨落在旧城墙上"})
        self.assertIsNotNone(node)
        self.assertEqual(node["h"], 220)

    def test_desc_match(self):
        node = p5.pick_node(self.nodes, {"desc": "导航菜单"})
        self.assertEqual(node["rid"], "com.denova:id/menu")

    def test_container_match(self):
        node = p5.pick_node(self.nodes, {"text": "旧城墙"})
        self.assertIsNotNone(node)

    def test_missing_returns_none(self):
        self.assertIsNone(p5.pick_node(self.nodes, {"text": "不存在"}))


class WebValueTest(unittest.TestCase):
    def test_numeric_and_px_string(self):
        scene = {"editor": {"paragraph": {"h": 60.78, "font-size": "16px"}}}
        self.assertAlmostEqual(p5.web_value(scene, "editor.paragraph.h"), 60.78, places=4)
        self.assertAlmostEqual(p5.web_value(scene, "editor.paragraph.font-size"), 16.0, places=4)

    def test_missing_path(self):
        self.assertIsNone(p5.web_value({"a": {}}, "a.b.c"))


class CompareItemTest(unittest.TestCase):
    def test_within_tolerance(self):
        scene = {"topBar": {"h": 48}}
        nodes = [{"text": "", "desc": "导航菜单", "rid": "", "w": 1439, "h": 177}]
        row = p5.compare_item(scene, nodes, {"label": "顶栏", "web": "topBar.h", "dim": "h",
                                             "match": {"desc": "导航菜单"}}, density=590)
        self.assertTrue(row["ok"])
        self.assertLessEqual(row["diff"], 1.0)

    def test_over_tolerance(self):
        scene = {"topBar": {"h": 48}}
        nodes = [{"text": "", "desc": "导航菜单", "rid": "", "w": 100, "h": 260}]
        row = p5.compare_item(scene, nodes, {"label": "顶栏", "web": "topBar.h", "dim": "h",
                                             "match": {"desc": "导航菜单"}}, density=590)
        self.assertTrue(row["ok"])
        self.assertGreater(row["diff"], 1.0)

    def test_missing_web_value_marks_failure(self):
        row = p5.compare_item({}, [], {"label": "缺失", "web": "nope.h"}, density=590)
        self.assertFalse(row["ok"])
        self.assertIn("Web", row["reason"])

    def test_missing_node_marks_failure(self):
        row = p5.compare_item({"a": {"h": 48}}, [], {"label": "缺失", "web": "a.h"}, density=590)
        self.assertFalse(row["ok"])
        self.assertIn("真机", row["reason"])

    def test_object_path_falls_back_to_dim(self):
        # spec 只写到对象（editor.paragraph）时按 dim 自动取 h，避免必须写全路径。
        scene = {"editor": {"paragraph": {"h": 60.78}}}
        nodes = [{"text": "段落", "desc": "", "rid": "", "w": 1080, "h": 224}]
        row = p5.compare_item(scene, nodes, {"label": "段落", "web": "editor.paragraph",
                                             "dim": "h", "match": {"text": "段落"}}, density=590)
        self.assertTrue(row["ok"])
        self.assertIsNotNone(row["web"])
        self.assertAlmostEqual(row["web"], 60.78, places=4)


class RenderMarkdownTest(unittest.TestCase):
    def test_marks_over_tolerance(self):
        rows = [
            {"label": "一致项", "web": 48.0, "android": 48.5, "diff": 0.5, "ok": True, "reason": ""},
            {"label": "超差项", "web": 48.0, "android": 52.0, "diff": 4.0, "ok": True, "reason": ""},
        ]
        text = p5.render_markdown(rows, 1.0, "editor-390")
        self.assertIn("一致项", text)
        self.assertIn("**超差**", text)
        self.assertIn("1/2 项在容差内", text)


if __name__ == "__main__":
    unittest.main()
