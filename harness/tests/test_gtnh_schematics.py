# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
import gzip
import contextlib
import io
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from gtnh_schematics import MAX_OBJECT_ELEMENTS, SchematicError, _NBT, import_schematic, main


def string(value):
    raw = value.encode(); return struct.pack(">H", len(raw)) + raw


def tag(kind, name, payload): return bytes([kind]) + string(name) + payload


def compound(values):
    out = bytearray()
    for kind, name, payload in values: out += tag(kind, name, payload)
    return bytes(out) + b"\0"


def nbt_root(name, values): return bytes([10]) + string(name) + compound(values)
def compound_list(values): return bytes([10]) + struct.pack(">i", len(values)) + b"".join(compound(x) for x in values)
def long_array(values): return struct.pack(">i", len(values)) + b"".join(struct.pack(">q", x) for x in values)


def sponge_nbt(block_data):
    palette = compound([(3, "minecraft:stone", struct.pack(">i", 0)),
                        (3, "mod:axis[facing=north]", struct.pack(">i", 1)),
                        (3, "mod:large", struct.pack(">i", 128)),
                        (3, "minecraft:dirt", struct.pack(">i", 2))])
    return nbt_root("Schematic", [(3, "Version", struct.pack(">i", 2)),
        (2, "Width", struct.pack(">h", 4)), (2, "Height", struct.pack(">h", 1)),
        (2, "Length", struct.pack(">h", 1)), (10, "Palette", palette),
        (7, "BlockData", struct.pack(">i", len(block_data)) + block_data)])


def palette_list(names):
    entries = [[(8, "Name", string(name))] for name in names]
    return compound_list(entries)


def litematic_region(position, size, names, words):
    vec = lambda value: compound([(3, axis, struct.pack(">i", value[i])) for i, axis in enumerate(("x", "y", "z"))])
    return compound([(10, "Position", vec(position)), (10, "Size", vec(size)),
                     (9, "BlockStatePalette", palette_list(names)), (12, "BlockStates", long_array(words))])


def litematic_nbt(regions, enclosing):
    enclosing_tag = compound([(3, axis, struct.pack(">i", enclosing[i])) for i, axis in enumerate(("x", "y", "z"))])
    return nbt_root("Litematic", [(3, "Version", struct.pack(">i", 4)),
        (10, "Metadata", compound([(10, "EnclosingSize", enclosing_tag)])),
        (10, "Regions", compound([(10, name, region) for name, region in regions]))])


def schematic(*, blocks=b"\x01\x2c", data=b"\x00\x03", add=None, mapping=None, tiles=(), width=2, height=1, length=1):
    values = [(8, "Materials", string("Alpha")),
              (2, "Width", struct.pack(">h", width)), (2, "Height", struct.pack(">h", height)),
              (2, "Length", struct.pack(">h", length)),
              (7, "Blocks", struct.pack(">i", len(blocks)) + blocks),
              (7, "Data", struct.pack(">i", len(data)) + data)]
    if add is not None: values.append((7, "AddBlocks", struct.pack(">i", len(add)) + add))
    if mapping is not None:
        values.append((10, "SchematicaMapping", compound([(2, k, struct.pack(">h", v)) for k, v in mapping.items()])))
    tile_payload = bytes([10]) + struct.pack(">i", len(tiles)) + b"".join(compound(tile) for tile in tiles)
    values.append((9, "TileEntities", tile_payload))
    return bytes([10]) + string("Schematic") + compound(values)


class SchematicTests(unittest.TestCase):
    def write(self, directory, name, data):
        path = Path(directory) / name; path.write_bytes(data); return path

    def test_canonical_validates_and_filters_air(self):
        with tempfile.TemporaryDirectory() as directory:
            doc = {"origin": [9, 9, 9], "cells": [
                {"pos": [0, 0, 0], "id": "minecraft:stone", "meta": 2},
                {"pos": [1, 0, 0], "id": "minecraft:air"}]}
            path = self.write(directory, "x.json", json.dumps(doc).encode())
            result = import_schematic(path, origin=[1, 2, 3])
            self.assertEqual(result["build"]["origin"], [1, 2, 3])
            self.assertEqual(len(result["build"]["cells"]), 1)
            self.assertEqual(import_schematic(path)["build"]["origin"], [9, 9, 9])

    def test_canonical_rejects_duplicates_and_embedded_tile_state(self):
        with tempfile.TemporaryDirectory() as directory:
            doc = {"cells": [{"pos": [0, 0, 0], "id": "a:b"}, {"pos": [0, 0, 0], "id": "a:c"}]}
            path = self.write(directory, "x.json", json.dumps(doc).encode())
            with self.assertRaisesRegex(SchematicError, "duplicate"): import_schematic(path)
            doc = {"cells": [], "tileEntities": [{"id": "machine"}]}
            path.write_text(json.dumps(doc))
            with self.assertRaisesRegex(SchematicError, "unsupported fields"): import_schematic(path)
            doc = {"cells": [{"pos":[0,0,0], "id":"mod:machine", "tileNbt":{"energy":4}}]}
            path.write_text(json.dumps(doc))
            with self.assertRaisesRegex(SchematicError, "unsupported fields"): import_schematic(path)

    def test_canonical_rejects_truthy_strings_and_invalid_placement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "x.json", json.dumps({"cells": [], "replaceExisting": "false"}).encode())
            with self.assertRaisesRegex(SchematicError, "boolean"): import_schematic(path)
            path.write_text(json.dumps({"cells": [{"pos": [0, 0, 0], "id": "a:b",
                "placement": {"face": "sideways"}}]}))
            with self.assertRaisesRegex(SchematicError, "face"): import_schematic(path)

    def test_legacy_requires_explicit_numeric_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "x.schematic", gzip.compress(schematic()))
            with self.assertRaisesRegex(SchematicError, "numeric block ID 1"): import_schematic(path)
            result = import_schematic(path, origin=[0,1,0], registry={1: "minecraft:stone", 44: "minecraft:slab"})
            self.assertEqual([x["id"] for x in result["build"]["cells"]], ["minecraft:stone", "minecraft:slab"])

    def test_schematica_mapping_and_addblocks_are_combined(self):
        with tempfile.TemporaryDirectory() as directory:
            # Bundled Baritone assigns the low nibble to the second entry.
            raw = schematic(add=b"\x01", mapping={"minecraft:stone": 1, "mod:block": 300})
            result = import_schematic(self.write(directory, "x.schematic", gzip.compress(raw)),origin=[0,1,0])
            self.assertEqual(result["build"]["cells"][1], {"pos": [1, 0, 0], "id": "mod:block", "meta": 3})

    def test_primary_schematica_writer_mixed_even_odd_high_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            # Fixed bytes follow Lunatrius/Schematica's SchematicAlpha writer:
            # (extra[even] << 4) | extra[odd]. This is not produced by a test serializer.
            ids = [0x123, 0x456, 0x789, 0xABC]
            raw = schematic(width=4, blocks=b"\x23\x56\x89\xbc", data=b"\0\1\2\3",
                add=b"\x14\x7a", mapping={f"fixture:block{i}": value for i, value in enumerate(ids)})
            result = import_schematic(self.write(directory, "mixed.schematic", raw),origin=[0,1,0])
            self.assertEqual([cell["id"] for cell in result["build"]["cells"]],
                             [f"fixture:block{i}" for i in range(4)])

    def test_tile_nbt_is_preserved_only_as_unsupported_requirement(self):
        with tempfile.TemporaryDirectory() as directory:
            tile = [(8, "id", string("mod:machine")), (3, "x", struct.pack(">i", 1)),
                    (3, "y", struct.pack(">i", 0)), (3, "z", struct.pack(">i", 0)),
                    (3, "energy", struct.pack(">i", 42))]
            raw = schematic(mapping={"minecraft:stone": 1, "minecraft:slab": 44}, tiles=[tile])
            result = import_schematic(self.write(directory, "x.schematic", raw),origin=[0,1,0])
            requirement = result["requirements"][0]
            self.assertFalse(requirement["supported"])
            self.assertEqual(requirement["nbt"]["energy"], 42)
            self.assertNotIn("nbt", result["build"]["cells"][1])

    def test_rejects_bad_lengths_out_of_bounds_tiles_and_state_transform(self):
        with tempfile.TemporaryDirectory() as directory:
            bad = schematic(blocks=b"\x01", mapping={"minecraft:stone": 1})
            path = self.write(directory, "bad.schematic", bad)
            with self.assertRaisesRegex(SchematicError, "lengths"): import_schematic(path)
            tile = [(3, "x", struct.pack(">i", 2)), (3, "y", struct.pack(">i", 0)), (3, "z", struct.pack(">i", 0))]
            path.write_bytes(schematic(mapping={"minecraft:stone": 1, "minecraft:slab": 44}, tiles=[tile]))
            with self.assertRaisesRegex(SchematicError, "outside"): import_schematic(path)
            with self.assertRaisesRegex(SchematicError, "state remapper"): import_schematic(path, rotation=90)

    def test_rejects_non_alpha_mcedit_materials(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = schematic(mapping={"minecraft:stone": 1, "minecraft:slab": 44}).replace(b"Alpha", b"Beta!")
            with self.assertRaisesRegex(SchematicError, "Materials"):
                import_schematic(self.write(directory, "x.schematic", raw))

    def test_cli_reports_invalid_input_instead_of_crashing(self):
        with tempfile.TemporaryDirectory() as directory:
            path=self.write(directory,"bad.schematic",b"not nbt")
            error=io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(2,main([str(path)]))
            self.assertIn("schematic error:",error.getvalue())

    def test_nbt_list_declared_over_object_budget_is_rejected_before_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            # No child compounds follow. A parser that checks the aggregate
            # allocation budget first reports the budget, rather than truncation
            # after attempting hundreds of thousands of Python objects.
            entities=bytes([10])+struct.pack(">i",MAX_OBJECT_ELEMENTS+1)
            raw=bytes([10])+string("Schematic")+tag(9,"Entities",entities)+b"\0"
            path=self.write(directory,"oversized-list.schematic",raw)
            with self.assertRaisesRegex(SchematicError,"object element budget"):
                import_schematic(path)

    def test_nbt_byte_arrays_stay_compact_during_parse(self):
        raw=schematic(blocks=b"\x01"*4096,data=b"\0"*4096,width=4096)
        parsed=_NBT(raw).root()
        self.assertIsInstance(parsed["Blocks"],bytes)
        self.assertEqual(4096,len(parsed["Blocks"]))

    def test_nbt_accepts_spec_valid_empty_end_typed_list(self):
        raw = nbt_root("Schematic", [(9, "Entities", b"\0" + struct.pack(">i", 0))])
        self.assertEqual(_NBT(raw).root()["Entities"], [])

    def test_requirement_byte_arrays_share_one_conversion_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            array=struct.pack(">i",9000)+b"\xff"*9000
            tile=[(3,"x",struct.pack(">i",0)),(3,"y",struct.pack(">i",0)),
                  (3,"z",struct.pack(">i",0)),(7,"first",array),(7,"second",array)]
            raw=schematic(blocks=b"\x01",data=b"\0",width=1,
                          mapping={"minecraft:stone":1},tiles=[tile])
            path=self.write(directory,"combined-extension.schematic",raw)
            with self.assertRaisesRegex(SchematicError,"extension exceeds 64 KiB"):
                import_schematic(path,origin=[0,1,0])

    def test_canonical_output_matches_native_workspec_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            doc={"origin":[10,1,10],"timeoutTicks":72000,"cells":[{"pos":[0,0,0],
                "id":"mod:block","meta":15,"item":{"id":"mod:item","meta":32767,"nbt":"{mode:1b}"},
                "placement":{"face":"east","hit":[-16,0.5,16],"yaw":360000,"pitch":-90,
                             "verifyAfterPlacement":True}}]}
            result=import_schematic(self.write(directory,"native.json",json.dumps(doc).encode()))["build"]
            self.assertEqual(5,result["cells"][0]["placement"]["face"])
            self.assertEqual({"face","hit","yaw","pitch","verifyAfterPlacement"},
                             set(result["cells"][0]["placement"]))
            self.assertIsInstance(result["cells"][0]["item"]["nbt"],str)
            self.assertEqual(72000,result["timeoutTicks"])

    def test_canonical_rejects_fields_and_values_native_would_reject(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"native.json"
            cases=[
                ({"cells":[{"pos":[0,1,0],"id":"a:b","placement":{"sneak":True}}]},"unknown fields"),
                ({"cells":[{"pos":[0,1,0],"id":"a:b","item":{"id":"a:i","nbt":{"x":1}}}]},"SNBT string"),
                ({"timeoutTicks":72001,"cells":[{"pos":[0,1,0],"id":"a:b"}]},"72000"),
                ({"cells":[{"pos":[0,0,0],"id":"a:b"}]},"translated cell"),
                ({"origin":[30000000,1,0],"cells":[{"pos":[1,0,0],"id":"a:b"}]},"translated cell"),
                ({"cells":[{"pos":[0,1,0],"id":"minecraft:air"}]},"1..16384"),
            ]
            for document,message in cases:
                path.write_text(json.dumps(document))
                with self.subTest(message=message),self.assertRaisesRegex(SchematicError,message):
                    import_schematic(path)

    def test_legacy_rejects_local_y_above_workspec_limit_even_if_translation_fits(self):
        with tempfile.TemporaryDirectory() as directory:
            blocks=b"\0"*256+b"\x01"
            raw=schematic(blocks=blocks,data=b"\0"*257,width=1,height=257,
                          mapping={"minecraft:stone":1})
            path=self.write(directory,"sparse-height257.schematic",raw)
            with self.assertRaisesRegex(SchematicError,r"cell pos\[1\].*-255\.\.255"):
                import_schematic(path,origin=[0,-255,0])

    def test_sponge_varints_and_explicit_property_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            # Fixed varint bytes encode palette indexes 0, 1, 128, 2.
            path = self.write(directory, "states.schem", gzip.compress(sponge_nbt(b"\x00\x01\x80\x01\x02")))
            mapping = {"mod:axis[facing=north]": {"id": "mod:axis", "meta": 4}}
            result = import_schematic(path, origin=[0, 1, 0], palette=mapping)
            self.assertEqual([x["meta"] for x in result["build"]["cells"]], [0, 4, 0, 0])
            self.assertEqual(result["build"]["size"], [4, 1, 1])
            with self.assertRaisesRegex(SchematicError, "explicit state mapping"):
                import_schematic(path, origin=[0, 1, 0])

    def test_sponge_rejects_truncated_trailing_and_overlong_varints(self):
        with tempfile.TemporaryDirectory() as directory:
            for name, data, message in (("truncated", b"\x00\x01\x80", "truncated"),
                                        ("trailing", b"\x00\x01\x80\x01\x02\x00", "trailing"),
                                        ("overlong", b"\x00\x01\x80\x80\x80\x80\x80\x00", "five bytes")):
                with self.subTest(name=name):
                    path = self.write(directory, name + ".schem", sponge_nbt(data))
                    with self.assertRaisesRegex(SchematicError, message):
                        import_schematic(path, origin=[0, 1, 0], palette={"mod:axis[facing=north]": {"id": "mod:axis"}})

    def test_litematica_multiple_regions_and_negative_size(self):
        with tempfile.TemporaryDirectory() as directory:
            names = ["minecraft:stone", "minecraft:dirt"]
            regions = [("later", litematic_region([10, 0, 0], [2, 1, 1], names, [4])),
                       ("negative", litematic_region([8, 0, 0], [-2, 1, 1], names, [1]))]
            path = self.write(directory, "multi.litematic", gzip.compress(litematic_nbt(regions, [5, 1, 1])))
            result = import_schematic(path, origin=[0, 1, 0])
            self.assertEqual([x["pos"] for x in result["build"]["cells"]], [[3, 0, 0], [4, 0, 0], [0, 0, 0], [1, 0, 0]])
            self.assertEqual([x["id"] for x in result["build"]["cells"]],
                             ["minecraft:stone", "minecraft:dirt", "minecraft:dirt", "minecraft:stone"])

    def test_litematica_packed_indexes_cross_word_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            names = [f"fixture:block{i}" for i in range(5)]
            # Independent fixed 3-bit packed words for 0,1,2,3,4 repeated;
            # entry 21 starts at bit 63 and spans both longs.
            region = litematic_region([0, 0, 0], [23, 1, 1], names, [-8588063626344774008, 8])
            path = self.write(directory, "boundary.litematic", litematic_nbt([("r", region)], [23, 1, 1]))
            result = import_schematic(path, origin=[0, 1, 0])
            self.assertEqual([x["id"] for x in result["build"]["cells"]], names * 4 + names[:3])

    def test_litematica_rejects_wrong_long_array_and_palette_index(self):
        with tempfile.TemporaryDirectory() as directory:
            names = ["minecraft:stone", "minecraft:dirt"]
            short = litematic_region([0, 0, 0], [33, 1, 1], names, [0])
            path = self.write(directory, "short.litematic", litematic_nbt([("r", short)], [33, 1, 1]))
            with self.assertRaisesRegex(SchematicError, "length is invalid"): import_schematic(path, origin=[0, 1, 0])
            invalid = litematic_region([0, 0, 0], [1, 1, 1], names, [3])
            path.write_bytes(litematic_nbt([("r", invalid)], [1, 1, 1]))
            with self.assertRaisesRegex(SchematicError, "invalid palette index"): import_schematic(path, origin=[0, 1, 0])

    def test_extension_fallback_and_validated_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write(directory, "house.schematic", schematic(blocks=b"\x01", data=b"\0", width=1,
                       mapping={"minecraft:stone": 1}))
            result = import_schematic("house", schematic_directory=directory, origin=[0, 1, 0])
            self.assertEqual(result["build"]["cells"][0]["id"], "minecraft:stone")
            path = self.write(directory, "settings.json", json.dumps({"origin": [0, 1, 0], "mode": "builder",
                "settings": {"layerHeight": 2, "buildRepeatCount": -1, "metadataMasks": {"mod:block": 7}},
                "cells": [{"pos": [0, 0, 0], "id": "mod:block"}]}).encode())
            build = import_schematic(path)["build"]
            self.assertEqual(build["mode"], "builder")
            self.assertEqual(build["settings"]["metadataMasks"], {"mod:block": 7})
            path.write_text(json.dumps({"settings": {"unknown": True}, "cells": [{"pos": [0, 1, 0], "id": "a:b"}]}))
            with self.assertRaisesRegex(SchematicError, "unsupported fields"): import_schematic(path)

    def test_explicit_builder_mode_raises_cell_limit_without_changing_default(self):
        with tempfile.TemporaryDirectory() as directory:
            cells = [{"pos": [x, y + 1, z], "id": "minecraft:stone"}
                     for y in range(17) for z in range(32) for x in range(32)]
            path = self.write(directory, "large.json", json.dumps({"cells": cells}).encode())
            with self.assertRaisesRegex(SchematicError, "explicit-cell limit"): import_schematic(path)
            result = import_schematic(path, mode="builder")
            self.assertEqual(result["build"]["mode"], "builder")
            self.assertEqual(len(result["build"]["cells"]), 17 * 32 * 32)

    def test_settings_match_java_substitute_and_mode_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "settings.json", json.dumps({"mode": "builder",
                "settings": {"buildSubstitutes": {"mod:source": ["mod:fallback"]},
                             "acceptableThrowawayItems": [{"id": "mod:item", "ore": "oreAny", "meta": 2}]},
                "cells": [{"pos": [0, 1, 0], "id": "mod:source", "meta": 7}]}).encode())
            settings = import_schematic(path)["build"]["settings"]
            self.assertEqual(settings["buildSubstitutes"]["mod:source"], ["mod:fallback"])
            self.assertEqual(settings["acceptableThrowawayItems"][0]["ore"], "oreAny")
            path.write_text(json.dumps({"settings": {"buildInLayers": True},
                                        "cells": [{"pos": [0, 1, 0], "id": "mod:source"}]}))
            with self.assertRaisesRegex(SchematicError, "require mode builder"): import_schematic(path)

    def test_canonical_preserves_validated_route_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "permissions.json", json.dumps({"allowBreak": True, "allowPlace": False,
                "cells": [{"pos": [0, 1, 0], "id": "minecraft:stone"}]}).encode())
            build = import_schematic(path)["build"]
            self.assertIs(build["allowBreak"], True); self.assertIs(build["allowPlace"], False)
            path.write_text(json.dumps({"allowBreak": "yes", "cells": [{"pos": [0, 1, 0], "id": "minecraft:stone"}]}))
            with self.assertRaisesRegex(SchematicError, "boolean"): import_schematic(path)

    def test_verify_picked_item_is_independent_from_placement_item_and_palette_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            document = {"cells": [{"pos": [0, 1, 0], "id": "mod:machine", "meta": 2,
                "item": {"id": "mod:machine_item", "meta": 0},
                "verify": {"pickedItem": {"id": "mod:machine_item", "meta": 7, "ore": "machineTier"}}}]}
            path = self.write(directory, "verify.json", json.dumps(document).encode())
            cell = import_schematic(path)["build"]["cells"][0]
            self.assertEqual(cell["item"]["meta"], 0); self.assertEqual(cell["verify"]["pickedItem"]["meta"], 7)
            mapped = {"mod:machine[variant=seven]": {"id": "mod:machine", "meta": 2,
                "verify": {"pickedItem": {"id": "mod:machine_item", "meta": 7}}}}
            schem = sponge_nbt(b"\x00\x01\x80\x01\x02")
            # Reuse the existing property-bearing state by mapping its exact key.
            mapped = {"mod:axis[facing=north]": mapped["mod:machine[variant=seven]"]}
            result = import_schematic(self.write(directory, "verify.schem", schem), origin=[0, 1, 0], palette=mapped)
            self.assertEqual(result["build"]["cells"][1]["verify"]["pickedItem"]["meta"], 7)
            document["cells"][0]["verify"] = {"pickedItem": {"id": "mod:item", "count": 1}}
            path.write_text(json.dumps(document))
            with self.assertRaisesRegex(SchematicError, "unknown fields"): import_schematic(path)


if __name__ == "__main__": unittest.main()
