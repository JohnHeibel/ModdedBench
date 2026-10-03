# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Click-step plans in the tools: step builds forward as given, patterns save/load, and turning keeps clicks consistent."""
from __future__ import annotations

import itertools
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

MCP = Path(__file__).resolve().parents[1] / "mcp"
sys.path[:0] = [str(MCP)]
import mbtool
import server
from test_gtnh_profile import FakeKernel, module_with

DIR = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)]
TURNS = [(r, m) for r in (0, 90, 180, 270) for m in (None, "x", "z")]


def look(yaw):
    return (-math.sin(math.radians(yaw)), math.cos(math.radians(yaw)))


class PatternTests(unittest.TestCase):
    def setUp(self):
        mbtool.state.pop("notes", None)
        self.srv = server.Server()
        self.addCleanup(self.srv.close)
        self.assertFalse(any(m.startswith("ERROR") for m in self.srv.check_reload(force=True)), self.srv.error)
        self.patterns = module_with(self.srv, "mb_pattern")
        self.work = module_with(self.srv, "mb_build")
        folder = tempfile.TemporaryDirectory(); self.addCleanup(folder.cleanup)
        ctx = patch.object(self.patterns, "PATTERNS", Path(folder.name) / "patterns"); ctx.start(); self.addCleanup(ctx.stop)

    def use(self, fake):
        ctx = patch.dict(mbtool.state, {"kernel": fake}); ctx.start(); self.addCleanup(ctx.stop)
        return fake

    def test_quarter_turn_moves_face_hit_and_look_clockwise(self):
        step = {"kind": "place", "pos": [0, 0, 0], "id": "a:b",
                "click": {"face": 2, "hit": [0.25, 0.5, 0.0], "look": {"toward": "north", "yaw": [170, 190]}}}
        out = self.patterns._transform([step], 90, None)[0]
        self.assertEqual(out["pos"], [-1, 0, 0])
        self.assertEqual(out["click"]["face"], 5)
        self.assertEqual(out["click"]["hit"], [1.0, 0.5, 0.25])
        self.assertEqual(out["click"]["look"]["toward"], "east")
        self.assertEqual(out["click"]["look"]["yaw"], [-100, -80])
        self.assertEqual(step["click"]["face"], 2)  # the saved steps are not changed in place

    def test_turned_place_clicks_stay_on_the_turned_support_face(self):
        for (rotate, mirror), face in itertools.product(TURNS, range(6)):
            target = [3, 5, 7]
            support = [target[i] - DIR[face][i] for i in range(3)]
            hit = [0.3, 0.6, 0.8]; axis = max(range(3), key=lambda i: abs(DIR[face][i]))
            hit[axis] = 1.0 if DIR[face][axis] > 0 else 0.0
            step = {"pos": target, "click": {"face": face, "hit": hit}}
            marker = {"pos": support}
            new, moved = self.patterns._transform([step, marker], rotate, mirror)
            f = new["click"]["face"]
            self.assertEqual([new["pos"][i] - DIR[f][i] for i in range(3)], moved["pos"], (rotate, mirror, face))
            a = max(range(3), key=lambda i: abs(DIR[f][i]))
            self.assertEqual(new["click"]["hit"][a], 1.0 if DIR[f][a] > 0 else 0.0, (rotate, mirror, face))

    def test_turned_yaw_points_the_way_positions_turn(self):
        for (rotate, mirror), yaw in itertools.product(TURNS, (0, 30, 135, 250)):
            dx, dz = look(yaw)
            ahead = [round(dx * 100), 0, round(dz * 100)]
            step = {"pos": [0, 0, 0], "click": {"look": {"yaw": yaw}}}
            new, far = self.patterns._transform([step, {"pos": ahead}], rotate, mirror)
            want = [far["pos"][0] - new["pos"][0], far["pos"][2] - new["pos"][2]]
            got = look(new["click"]["look"]["yaw"])
            self.assertAlmostEqual(got[0], want[0] / 100, delta=0.02, msg=(rotate, mirror, yaw))
            self.assertAlmostEqual(got[1], want[1] / 100, delta=0.02, msg=(rotate, mirror, yaw))

    def test_four_turns_and_double_mirrors_are_the_identity(self):
        steps = [{"name": "a", "kind": "use", "pos": [2, 1, 4], "item": {"empty": True},
                  "click": {"face": "west", "hit": [0.0, 0.2, 0.7], "look": {"yaw": [10, 50], "pitch": 30}},
                  "expect": [{"method": "obs.block", "pos": [2, 1, 4], "path": "facing", "equals": ["north", 4], "faces": True}]},
                 {"kind": "place", "pos": [0, 0, 0], "id": "a:b", "click": {"face": "+x"}}]
        pattern = {"steps": steps}
        same = self.patterns.placed(pattern, [0, 0, 0])
        for rotate, mirror in ((0, None), (0, "x"), (0, "z")):
            once = self.patterns.placed(pattern, [0, 0, 0], 90 if mirror is None else 0, mirror)
            back = self.patterns.placed({"steps": once["steps"]}, [0, 0, 0], 270 if mirror is None else 0, mirror)
            self.assertEqual(back["steps"], same["steps"])

    def test_expectations_marked_faces_turn_and_positions_move(self):
        steps = [{"kind": "use", "pos": [1, 0, 0], "item": {"id": "x:wrench"},
                  "expect": [{"method": "obs.block", "pos": [1, 0, 0], "path": "sides", "contains": "north", "faces": True},
                             {"method": "obs.block", "pos": [1, 0, 0], "path": "meta", "equals": 2}]}]
        out = self.patterns._transform(steps, 90, None)[0]
        self.assertEqual(out["expect"][0]["contains"], "east")
        self.assertEqual(out["expect"][0]["pos"], out["pos"])
        self.assertEqual(out["expect"][1]["equals"], 2)  # not marked faces: a number stays a number

    def test_turning_drops_meta_only_where_the_click_fixes_the_facing(self):
        steps = [{"name": "machine", "kind": "place", "pos": [0, 0, 0], "id": "a:m", "meta": 3, "click": {"face": 1, "look": {"toward": "north"}}},
                 {"name": "plank", "kind": "place", "pos": [1, 0, 0], "id": "a:p", "meta": 2}]
        self.assertNotIn("metaDropped", self.patterns.placed({"steps": steps}, [5, 5, 5]))
        turned = self.patterns.placed({"steps": steps}, [5, 5, 5], 90)
        self.assertEqual(turned["metaDropped"], [{"step": "machine", "meta": 3}])
        self.assertEqual({s["name"]: s.get("meta") for s in turned["steps"]}, {"machine": None, "plank": 2})
        self.assertEqual(turned["origin"], [5, 5, 5])
        self.assertEqual(min(s["pos"][0] for s in turned["steps"]), 0)

    def test_save_list_load_and_build_by_name(self):
        steps = [{"kind": "place", "pos": [0, 0, 0], "id": "a:b", "click": {"face": "north"}},
                 {"kind": "place", "pos": [1, 1, 0], "id": "a:c"}]
        saved = self.patterns.mb_pattern("save", "pair", steps=steps, origin=[100, 64, -20], note="test")
        self.assertEqual((saved["steps"], saved["size"], saved["proven"]), (2, [2, 2, 1], None))
        with self.assertRaises(ValueError): self.patterns.mb_pattern("save", "pair", steps=steps)
        self.assertEqual([r["name"] for r in self.patterns.mb_pattern("list")["patterns"]], ["pair"])
        loaded = self.patterns.mb_pattern("load", "pair", at=[0, 70, 0], rotate=180)
        self.assertEqual(loaded["source"]["corner"], [100, 64, -20])
        self.assertEqual(loaded["placed"]["steps"][0]["click"]["face"], "south")
        fake = self.use(FakeKernel(lambda method, params: {"state": "succeeded"}))
        self.work.mb_build(pattern={"name": "pair", "at": [0, 70, 0], "rotate": 180})
        sent = fake.last("nav.build")[1]
        self.assertEqual(sent["origin"], [0, 70, 0])
        self.assertEqual(sent["steps"], loaded["placed"]["steps"])
        self.assertNotIn("mode", sent); self.assertNotIn("settings", sent); self.assertNotIn("replaceExisting", sent)
        for bad in ({"name": "pair"}, {"name": "pair", "at": [0, 0, 0], "turn": 90}, {"name": "nope", "at": [0, 0, 0]}):
            with self.assertRaises(ValueError): self.work.mb_build(pattern=bad)
        with self.assertRaises(ValueError): self.patterns.mb_pattern("load", "pair", at=[0, 0, 0], rotate=45)

    def test_save_from_a_job_marks_whether_it_succeeded(self):
        steps = [{"kind": "use", "pos": [0, 0, 0], "item": {"empty": True}}]
        self.use(FakeKernel(lambda method, params: {"specSummary": {"steps": steps, "origin": [1, 2, 3]}, "receipt": {"state": "failed"}}))
        saved = self.patterns.mb_pattern("save", "lever", job_id="j")
        self.assertFalse(saved["proven"])
        self.assertEqual(self.patterns.mb_pattern("load", "lever")["source"]["jobId"], "j")
        self.use(FakeKernel(lambda method, params: {"specSummary": {"cellCount": 4}}))
        with self.assertRaises(ValueError): self.patterns.mb_pattern("save", "cells", job_id="k")

    def test_step_builds_forward_steps_and_access_without_cell_settings(self):
        fake = self.use(FakeKernel(lambda method, params: {"state": "succeeded"}))
        steps = [{"kind": "place", "pos": [0, 0, 0], "id": "a:b"}]
        access = {"allow": True, "maxCells": 2}
        self.work.mb_build(steps=steps, origin=[1, 2, 3], access=access, mode="builder")
        self.work.mb_build_preview(steps=steps, origin=[1, 2, 3], access=access)
        for method in ("nav.build", "nav.build_preview"):
            sent = fake.last(method)[1]
            self.assertEqual((sent["steps"], sent["origin"], sent["access"]), (steps, [1, 2, 3], access))
            self.assertNotIn("settings", sent); self.assertNotIn("mode", sent)
        with self.assertRaises(ValueError): self.work.mb_build(steps=steps, cells=steps)
        with self.assertRaises(ValueError): self.work.mb_build(steps=steps, pattern={"name": "x", "at": [0, 0, 0]})


if __name__ == "__main__":
    unittest.main()
