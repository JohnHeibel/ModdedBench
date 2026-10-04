# SPDX-License-Identifier: LGPL-3.0-or-later
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'smoke'))
import build_order  # noqa: E402


class BuildOrderTests(unittest.TestCase):
    def test_a_row_laid_in_order_has_no_jumps(self):
        out = build_order.metrics([(x, 64, 0) for x in range(20)], ticks=100)
        self.assertEqual((out["clicks"], out["cells"], out["reclicks"], out["jumps"], out["meanStep"]), (20, 20, 0, 0, 1.0))
        self.assertEqual((out["ticksPerClick"], out["cellsPerMinute"], out["layerChanges"], out["overGap"]), (5.0, 240.0, 0, 0))

    def test_a_walk_between_two_ends_is_a_jump(self):
        out = build_order.metrics([(0, 64, 0), (1, 64, 0), (20, 64, 0), (2, 64, 0)])
        self.assertEqual((out["jumps"], out["jumpBlocks"], out["longestJump"]), (2, 37.0, 19.0))

    def test_a_cell_laid_over_one_still_to_come_is_counted(self):
        roof_first = [(0, 66, 0), (0, 65, 0), (0, 64, 0)]
        self.assertEqual(build_order.metrics(roof_first)["overGap"], 2)
        self.assertEqual(build_order.metrics(list(reversed(roof_first)))["overGap"], 0)
        # Never laid at all: the cell underneath is still wrong when the job ends.
        self.assertEqual(build_order.metrics([(5, 81, 5)], left=[[5, 80, 5]])["overGap"], 1)

    def test_a_cell_clicked_again_counts_once_as_a_cell(self):
        out = build_order.metrics([(0, 64, 0)] * 8, ticks=343)
        self.assertEqual((out["clicks"], out["cells"], out["reclicks"]), (8, 1, 7))
        self.assertEqual((out["reclickedCells"], out["maxAttempts"]), (1, 8))

    def test_repeated_clicks_are_counted_per_cell_for_comparing_click_intervals(self):
        out = build_order.metrics([(0, 64, 0), (0, 64, 0), (1, 64, 0), (2, 64, 0), (2, 64, 0), (2, 64, 0)])
        self.assertEqual((out["reclicks"], out["reclickedCells"], out["maxAttempts"]), (3, 2, 3))
        self.assertEqual(build_order.metrics([])["maxAttempts"], 0)

    def test_reads_a_job_from_its_files(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d) / "0123456789abcdef"
            Path(f"{base}.attempts.jsonl").write_text('{"count":1,"key":"1,64,-2"}\n{"count":1,"key":"2,64,-2"}\n', encoding="utf-8")
            Path(f"{base}.json").write_text(json.dumps({"receipt": {"state": "succeeded", "ticks": 10, "incorrect": []}}), encoding="utf-8")
            out = build_order.read(base)
            step = {"stage": 0, "y": 64, "index": 1, "of": 3, "left": 4, "first": [3, 64, -2]}
            Path(f"{base}.json").write_text(json.dumps({"specSummary": {"settings": {"clickInterval": 2}}, "receipt": {"state": "paused", "ticks": 4, "step": step}}), encoding="utf-8")
            fast = build_order.read(base)
        self.assertEqual((out["job"], out["state"], out["clicks"], out["ticksPerClick"]), ("01234567", "succeeded", 2, 5.0))
        self.assertEqual((out["clickInterval"], "step" in out), (5, False))
        self.assertEqual((fast["clickInterval"], fast["ticksPerClick"], fast["step"]), (2, 2.0, step))


if __name__ == "__main__":
    unittest.main()
