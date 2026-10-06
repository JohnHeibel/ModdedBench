# SPDX-License-Identifier: MIT
import importlib
from pathlib import Path
import re
import sys
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'harness' / 'smoke'))
import tick_cost  # noqa: E402

SUITES = {"movement_course": ("Course", "walk"), "pathfix_replay": ("Replay", "walk"), "explore_course": ("Explore", "walk"), "step_course": ("Steps", "walk"),
          "builder_shell": ("Shells", "build"), "build_suite": ("Suite", "build")}


def cost(max_ms, mean_ms, ticks): return {"tickNsMax": int(max_ms * 1e6), "tickNsMean": int(mean_ms * 1e6), "ticks": ticks, "searches": 1}


class TickCostTests(unittest.TestCase):
    def gate(self, kind="build", warm_up=False):
        self.said = []
        return tick_cost.Gate(kind, warm_up, out=self.said.append)

    def test_the_receipts_on_record_pass(self):
        # the worst of each kind on 2026-10-05: the hall, the resumed build with its one 98.3 ms tick, a three-tick walk
        for kind, c in (("build", cost(42.9, 1.24, 1731)), ("build", cost(98.3, 1.04, 548)), ("build", cost(23.8, 1.43, 300)),
                        ("walk", cost(43.4, 16.4, 3)), ("walk", cost(20.6, 5.3, 11)), ("walk", cost(19.5, 2.6, 400))):
            row = {"passed": True, "receipt": {"state": "succeeded", "cost": c}}
            self.assertIsNone(self.gate(kind)("case", row)); self.assertTrue(row["passed"]); self.assertNotIn("over", row["tickCost"])

    def test_a_case_over_either_limit_fails_and_prints_the_figure(self):
        row = {"passed": True, "why": "state=succeeded", "receipt": {"cost": cost(312.4, 2.0, 500)}}
        why = self.gate()("hall", row)
        self.assertEqual(why, "game thread over budget: tickMsMax 312.4 ms > 120")
        self.assertEqual((row["passed"], row["why"]), (False, why + "; state=succeeded"))
        self.assertEqual(row["tickCost"], {"tickMsMax": 312.4, "tickMsMean": 2.0, "limits": tick_cost.LIMITS["build"], "over": {"tickMsMax": 312.4}})
        self.assertEqual(self.said, ["tick cost hall: worst 312.4 ms (limit 120), mean 2.0 ms (limit 3) OVER"])
        # the sweep this was built after: 35 ms every fifth tick, no one tick near the limit
        row = {"passed": True, "failures": [], "receipt": {"cost": cost(35.0, 7.8, 200)}}
        self.assertEqual(self.gate("walk")("found[0]", row), "game thread over budget: tickMsMean 7.8 ms > 5")
        self.assertEqual((row["passed"], row["failures"]), (False, ["game thread over budget: tickMsMean 7.8 ms > 5"]))

    def test_a_mean_over_a_few_ticks_is_not_judged(self):
        row = {"passed": True, "receipt": {"cost": cost(50, 30, tick_cost.MEAN_TICKS - 1)}}
        self.assertIsNone(self.gate("walk")("short", row)); self.assertIsNone(row["tickCost"]["tickMsMean"])
        self.assertIn("mean n/a ms", self.said[0])
        self.assertIsNotNone(self.gate("walk")("short", {"passed": True, "receipt": {"cost": cost(50, 30, tick_cost.MEAN_TICKS)}}))

    def test_every_job_in_the_row_is_judged_by_its_worst(self):
        row = {"passed": True, "costs": [cost(10, 1, 400), cost(200, 1, 400), None], "receipt": {"cost": cost(10, 4, 400)}, "walk": {"cost": cost(5, 9, 10)}}
        self.assertEqual(tick_cost.figures(row), {"tickMsMax": 200.0, "tickMsMean": 4.0})
        self.assertEqual(self.gate()("resumed", row), "game thread over budget: tickMsMax 200.0 ms > 120, tickMsMean 4.0 ms > 3")

    def test_a_row_with_no_cost_is_left_alone(self):
        for row in ({"passed": True}, {"passed": False, "why": "error RuntimeError: x"}, {"ok": True, "receipt": {"cost": {}}}):
            before = dict(row)
            self.assertIsNone(self.gate()("none", row)); self.assertEqual(row, before)
        self.assertEqual(self.said, [])

    def test_warm_up_prints_the_first_costed_case_and_judges_the_next(self):
        gate = self.gate(warm_up=True)
        self.assertIsNone(gate("crashed", {"passed": False}))   # no cost: the warm-up is still to come
        first, second = ({"passed": True, "receipt": {"cost": cost(400, 1, 500)}} for _ in range(2))
        self.assertIsNone(gate("first", first)); self.assertTrue(first["passed"])
        self.assertEqual(first["tickCost"]["warmUp"], True); self.assertEqual(first["tickCost"]["over"], {"tickMsMax": 400.0})
        self.assertTrue(self.said[0].endswith("OVER, warm-up: not judged"))
        self.assertIsNotNone(gate("second", second)); self.assertFalse(second["passed"]); self.assertNotIn("warmUp", second["tickCost"])

    def test_a_verdict_and_an_ok_key_are_failed_in_their_own_terms(self):
        row = {"verdict": "pass", "failures": [], "cost": cost(400, 1, 500)}
        self.gate("walk")("replay", row, "verdict"); self.assertEqual(row["verdict"], "fail")
        row = {"ok": True, "costs": [cost(400, 1, 500)]}
        self.gate("walk")("found", row, "ok"); self.assertIs(row["ok"], False)

    def test_every_suite_runs_the_one_check_with_limits_of_its_kind(self):
        for module, (name, kind) in SUITES.items():
            suite = getattr(importlib.import_module(module), name)
            self.assertEqual(suite.COST, kind, module)
            self.assertIs(suite.costed, importlib.import_module("movement_course").Course.costed, module)
            source = (ROOT / "harness" / "smoke" / f"{module}.py").read_text(encoding="utf-8")
            self.assertEqual(len(re.findall(r"\.costed\(", source)), 1, module)
            self.assertEqual(len(re.findall(r"tick_cost\.argument\(ap\)", source)), 1, module)

    def test_a_suite_reads_warm_up_from_its_arguments(self):
        import argparse
        import builder_shell as bs
        ap = argparse.ArgumentParser(); tick_cost.argument(ap)
        self.assertFalse(ap.parse_args([]).warm_up)
        suite = object.__new__(bs.Shells); suite.args = ap.parse_args(["--warm-up"])
        rows = [{"passed": True, "receipt": {"cost": cost(400, 1, 500)}} for _ in range(2)]
        suite.gate = tick_cost.Gate(suite.COST, suite.args.warm_up, out=lambda line: None)
        for i, row in enumerate(rows): suite.costed(f"case{i}", row)
        self.assertEqual([r["passed"] for r in rows], [True, False])
        plain = object.__new__(bs.Shells); plain.args = types.SimpleNamespace()   # a caller with no such argument: no warm-up
        plain.costed("x", {"passed": True}); self.assertEqual((plain.gate.kind, plain.gate.warm), ("build", False))

    def test_the_mean_waits_for_the_ticks_the_job_result_waits_for(self):
        java = (ROOT / "mods" / "baritone" / "src" / "main" / "java" / "baritone" / "gtnh" / "pathing" / "Cost.java").read_text(encoding="utf-8")
        self.assertIn(f"MEAN_TICKS={tick_cost.MEAN_TICKS};", java)


if __name__ == "__main__":
    unittest.main()
