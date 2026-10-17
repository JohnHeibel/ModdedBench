# SPDX-License-Identifier: MIT
import inspect
from pathlib import Path
import sys
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'smoke'))
import build_edges as be  # noqa: E402


class Fake(be.Edges):
    """A scenario with no game: an empty plot, the player where it started, every call recorded and answered."""
    def __init__(self, receipt):
        self.receipt = receipt; self.args = types.SimpleNamespace(keep=False)
    def scene(self, spec): self.spec = spec; return [1000, 176, 2000]
    def found(self, box): return {}
    def feet(self, origin): return list(self.spec["start"])
    def tracked(self, build): return build(), {"blocks": 0.0}


class BuildEdgesTests(unittest.TestCase):
    def setUp(self):
        self.calls = []; real = be.call
        be.call = lambda fn, **kw: self.calls.append((fn.__name__, kw)) or dict(self.receipt)
        self.addCleanup(setattr, be, "call", real)
        self.receipt = {"state": "paused", "stopped": {"reason": "no_stance", "pos": [1003, 178, 2003]}, "ticks": 300}

    def test_every_plan_is_a_valid_request_in_the_tools_own_parameters(self):
        allowed = set(inspect.signature(be.work.mb_build).parameters)
        for name, make in (be.SCENARIOS | be.PACK).items():
            spec = make(); cells = spec["cells"]
            positions = [tuple(c["pos"]) for c in cells]
            self.assertEqual(len(positions), len(set(positions)), name)
            self.assertLessEqual(len(cells), 4096, name)
            for c in cells:
                self.assertEqual(len(c["pos"]), 3, name); self.assertTrue(c.get("clear") or ":" in c["id"], (name, c))
                self.assertGreaterEqual(c.get("stage", 0), 0, name)
            # Terrain is never in a plan cell unless it is that cell's block already (the oven's floor).
            plan = {tuple(c["pos"]): c.get("id") for c in cells}
            for p, block in spec["terrain"].items():
                self.assertIn(plan.get(tuple(p), block if isinstance(block, str) else block[0]), (block if isinstance(block, str) else block[0],), (name, p))
            lo, hi = spec["box"]
            for p in list(plan) + [tuple(p) for p in spec["terrain"]] + [tuple(spec["start"])]:
                self.assertTrue(all(lo[i] <= p[i] <= hi[i] for i in range(3)), (name, p))
            self.assertLessEqual((hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1), 16384, name)   # one region read
            # The body fits where it starts: neither cell is terrain.
            x, y, z = spec["start"]; self.assertFalse({(x, y, z), (x, y + 1, z)} & set(map(tuple, spec["terrain"])), name)
            self.assertLessEqual(set(spec["options"]), allowed, name)
            self.assertIn("end", spec["good"]); self.assertIn("end", spec["predict"])
            # Enough of each placed item is carried for every cell (by id, or the item a cell names).
            need = {}
            for c in cells:
                if not c.get("clear"): key = (c.get("item") or {}).get("id") or {be.WIRE: be.REDSTONE}.get(c["id"], c["id"]); need[key] = need.get(key, 0) + 1
            for item, n in need.items(): self.assertGreaterEqual(sum(k[1] for k in spec["kit"] if k[0] == item), n, (name, item))

    def test_each_scenario_is_one_build_call_and_its_row_is_judged(self):
        for name in be.SCENARIOS | be.PACK:
            self.calls.clear(); row = Fake(self.receipt).edge(name)
            self.assertEqual([t for t, _ in self.calls], ["mb_build"], name)
            _, kw = self.calls[0]
            self.assertFalse(set(kw) - set(inspect.signature(be.work.mb_build).parameters), name)
            self.assertEqual(kw["origin"], [1000, 176, 2000], name)
            for key in ("passed", "asPredicted", "why", "wrong", "strays", "endedInside", "ticks", "walked", "predicted"): self.assertIn(key, row, name)
            self.assertEqual(row["stoppedAt"], [3, 2, 3], name)

    def test_the_lamp_finding_is_judged_as_predicted_from_its_receipt(self):
        # The world reads as empty and the receipt names the lamp: the predicted stop, and not what a good builder does.
        self.receipt = {"state": "paused", "stopped": {"reason": "no_stance", "pos": [1003, 178, 2003]}, "ticks": 300}
        row = Fake(self.receipt).edge("ceiling_lamp")
        self.assertTrue(row["asPredicted"]); self.assertFalse(row["passed"])
        self.assertEqual(len(row["wrong"]), 50)

    def test_a_build_said_done_with_a_plan_cell_wrong_never_passes(self):
        # The sand fell after the job said built: the server reads the cell empty.
        self.receipt = {"state": "succeeded", "ticks": 10}; row = Fake(self.receipt).edge("sand_over_air")
        self.assertEqual(row["end"], "built"); self.assertEqual(row["wrong"], [[2, 2, 3]]); self.assertFalse(row["passed"])

    def test_the_geometry_gives_the_mechanism_each_prediction_names(self):
        # The lamp comes alone: every lining cell is within NEAR of its column, one lower.
        self.assertEqual(be.first_shown(be.ceiling_lamp()["cells"]), [[3, 2, 3]])
        # Staged, the whole lining comes at once.
        self.assertEqual(len(be.first_shown(be.ceiling_lamp(True)["cells"])), 49)
        # From the middle of the room: the bottom course (80) and more roof cells over nothing than the source keeps.
        shown = be.first_shown(be.room_from_middle()["cells"])
        roof = [p for p in shown if p[1] == 3]
        self.assertEqual(len(shown) - len(roof), 80); self.assertEqual(len(roof), 121); self.assertGreater(len(roof), be.KEPT)
        # And every roof cell that comes is nearer the start than every wall cell.
        start = be.room_from_middle()["start"]
        d = lambda p: sum((a - b) ** 2 for a, b in zip(p, start))
        self.assertLess(max(map(d, roof)), min(d(p) for p in shown if p[1] == 0))
        # The oven's far cell is more than NEAR columns from the oven, so it does not wait.
        self.assertIn([1, 4, 8], be.first_shown(be.oven_floating()["cells"]))


if __name__ == "__main__":
    unittest.main()
