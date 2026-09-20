# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from gtnh_selections import SelectionError, SelectionStore


class SelectionStoreTests(unittest.TestCase):
    def setUp(self): self.store = SelectionStore()

    def test_world_scoping_pos_pair_add_remove_clear_and_undo(self):
        self.store.pos1("save:a", [3, 4, 5]); self.store.pos2("save:a", [1, 4, 5])
        self.store.add("save:a", [8, 4, 5], [8, 5, 5])
        self.assertEqual([x["size"] for x in self.store.view("save:a")["selections"]], [[3, 1, 1], [1, 2, 1]])
        self.assertEqual(self.store.view("save:b")["selections"], [])
        self.store.remove("save:a", 0); self.assertEqual(len(self.store.view("save:a")["selections"]), 1)
        self.store.undo("save:a"); self.assertEqual(len(self.store.view("save:a")["selections"]), 2)
        self.store.clear("save:a"); self.store.undo("save:a")
        self.assertEqual(len(self.store.view("save:a")["selections"]), 2)

    def test_transform_targets_preserve_actual_endpoint_geometry(self):
        self.store.add("w", [3, 3, 3], [1, 1, 1])
        self.store.add("w", [10, 2, 0], [11, 2, 0])
        self.store.transform("w", "expand", "oldest", "east", 2)
        first = self.store.view("w")["selections"][0]
        self.assertEqual(first["pos1"], [5, 3, 3]); self.assertEqual(first["min"], [1, 1, 1]); self.assertEqual(first["max"], [5, 3, 3])
        self.store.transform("w", "shift", "newest", "up", 4)
        self.assertEqual(self.store.view("w")["selections"][1]["min"], [10, 6, 0])
        self.store.transform("w", "contract", "all", "west", 1)
        # Match retained Selection.contract endpoint logic for a reversed pair.
        self.assertEqual(self.store.view("w")["selections"][0]["max"], [4, 3, 3])

    def test_copy_requires_complete_union_and_paste_preserves_air_and_holes(self):
        self.store.add("w", [10, 5, 10], [11, 5, 10])
        self.store.add("w", [13, 5, 10], [13, 5, 10])
        rows = [{"pos": [10, 5, 10], "loaded": True, "id": "minecraft:stone", "meta": 0},
                {"pos": [11, 5, 10], "loaded": True, "id": "minecraft:air", "meta": 0},
                {"pos": [13, 5, 10], "loaded": True, "id": "mod:block", "meta": 7}]
        copied = self.store.copy("w", [9, 5, 9], rows)
        self.assertEqual(copied["offset"], [1, 0, 1]); self.assertEqual(copied["size"], [4, 1, 1])
        plan = self.store.paste("w", [20, 7, 20])
        self.assertEqual(plan["origin"], [21, 7, 21])
        self.assertEqual([x["pos"] for x in plan["cells"]], [[0, 0, 0], [1, 0, 0], [3, 0, 0]])
        self.assertTrue(plan["cells"][1]["clear"])
        self.assertNotIn([2, 0, 0], [x["pos"] for x in plan["cells"]])

    def test_copy_rejects_missing_duplicate_outside_and_unloaded_observations(self):
        self.store.add("w", [0, 1, 0], [1, 1, 0]); good = {"loaded": True, "id": "minecraft:air", "meta": 0}
        with self.assertRaisesRegex(SelectionError, "incomplete"): self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], **good}])
        with self.assertRaisesRegex(SelectionError, "duplicate"): self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], **good}, {"pos": [0, 1, 0], **good}])
        with self.assertRaisesRegex(SelectionError, "outside"): self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], **good}, {"pos": [2, 1, 0], **good}])
        with self.assertRaisesRegex(SelectionError, "unloaded"): self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], **good}, {"pos": [1, 1, 0], **good, "loaded": False}])

    def test_copy_retains_tile_requirement_and_refuses_paste(self):
        self.store.add("w", [0, 1, 0], [0, 1, 0])
        copied = self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], "loaded": True,
            "id": "mod:machine", "meta": 2, "tileClass": "mod.TileMachine"}])
        self.assertEqual(copied["requirements"][0]["tileClass"], "mod.TileMachine")
        with self.assertRaisesRegex(SelectionError, "unsupported tile-entity"):
            self.store.paste("w", [4, 1, 4])
        copied = self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], "loaded": True,
            "id": "mod:machine", "meta": 2, "tileClass": "mod.TileMachine"}], block_states_only=True)
        self.assertEqual(copied["requirements"], []); self.assertTrue(copied["blockStatesOnly"])
        self.assertEqual(self.store.paste("w", [4, 1, 4])["cells"][0]["id"], "mod:machine")

    def test_copy_uses_observed_picked_item_as_independent_verification(self):
        self.store.add("w", [0, 1, 0], [0, 1, 0])
        self.store.copy("w", [0, 1, 0], [{"pos": [0, 1, 0], "loaded": True,
            "id": "mod:pipe", "meta": 0, "tileClass": None,
            "pickedItem": {"id": "mod:pipe", "meta": 12, "count": 1, "name": "Configured Pipe"}}])
        cell = self.store.paste("w", [4, 1, 4])["cells"][0]
        self.assertEqual(cell["verify"], {"pickedItem": {"id": "mod:pipe", "meta": 12}})
        self.assertNotIn("item", cell)

    def test_copy_preserves_explicit_placement_item_variant_independently(self):
        self.store.add("w", [0, 1, 0], [1, 1, 0])
        self.store.copy("w", [0, 1, 0], [
            {"pos": [0, 1, 0], "loaded": True, "id": "gregtech:gt.blockmachines", "meta": 1,
             "tileClass": "gregtech.Tile", "pickedItem": {"id": "gregtech:gt.blockmachines", "meta": 511, "count": 1},
             "placementItem": {"id": "gregtech:gt.blockmachines", "meta": 511, "count": 1, "name": "GT Machine"}},
            {"pos": [1, 1, 0], "loaded": True, "id": "mod:block", "meta": 1,
             "tileClass": None, "pickedItem": {"id": "mod:special_pick", "meta": 9, "count": 1}}],
            block_states_only=True)
        cells = self.store.paste("w", [4, 1, 4])["cells"]
        self.assertEqual(cells[0]["item"], {"id": "gregtech:gt.blockmachines", "meta": 511})
        self.assertEqual(cells[0]["verify"]["pickedItem"]["meta"], 511)
        self.assertNotIn("item", cells[1])

    def test_shape_geometry_uses_each_source_region(self):
        self.store.add("w", [0, 1, 0], [4, 5, 4]); self.store.add("w", [10, 1, 0], [12, 3, 2])
        filled = self.store.shape_cells("w", {"id": "minecraft:stone"})
        ellipsoid = self.store.shape_cells("w", {"id": "minecraft:stone"}, "ellipsoid")
        hollow = self.store.shape_cells("w", {"id": "minecraft:stone"}, "hollow_ellipsoid")
        cylinder = self.store.shape_cells("w", {"id": "minecraft:stone"}, "cylinder", "y")
        self.assertEqual(len(filled), 125 + 27)
        self.assertLess(len(hollow), len(ellipsoid)); self.assertLess(len(ellipsoid), len(filled))
        self.assertTrue(any(x["pos"][0] >= 10 for x in cylinder))


if __name__ == "__main__": unittest.main()
