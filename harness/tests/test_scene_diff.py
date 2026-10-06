# SPDX-License-Identifier: MIT
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'smoke'))
import builder_shell as bs  # noqa: E402

# What Snbt.write gives (SnbtTest.writesOneCanonicalText holds the same line).
TEXT = r'{Items:[0:{Count:64b,Slot:0b,id:1s,tag:{display:{Name:"a \"q\" , } ] : b\\"}}},1:{Count:1b,Slot:5b,id:2s}],bytes:[B;1,-2],empty:[],f:1.0E-4f,id:"Chest",ints:[I;],"odd key":"",x:10}'
CHEST = '{Items:[0:{Count:%db,Slot:0b,id:1s}],id:"Chest",ticks:%d,x:1}'


class SceneDiffTests(unittest.TestCase):
    def test_text_nbt_parses_to_its_leaves(self):
        tag = bs.snbt(TEXT)
        self.assertEqual(list(tag), ["Items", "bytes", "empty", "f", "id", "ints", '"odd key"', "x"])
        self.assertEqual(tag["Items"][0]["tag"]["display"]["Name"], r'"a \"q\" , } ] : b\\"')
        self.assertEqual(tag["Items"][1], {"Count": "1b", "Slot": "5b", "id": "2s"})
        self.assertEqual((tag["bytes"], tag["empty"], tag["f"], tag["ints"], tag['"odd key"'], tag["x"]), ("[B;1,-2]", [], "1.0E-4f", "[I;]", '""', "10"))
        flat = bs.leaves(tag)
        self.assertEqual(flat[("Items", 0, "Count")], "64b")
        self.assertEqual(flat[("empty",)], [])
        self.assertEqual(len(flat), 14)

    def test_cells_outside_the_allowed_set_are_reported(self):
        stone, dirt = {"id": "minecraft:stone", "meta": 0}, {"id": "minecraft:dirt", "meta": 0}
        before = {(0, 0, 0): stone, (1, 0, 0): stone, (2, 0, 0): stone}
        after = {(0, 0, 0): stone, (1, 0, 0): dirt, (3, 0, 0): dirt, (4, 0, 0): dirt}
        self.assertEqual(bs.diff(before, after, allowed=[(4, 0, 0)]), [
            {"pos": [1, 0, 0], "before": ["minecraft:stone", 0], "after": ["minecraft:dirt", 0], "paths": []},
            {"pos": [2, 0, 0], "before": ["minecraft:stone", 0], "after": None, "paths": []},
            {"pos": [3, 0, 0], "before": None, "after": ["minecraft:dirt", 0], "paths": []}])
        self.assertEqual(bs.diff(before, before), [])

    def test_tile_nbt_differs_by_path_and_volatile_paths_are_ignored(self):
        chest = lambda count, ticks: {(0, 0, 0): {"id": "minecraft:chest", "meta": 2, "snbt": CHEST % (count, ticks)}}
        self.assertEqual(bs.diff(chest(64, 1), chest(63, 2)), [{"pos": [0, 0, 0], "before": ["minecraft:chest", 2], "after": ["minecraft:chest", 2], "paths": [["Items", 0, "Count"], ["ticks"]]}])
        self.assertEqual(bs.diff(chest(64, 1), chest(64, 2), ignore=[("ticks",)]), [])
        self.assertEqual([d["paths"] for d in bs.diff(chest(64, 1), chest(63, 2), ignore=[("ticks",)])], [[["Items", 0, "Count"]]])
        # A tile that lost its NBT altogether differs on every path it had.
        bare = {(0, 0, 0): {"id": "minecraft:chest", "meta": 2}}
        self.assertEqual(len(bs.diff(chest(64, 1), bare)[0]["paths"]), 6)


if __name__ == '__main__':
    unittest.main()
