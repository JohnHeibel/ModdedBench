# SPDX-License-Identifier: MIT
import inspect
from pathlib import Path
import sys
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'smoke'))
import faceclick_course as fc  # noqa: E402


class Fake(fc.Clicks):
    """The scenarios with no game: the world is empty air and every call is recorded and answered as done."""
    c = types.SimpleNamespace(call=lambda method, **kw: {"class": None})
    def scene(self, blocks=(), stacks=(), start=(0, 0)): return [100, 176, 200]
    def seen(self, origin, rels=None): return {tuple(r): (fc.AIR, 0) for r in rels or []}
    def strays(self, origin, expected): return []


class FaceclickCourseTests(unittest.TestCase):
    def setUp(self):
        self.calls = []; real = fc.call
        fc.call = lambda fn, **kw: self.calls.append((fn.__name__, kw)) or {"state": "succeeded", "jobId": "job-1", "clicks": {"done": 7, "verified": 1}}
        self.addCleanup(setattr, fc, "call", real)

    def test_every_scenario_is_one_tool_call_a_build_in_the_tools_own_parameters(self):
        for name in fc.SCENARIOS:
            self.calls.clear(); row = getattr(Fake(), name)()
            self.assertIn("passed", row); self.assertIn("why", row)
            self.assertTrue(self.calls, name)
            for tool, kw in self.calls:
                self.assertIn(tool, ("mb_build", "mb_build_preview", "mb_work_resume"), name)
                self.assertFalse(set(kw) - set(inspect.signature(getattr(fc.work, tool)).parameters), (name, tool))
                self.assertFalse({"steps", "pattern", "access"} & set(kw), name)

    def test_the_acceptance_shape_is_one_call_and_one_drawing(self):
        Fake().click_multiblock()
        builds = [kw for tool, kw in self.calls if tool == "mb_build"]
        self.assertEqual(len(builds), 1); self.assertEqual(set(builds[0]), {"drawing", "allow_place"})
        cells = fc.plan.from_drawing(builds[0]["drawing"])[0]
        clicked = [c for c in cells if "click" in c]
        self.assertEqual((len(cells), len(clicked)), (37, 7))
        self.assertEqual({c.get("stage", 0) for c in cells if "click" not in c}, {0})                 # the body is plain and first
        looks = sorted(c["click"]["look"]["toward"] for c in clicked if "look" in c["click"])
        self.assertEqual(looks, ["down", "east", "south", "west"])                                    # controller and three hatches
        self.assertEqual({c["stage"] for c in clicked if "look" in c["click"]}, {1})
        later = [c for c in clicked if "face" in c["click"]]
        self.assertEqual((len(later), {c["stage"] for c in later}), (3, {2}))                         # two cables and the chest, last
        top = next(c for c in clicked if c["click"].get("look") == {"toward": "down"})
        self.assertEqual(top["pos"], [3, 3, 1])                                                       # in the roof, over the hollow


if __name__ == "__main__":
    unittest.main()
