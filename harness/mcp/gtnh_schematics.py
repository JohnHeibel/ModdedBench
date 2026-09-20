# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Bounded canonical JSON and legacy MCEdit ``.schematic`` importer.

The result contains ``build`` (the canonical Java build request) and
``requirements`` (tile-entity NBT that needs a model/mod-specific adapter).
Tile entity data is never represented as ordinary block placement.
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any

MAX_COMPRESSED = 32 * 1024 * 1024
MAX_DECOMPRESSED = 128 * 1024 * 1024
MAX_VOLUME = 16 * 1024 * 1024
MAX_CELLS = 16_384
MAX_BUILDER_CELLS = 1_048_576
MAX_DEPTH = 64
MAX_STRING = 1 * 1024 * 1024
MAX_OBJECT_ELEMENTS = 262_144
MAX_EXTENSION_JSON = 64 * 1024
SCHEMATIC_EXTENSIONS = (".schematic", ".schem", ".litematic", ".json")


class SchematicError(ValueError): pass


class _NBT:
    def __init__(self, data: bytes): self.data, self.at, self.object_elements = data, 0, 0
    def charge(self, count: int) -> None:
        if count < 0 or self.object_elements + count > MAX_OBJECT_ELEMENTS:
            raise SchematicError("NBT object element budget exceeded")
        self.object_elements += count
    def take(self, size: int) -> bytes:
        if size < 0 or self.at + size > len(self.data): raise SchematicError("truncated NBT")
        result = self.data[self.at:self.at + size]; self.at += size; return result
    def number(self, code: str): return struct.unpack(">" + code, self.take(struct.calcsize(">" + code)))[0]
    def string(self) -> str:
        size = self.number("H")
        if size > MAX_STRING: raise SchematicError("NBT string exceeds limit")
        try: return self.take(size).decode("utf-8")
        except UnicodeDecodeError as exc: raise SchematicError("invalid NBT UTF-8") from exc
    def payload(self, tag: int, depth: int = 0):
        if depth > MAX_DEPTH: raise SchematicError("NBT nesting exceeds limit")
        if tag == 1: return self.number("b")
        if tag == 2: return self.number("h")
        if tag == 3: return self.number("i")
        if tag == 4: return self.number("q")
        if tag == 5: return self.number("f")
        if tag == 6: return self.number("d")
        if tag == 7:
            size = self.number("i")
            if size < 0 or size > MAX_DECOMPRESSED: raise SchematicError("invalid NBT byte array length")
            # Keep bulk block/data arrays compact. Converting each byte to a
            # Python int can amplify a valid 16 MiB schematic past 500 MiB.
            return self.take(size)
        if tag == 8: return self.string()
        if tag == 9:
            child, size = self.number("B"), self.number("i")
            if child > 12 or child == 0 and size != 0 or size < 0 or size > MAX_VOLUME: raise SchematicError("invalid NBT list")
            self.charge(size)
            return [self.payload(child, depth + 1) for _ in range(size)]
        if tag == 10:
            result = {}
            while True:
                child = self.number("B")
                if child == 0: return result
                if child > 12: raise SchematicError("unknown NBT tag")
                name = self.string()
                if name in result: raise SchematicError("duplicate NBT compound key")
                self.charge(1)
                result[name] = self.payload(child, depth + 1)
        if tag in (11, 12):
            size = self.number("i"); width, code = ((4, "i") if tag == 11 else (8, "q"))
            if size < 0 or size > MAX_VOLUME or size * width > MAX_DECOMPRESSED: raise SchematicError("invalid NBT array length")
            self.charge(size)
            return list(struct.unpack(">" + code * size, self.take(size * width))) if size else []
        raise SchematicError("unknown NBT tag")

    def root(self) -> dict[str, Any]:
        tag = self.number("B")
        if tag != 10: raise SchematicError("NBT root must be a compound")
        self.string()
        result = self.payload(tag)
        if self.at != len(self.data): raise SchematicError("trailing NBT data")
        return result


def _read_bounded(path: Path) -> bytes:
    size = path.stat().st_size
    if size > MAX_COMPRESSED: raise SchematicError("schematic file exceeds compressed-size limit")
    raw = path.read_bytes()
    if len(raw) > MAX_COMPRESSED: raise SchematicError("schematic file exceeds compressed-size limit")
    if raw[:2] == b"\x1f\x8b":
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream: data = stream.read(MAX_DECOMPRESSED + 1)
        except (OSError, EOFError) as exc: raise SchematicError("invalid gzip schematic") from exc
    else: data = raw
    if len(data) > MAX_DECOMPRESSED: raise SchematicError("schematic exceeds decompressed-size limit")
    return data


def _integer(value: Any, name: str, low: int, high: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise SchematicError(f"{name} must be an integer in {low}..{high}")
    return value


def _vec3(value: Any, name: str) -> list[int]:
    if not isinstance(value, list) or len(value) != 3: raise SchematicError(f"{name} must be [x,y,z]")
    return [_integer(value[0], f"{name}[0]", -30_000_000, 30_000_000),
            _integer(value[1], f"{name}[1]", -255, 255),
            _integer(value[2], f"{name}[2]", -30_000_000, 30_000_000)]


def _block_name(value: Any, name="id") -> str:
    if (not isinstance(value, str) or len(value) > 256 or value.count(":") != 1
            or any(c.isspace() for c in value) or any(not part for part in value.split(":"))):
        raise SchematicError(f"{name} must be a namespaced registry ID")
    return value


def _block_selector(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - {"id", "meta", "ore", "item"}:
        raise SchematicError("replace selector has unknown fields")
    if not any(key in value for key in ("id", "ore", "item")): raise SchematicError("replace selector needs id, ore, or item")
    result = {}
    if "id" in value: result["id"] = _block_name(value["id"], "replace block id")
    if "meta" in value: result["meta"] = _integer(value["meta"], "replace meta", 0, 15)
    if "ore" in value:
        if not isinstance(value["ore"], str) or not value["ore"] or len(value["ore"]) > 256: raise SchematicError("replace ore must be bounded")
        result["ore"] = value["ore"]
    if "item" in value: result["item"] = _item_selector(value["item"], "replace item selector")
    return result


def _boolean(value: Any, name: str) -> bool:
    if not isinstance(value, bool): raise SchematicError(f"{name} must be boolean")
    return value


def _item(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - {"id", "meta", "nbt"}: raise SchematicError("cell item has unknown fields")
    result = {"id": _block_name(value.get("id"), "item id"), "meta": _integer(value.get("meta", 0), "item meta", 0, 32767)}
    if "nbt" in value:
        nbt=value["nbt"]
        if not isinstance(nbt,str) or len(nbt)>4096: raise SchematicError("item nbt must be an exact SNBT string of at most 4096 characters")
        result["nbt"] = nbt
    return result


def _item_selector(value: Any, name="item selector") -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - {"id", "meta", "nbt", "ore"}:
        raise SchematicError(f"{name} has unknown fields")
    if "id" not in value and "ore" not in value: raise SchematicError(f"{name} needs id or ore")
    result = {}
    if "meta" in value: result["meta"] = _integer(value["meta"], f"{name} meta", 0, 32767)
    if "id" in value: result["id"] = _block_name(value["id"], f"{name} id")
    if "ore" in value:
        if not isinstance(value["ore"], str) or not value["ore"] or len(value["ore"]) > 256: raise SchematicError(f"{name} ore must be bounded")
        result["ore"] = value["ore"]
    if "nbt" in value:
        if not isinstance(value["nbt"], str) or len(value["nbt"]) > 4096: raise SchematicError(f"{name} nbt must be bounded SNBT")
        result["nbt"] = value["nbt"]
    return result


def _verify(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != {"pickedItem"}:
        raise SchematicError("verify must contain exactly pickedItem")
    return {"pickedItem": _item_selector(value["pickedItem"], "verify pickedItem selector")}


def _cell_spec(value: Any, name="cell specification") -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - {"id", "meta", "item", "placement", "verify"}:
        raise SchematicError(f"{name} has unknown fields")
    result = {"id": _block_name(value.get("id"), f"{name} id"),
              "meta": _integer(value.get("meta", 0), f"{name} meta", 0, 15)}
    if "item" in value: result["item"] = _item(value["item"])
    if "placement" in value: result["placement"] = _placement(value["placement"])
    if "verify" in value: result["verify"] = _verify(value["verify"])
    return result


def _placement(value: Any) -> dict[str, Any]:
    allowed = {"face", "hit", "yaw", "pitch", "verifyAfterPlacement"}
    if not isinstance(value, dict) or set(value) - allowed: raise SchematicError("cell placement has unknown fields")
    result = {}
    if "face" in value:
        face=value["face"]
        names={"down":0,"up":1,"north":2,"south":3,"west":4,"east":5}
        if isinstance(face,str):
            if face not in names: raise SchematicError("placement face is invalid")
            face=names[face]
        result["face"] = _integer(face,"placement face",0,5)
    if "hit" in value:
        hit = value["hit"]
        if not isinstance(hit, list) or len(hit) != 3 or any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) or x < -16 or x > 16 for x in hit):
            raise SchematicError("placement hit must contain three finite values in -16..16")
        result["hit"] = hit
    for key in ("yaw", "pitch"):
        if key in value:
            number = value[key]
            low,high=(-360000,360000) if key=="yaw" else (-90,90)
            if isinstance(number, bool) or not isinstance(number, (int, float)) or not math.isfinite(number) or not low<=number<=high: raise SchematicError(f"placement {key} must be finite and in {low}..{high}")
            result[key] = number
    if "verifyAfterPlacement" in value: result["verifyAfterPlacement"] = _boolean(value["verifyAfterPlacement"], "placement verifyAfterPlacement")
    return result


def _settings(value: Any) -> dict[str, Any]:
    if value is None: return {}
    if not isinstance(value, dict): raise SchematicError("settings must be an object")
    bools = {"buildInLayers", "layerOrder", "skipFailedLayers", "buildRepeatSneaky", "mapArtMode",
             "buildIgnoreExisting", "okIfWater", "schematicOrientationX", "schematicOrientationY",
             "schematicOrientationZ", "breakFromAbove", "goalBreakFromAbove", "distanceTrim",
             "allowInventory", "restricted", "repairPlaced"}
    ints = {"layerHeight": (1, 256), "startAtLayer": (0, 255), "incorrectSize": (1, 16384),
            "builderTickScanRadius": (1, 32)}
    id_lists = {"buildIgnoreBlocks", "buildSkipBlocks", "okIfAir"}
    allowed = bools | set(ints) | id_lists | {"buildRepeat", "buildRepeatCount", "breakCorrectBlockPenaltyMultiplier",
        "buildValidSubstitutes", "buildSubstitutes", "metadataMasks", "acceptableThrowawayItems"}
    unknown = set(value) - allowed
    if unknown: raise SchematicError("settings has unsupported fields: " + ", ".join(sorted(unknown)))
    result = {}
    for key in bools:
        if key in value: result[key] = _boolean(value[key], f"settings.{key}")
    for key, bounds in ints.items():
        if key in value: result[key] = _integer(value[key], f"settings.{key}", *bounds)
    for key in id_lists:
        if key in value:
            if not isinstance(value[key], list): raise SchematicError(f"settings.{key} must be a list")
            result[key] = [_block_name(item, f"settings.{key} entry") for item in value[key]]
    if "buildRepeat" in value: result["buildRepeat"] = _vec3(value["buildRepeat"], "settings.buildRepeat")
    if "buildRepeatCount" in value:
        count = _integer(value["buildRepeatCount"], "settings.buildRepeatCount", -1, 100000)
        if count == 0: raise SchematicError("settings.buildRepeatCount must be -1 or 1..100000")
        result["buildRepeatCount"] = count
    if "breakCorrectBlockPenaltyMultiplier" in value:
        number = value["breakCorrectBlockPenaltyMultiplier"]
        if isinstance(number, bool) or not isinstance(number, (int, float)) or not math.isfinite(number) or not 1 <= number <= 1e6:
            raise SchematicError("settings.breakCorrectBlockPenaltyMultiplier must be finite and in 1..1000000")
        result["breakCorrectBlockPenaltyMultiplier"] = number
    for key in ("buildValidSubstitutes", "buildSubstitutes"):
        if key not in value: continue
        mapping = value[key]
        if not isinstance(mapping, dict): raise SchematicError(f"settings.{key} must be an object")
        clean = {}
        for block, choices in mapping.items():
            block = _block_name(block, f"settings.{key} key")
            if not isinstance(choices, list): raise SchematicError(f"settings.{key} values must be lists")
            if key == "buildSubstitutes" and not choices: raise SchematicError("settings.buildSubstitutes values must be non-empty lists")
            if key == "buildValidSubstitutes": clean[block] = [_block_name(x, f"settings.{key} entry") for x in choices]
            else: clean[block] = [_cell_spec(x, f"settings.{key} entry") if isinstance(x, dict)
                                  else _block_name(x) for x in choices]
        result[key] = clean
    if "metadataMasks" in value:
        masks = value["metadataMasks"]
        if not isinstance(masks, dict): raise SchematicError("settings.metadataMasks must be an object")
        result["metadataMasks"] = {_block_name(k, "metadataMasks key"): _integer(v, "metadata mask", 0, 15) for k, v in masks.items()}
    if "acceptableThrowawayItems" in value:
        values = value["acceptableThrowawayItems"]
        if not isinstance(values, list): raise SchematicError("settings.acceptableThrowawayItems must be a list")
        clean = []
        for item in values:
            if not isinstance(item, dict) or set(item) - {"id", "meta", "nbt", "ore"}: raise SchematicError("throwaway item has unknown fields")
            clean.append(_item_selector(item, "throwaway item selector"))
        result["acceptableThrowawayItems"] = clean
    return result


def _canonical(document: Any, origin: list[int] | None, include_air: bool, requested_mode: str | None = None) -> dict[str, Any]:
    if not isinstance(document, dict): raise SchematicError("canonical schematic must be an object")
    allowed_document = {"origin", "size", "cells", "replaceExisting", "timeoutTicks", "overrideProtection",
                        "allowBreak", "allowPlace", "mode", "settings"}
    if set(document) - allowed_document:
        raise SchematicError("canonical schematic has unsupported fields: " + ", ".join(sorted(set(document) - allowed_document)))
    source_cells = document.get("cells")
    if not isinstance(source_cells, list): raise SchematicError("canonical cells must be a list")
    mode = requested_mode if requested_mode is not None else document.get("mode", "blueprint")
    if mode not in ("blueprint", "builder"): raise SchematicError("mode must be blueprint or builder")
    cell_limit = MAX_BUILDER_CELLS if mode == "builder" else MAX_CELLS
    if len(source_cells) > cell_limit: raise SchematicError("schematic exceeds explicit-cell limit")
    build_origin=_vec3(origin if origin is not None else document.get("origin", [0, 0, 0]), "origin")
    replace_existing=_boolean(document.get("replaceExisting", False), "replaceExisting")
    timeout_ticks=_integer(document.get("timeoutTicks", 12000), "timeoutTicks", 1, 72_000)
    override_protection=_boolean(document.get("overrideProtection", False), "overrideProtection")
    allow_break = _boolean(document["allowBreak"], "allowBreak") if "allowBreak" in document else None
    allow_place = _boolean(document["allowPlace"], "allowPlace") if "allowPlace" in document else None
    settings = _settings(document.get("settings"))
    if settings and mode != "builder": raise SchematicError("construction settings require mode builder")
    cells, seen = [], set()
    for index, raw in enumerate(source_cells):
        if not isinstance(raw, dict): raise SchematicError(f"cell {index} must be an object")
        allowed_cell = {"pos", "id", "meta", "item", "placement", "verify", "replace", "clear"}
        if set(raw) - allowed_cell:
            raise SchematicError(f"cell {index} has unsupported fields: " + ", ".join(sorted(set(raw) - allowed_cell)))
        pos = _vec3(raw.get("pos"), f"cell {index} pos"); key = tuple(pos)
        if key in seen: raise SchematicError(f"duplicate cell position {pos}")
        seen.add(key)
        clear = _boolean(raw.get("clear", False), "cell clear")
        block = _block_name(raw.get("id", "minecraft:air" if clear else None))
        if block == "minecraft:air" and not include_air and not clear: continue
        cell = {"pos": pos, "id": block, "meta": _integer(raw.get("meta", 0), "cell meta", 0, 15)}
        if clear: cell["clear"] = True
        if "item" in raw: cell["item"] = _item(raw["item"])
        if "placement" in raw: cell["placement"] = _placement(raw["placement"])
        if "verify" in raw: cell["verify"] = _verify(raw["verify"])
        if "replace" in raw: cell["replace"] = _block_selector(raw["replace"])
        cells.append(cell)
    if not cells: raise SchematicError("schematic must emit 1..16384 explicit cells")
    if len(cells) > cell_limit: raise SchematicError("schematic exceeds explicit-cell limit")
    _validate_translated(cells,build_origin)
    build = {"origin": build_origin,
             "cells": cells,
             "replaceExisting": replace_existing,
             "timeoutTicks": timeout_ticks,
             "overrideProtection": override_protection}
    if "size" in document:
        size = document["size"]
        if not isinstance(size, list) or len(size) != 3: raise SchematicError("size must be [width,height,depth]")
        build["size"] = [_integer(size[0], "size[0]", 1, 30_000_000), _integer(size[1], "size[1]", 1, 256),
                         _integer(size[2], "size[2]", 1, 30_000_000)]
    if mode != "blueprint": build["mode"] = mode
    if settings: build["settings"] = settings
    if allow_break is not None: build["allowBreak"] = allow_break
    if allow_place is not None: build["allowPlace"] = allow_place
    return {"build": build, "requirements": []}


def _json_value(value):
    value = _json_compatible(value, [MAX_EXTENSION_JSON])
    try:
        encoded = json.dumps(value, allow_nan=False, separators=(",", ":"))
    except (TypeError, ValueError) as exc: raise SchematicError("cell extension must be bounded JSON") from exc
    if len(encoded.encode("utf-8")) > MAX_EXTENSION_JSON: raise SchematicError("cell extension exceeds 64 KiB")
    return value


def _json_compatible(value, budget: list[int]):
    """Convert compact NBT byte arrays only at the bounded extension boundary."""
    def charge(size: int) -> None:
        if size < 0 or size > budget[0]: raise SchematicError("cell extension exceeds 64 KiB")
        budget[0]-=size
    if isinstance(value, bytes):
        # Exact compact-JSON upper accounting before allocating Python ints.
        charge(2 + max(0,len(value)-1) + sum(1 if x<10 else 2 if x<100 else 3 for x in value))
        return list(value)
    if isinstance(value, list):
        charge(2+max(0,len(value)-1))
        return [_json_compatible(item,budget) for item in value]
    if isinstance(value, dict):
        charge(2+max(0,len(value)-1))
        result={}
        for key,item in value.items():
            if not isinstance(key,str): raise SchematicError("cell extension object keys must be strings")
            charge(len(json.dumps(key).encode("utf-8"))+1)
            result[key]=_json_compatible(item,budget)
        return result
    try: encoded=json.dumps(value,allow_nan=False,separators=(",", ":")).encode("utf-8")
    except (TypeError,ValueError) as exc: raise SchematicError("cell extension must be bounded JSON") from exc
    charge(len(encoded))
    return value


def _validate_translated(cells: list[dict[str,Any]], origin: list[int]) -> None:
    for cell in cells:
        local=_vec3(cell["pos"],"cell pos")
        x=local[0]+origin[0];y=local[1]+origin[1];z=local[2]+origin[2]
        if abs(x)>30_000_000 or not 1<=y<=254 or abs(z)>30_000_000:
            raise SchematicError("translated cell outside native world bounds")


def _state_key(name: str, properties: Any = None) -> str:
    name = _block_name(name, "palette block name")
    if properties in (None, {}): return name
    if not isinstance(properties, dict): raise SchematicError("palette Properties must be a compound")
    pairs = []
    for key in sorted(properties):
        value = properties[key]
        if not isinstance(key, str) or not key or len(key) > 128 or not isinstance(value, str) or not value or len(value) > 128:
            raise SchematicError("palette property names and values must be non-empty bounded strings")
        if any(c in key + value for c in "[]=,"):
            raise SchematicError("palette property contains reserved syntax")
        pairs.append(f"{key}={value}")
    return f"{name}[{','.join(pairs)}]"


def _clean_state_palette(palette: Any) -> dict[str, dict[str, Any]]:
    if palette is None: return {}
    if not isinstance(palette, dict): raise SchematicError("state palette mapping must be an object")
    result = {}
    for state, value in palette.items():
        if not isinstance(state, str) or len(state) > 4096: raise SchematicError("state palette keys must be bounded strings")
        result[state] = _cell_spec(value, f"state palette mapping for {state!r}")
    return result


def _mapped_state(state: str, palette: dict[str, dict[str, Any]]) -> dict[str, Any]:
    if state in palette: return dict(palette[state])
    if "[" not in state: return {"id": _block_name(state), "meta": 0}
    raise SchematicError(f"palette state {state!r} requires an explicit state mapping")


def _finish_cells(cells_by_pos, origin, include_air, source_size, requirements, mode):
    cell_limit = MAX_BUILDER_CELLS if mode == "builder" else MAX_CELLS
    cells = []
    for cell in cells_by_pos.values():
        if cell["id"] == "minecraft:air":
            if not include_air: continue
            cell = {**cell, "clear": True}
        cells.append(cell)
        if len(cells) > cell_limit: raise SchematicError("schematic exceeds explicit-cell limit")
    if not cells: raise SchematicError("schematic must emit 1..16384 explicit cells")
    _validate_translated(cells, origin)
    build = {"origin": origin, "size": source_size, "cells": cells, "replaceExisting": False,
             "timeoutTicks": 12000, "overrideProtection": False}
    if mode == "builder": build["mode"] = mode
    return {"build": build,
            "requirements": requirements, "sourceSize": source_size}


def _unsupported_nbt(kind, value):
    return {"kind": kind, "nbt": _json_value(value), "supported": False,
            "reason": ("requires a mod-specific placement/configuration adapter" if kind == "tile_entity_nbt"
                       else "entity creation requires a separate normal-gameplay adapter")}


def _sponge(root, origin, state_palette, include_air, mode):
    version = _integer(root.get("Version"), "Version", 1, 2)
    width = _integer(root.get("Width"), "Width", 1, 32767)
    height = _integer(root.get("Height"), "Height", 1, 256)
    length = _integer(root.get("Length"), "Length", 1, 32767)
    volume = width * height * length
    if volume > MAX_VOLUME: raise SchematicError("schematic volume exceeds limit")
    raw_palette = root.get("Palette")
    if not isinstance(raw_palette, dict) or not raw_palette: raise SchematicError("Palette must be a non-empty compound")
    decoded = {}
    for state, raw_index in raw_palette.items():
        index = _integer(raw_index, f"palette index for {state}", 0, 2_147_483_647)
        if index in decoded: raise SchematicError(f"duplicate palette index {index}")
        if not isinstance(state, str) or not state: raise SchematicError("invalid serialized palette state")
        if "[" in state:
            if state.count("[") != 1 or not state.endswith("]"): raise SchematicError("invalid serialized palette state")
            block, properties = state[:-1].split("[", 1); _block_name(block, "palette block name")
            seen = set()
            for pair in properties.split(","):
                if pair.count("=") != 1: raise SchematicError("invalid serialized palette state")
                key, value = pair.split("=")
                if not key or not value or key in seen or any(c in key + value for c in "[]=,"):
                    raise SchematicError("invalid serialized palette state")
                seen.add(key)
        else: _block_name(state, "palette block name")
        # Sponge mappings intentionally use the exact serialized key.
        if "[" in state and state not in state_palette:
            raise SchematicError(f"palette state {state!r} requires an explicit state mapping")
        decoded[index] = _mapped_state(state, state_palette)
    raw = root.get("BlockData")
    if not isinstance(raw, (bytes, list)): raise SchematicError("BlockData must be a byte array")
    cells, offset = {}, 0
    for index in range(volume):
        value = 0
        for byte_index in range(5):
            if offset >= len(raw): raise SchematicError("truncated BlockData varint")
            byte = _integer(raw[offset], "BlockData byte", 0, 255); offset += 1
            value |= (byte & 0x7f) << (7 * byte_index)
            if not byte & 0x80: break
        else: raise SchematicError("BlockData varint exceeds five bytes")
        if value > 0x7fffffff: raise SchematicError("BlockData varint exceeds signed integer range")
        if value not in decoded: raise SchematicError(f"invalid palette index {value} at block {index}")
        y, rem = divmod(index, width * length); z, x = divmod(rem, width)
        cells[(x, y, z)] = {"pos": [x, y, z], **decoded[value]}
    if offset != len(raw): raise SchematicError("trailing bytes in BlockData")
    requirements = []
    for kind, key in (("tile_entity_nbt", "BlockEntities" if version == 2 else "TileEntities"), ("entity_nbt", "Entities")):
        values = root.get(key, [])
        if not isinstance(values, list): raise SchematicError(f"{key} must be a list")
        for value in values:
            if not isinstance(value, dict): raise SchematicError(f"{key} entry must be a compound")
            requirements.append(_unsupported_nbt(kind, value))
    return _finish_cells(cells, origin, include_air, [width, height, length], requirements, mode)


def _litematica(root, origin, state_palette, include_air, mode):
    if _integer(root.get("Version"), "Version", 0, 2_147_483_647) != 4:
        raise SchematicError("only Litematica version 4 (1.12) is supported")
    metadata, regions = root.get("Metadata"), root.get("Regions")
    if not isinstance(metadata, dict) or not isinstance(metadata.get("EnclosingSize"), dict): raise SchematicError("Metadata.EnclosingSize must be a compound")
    if not isinstance(regions, dict) or not regions: raise SchematicError("Regions must be a non-empty compound")
    enclosing = [_integer(metadata["EnclosingSize"].get(k), f"EnclosingSize.{k}", -30_000_000, 30_000_000) for k in ("x", "y", "z")]
    source_size = [abs(x) for x in enclosing]
    if 0 in source_size or source_size[1] > 256 or source_size[0] * source_size[1] * source_size[2] > MAX_VOLUME:
        raise SchematicError("invalid enclosing schematic volume")
    specs, minima, total_volume = [], [2_147_483_647] * 3, 0
    for name, region in regions.items():
        if not isinstance(name, str) or not isinstance(region, dict): raise SchematicError("region must be a named compound")
        position, size = region.get("Position"), region.get("Size")
        if not isinstance(position, dict) or not isinstance(size, dict): raise SchematicError(f"region {name} position and size must be compounds")
        pos = [_integer(position.get(k), f"region {name} Position.{k}", -30_000_000, 30_000_000) for k in ("x", "y", "z")]
        signed_size = [_integer(size.get(k), f"region {name} Size.{k}", -32767, 32767) for k in ("x", "y", "z")]
        dims = [abs(x) for x in signed_size]
        if 0 in dims or dims[1] > 256: raise SchematicError(f"region {name} has invalid size")
        volume = dims[0] * dims[1] * dims[2]; total_volume += volume
        if volume > MAX_VOLUME or total_volume > MAX_VOLUME: raise SchematicError("Litematica region volume exceeds limit")
        minimum = [min(pos[i], pos[i] + signed_size[i] + (1 if signed_size[i] < 0 else -1)) for i in range(3)]
        minima = [min(minima[i], minimum[i]) for i in range(3)]
        specs.append((name, region, dims, minimum, volume))
    for name, _region, dims, minimum, _volume in specs:
        local = [minimum[i] - minima[i] for i in range(3)]
        if any(local[i] < 0 or local[i] + dims[i] > source_size[i] for i in range(3)):
            raise SchematicError(f"region {name} lies outside Metadata.EnclosingSize")
    cells, requirements = {}, []
    for name, region, dims, minimum, volume in specs:
        raw_palette = region.get("BlockStatePalette")
        if not isinstance(raw_palette, list) or not raw_palette: raise SchematicError(f"region {name} palette must be a non-empty list")
        decoded = []
        for entry in raw_palette:
            if not isinstance(entry, dict) or set(entry) - {"Name", "Properties"}: raise SchematicError(f"region {name} palette entry has unknown fields")
            decoded.append(_mapped_state(_state_key(entry.get("Name"), entry.get("Properties")), state_palette))
        bits = max(2, (len(decoded) - 1).bit_length())
        states = region.get("BlockStates")
        if not isinstance(states, list): raise SchematicError(f"region {name} BlockStates must be a long array")
        needed = (volume * bits + 63) // 64
        if len(states) != needed: raise SchematicError(f"region {name} BlockStates length is invalid")
        words = [(_integer(word, "BlockStates word", -(1 << 63), (1 << 63) - 1) & ((1 << 64) - 1)) for word in states]
        mask = (1 << bits) - 1
        for index in range(volume):
            bit = index * bits; word_index, shift = divmod(bit, 64)
            value = words[word_index] >> shift
            if shift + bits > 64: value |= words[word_index + 1] << (64 - shift)
            value &= mask
            if value >= len(decoded): raise SchematicError(f"region {name} has invalid palette index {value} at block {index}")
            y, rem = divmod(index, dims[0] * dims[2]); z, x = divmod(rem, dims[0])
            local = [minimum[0] + x - minima[0], minimum[1] + y - minima[1], minimum[2] + z - minima[2]]
            cells[tuple(local)] = {"pos": local, **decoded[value]}
        for kind, key in (("tile_entity_nbt", "TileEntities"), ("entity_nbt", "Entities")):
            values = region.get(key, [])
            if not isinstance(values, list): raise SchematicError(f"region {name} {key} must be a list")
            for value in values:
                if not isinstance(value, dict): raise SchematicError(f"region {name} {key} entry must be a compound")
                requirements.append(_unsupported_nbt(kind, value))
    return _finish_cells(cells, origin, include_air, source_size, requirements, mode)


def _legacy(root: dict[str, Any], origin: list[int], registry: dict[int, str], include_air: bool, mode="blueprint") -> dict[str, Any]:
    cell_limit = MAX_BUILDER_CELLS if mode == "builder" else MAX_CELLS
    if root.get("Materials") != "Alpha": raise SchematicError("legacy Materials must be Alpha")
    width = _integer(root.get("Width"), "Width", 1, 32767)
    height = _integer(root.get("Height"), "Height", 1, 32767)
    length = _integer(root.get("Length"), "Length", 1, 32767)
    volume = width * height * length
    if volume > MAX_VOLUME: raise SchematicError("schematic volume exceeds limit")
    blocks, data = root.get("Blocks"), root.get("Data")
    if not isinstance(blocks, (bytes, list)) or len(blocks) != volume or not isinstance(data, (bytes, list)) or len(data) != volume:
        raise SchematicError("Blocks and Data lengths must equal schematic volume")
    add = root.get("AddBlocks")
    if add is not None and (not isinstance(add, (bytes, list)) or len(add) != (volume + 1) // 2):
        raise SchematicError("AddBlocks length is invalid")
    palette = dict(registry)
    mappings = root.get("SchematicaMapping", {})
    if mappings is not None and not isinstance(mappings, dict): raise SchematicError("SchematicaMapping must be a compound")
    for name, numeric in (mappings or {}).items():
        block_id = _integer(numeric, f"mapping {name}", 0, 4095); block_name = _block_name(name, "mapping name")
        if block_id in palette and palette[block_id] != block_name: raise SchematicError(f"conflicting mapping for numeric ID {block_id}")
        palette[block_id] = block_name
    palette.setdefault(0, "minecraft:air")
    cells = []
    for index, low in enumerate(blocks):
        numeric = _integer(low, "Blocks entry", 0, 255)
        if add is not None:
            packed = _integer(add[index // 2], "AddBlocks entry", 0, 255)
            # Match Baritone's bundled MCEditSchematic decoder.
            numeric |= ((packed >> 4) if index % 2 == 0 else (packed & 15)) << 8
        if numeric == 0 and not include_air: continue
        if numeric not in palette: raise SchematicError(f"numeric block ID {numeric} has no explicit registry mapping")
        y, rem = divmod(index, width * length); z, x = divmod(rem, width)
        cells.append({"pos": [x, y, z], "id": palette[numeric], "meta": _integer(data[index], "Data entry", 0, 15),
                      **({"clear": True} if numeric == 0 else {})})
        if len(cells) > cell_limit: raise SchematicError("schematic exceeds explicit-cell limit")
    requirements = []
    tiles = root.get("TileEntities", [])
    if not isinstance(tiles, list): raise SchematicError("TileEntities must be a list")
    for tile in tiles:
        if not isinstance(tile, dict): raise SchematicError("tile entity must be a compound")
        pos = [_integer(tile.get(k), f"tile entity {k}", -30_000_000, 30_000_000) for k in ("x", "y", "z")]
        if not (0 <= pos[0] < width and 0 <= pos[1] < height and 0 <= pos[2] < length):
            raise SchematicError(f"tile entity position is outside schematic bounds: {pos}")
        requirements.append({"kind": "tile_entity_nbt", "pos": pos, "nbt": _json_value(tile),
                             "supported": False, "reason": "requires a mod-specific placement/configuration adapter"})
    entities = root.get("Entities", [])
    if not isinstance(entities, list): raise SchematicError("Entities must be a list")
    for entity in entities:
        if not isinstance(entity, dict): raise SchematicError("entity must be a compound")
        requirements.append({"kind": "entity_nbt", "nbt": _json_value(entity), "supported": False,
                             "reason": "entity creation requires a separate normal-gameplay adapter"})
    if not cells: raise SchematicError("schematic must emit 1..16384 explicit cells")
    build_origin=_vec3(origin, "origin");_validate_translated(cells,build_origin)
    build = {"origin": build_origin, "size": [width, height, length], "cells": cells, "replaceExisting": False,
             "timeoutTicks": 12000, "overrideProtection": False}
    if mode == "builder": build["mode"] = mode
    return {"build": build, "requirements": requirements,
            "sourceSize": [width, height, length]}


def resolve_schematic_path(path: str | Path, schematic_directory: str | Path | None = None) -> Path:
    candidate = Path(path)
    if schematic_directory is not None:
        if candidate.is_absolute() or candidate.parent != Path("."):
            raise SchematicError("schematic directory lookup requires a bare filename")
        candidate = Path(schematic_directory) / candidate
    attempts = [candidate] if candidate.suffix else [candidate, *(candidate.with_suffix(ext) for ext in SCHEMATIC_EXTENSIONS)]
    matches = [item for item in attempts if item.is_file()]
    if not matches: raise SchematicError(f"schematic file not found: {candidate}")
    if len(matches) > 1: raise SchematicError(f"schematic filename is ambiguous: {candidate.name}")
    return matches[0]


def import_schematic(path: str | Path, *, origin=None, registry: dict[int, str] | None = None,
                     palette: dict[str, dict[str, Any]] | None = None, include_air: bool = False,
                     rotation: int = 0, mirror: str | None = None,
                     schematic_directory: str | Path | None = None, mode: str | None = None) -> dict[str, Any]:
    """Import one file without executing a build or mutating Minecraft."""
    path = resolve_schematic_path(path, schematic_directory)
    if rotation != 0 or mirror is not None:
        raise SchematicError("rotation/mirroring requires an explicit block-state remapper and is not supported")
    if not isinstance(include_air, bool): raise SchematicError("include_air must be boolean")
    if mode not in (None, "blueprint", "builder"): raise SchematicError("mode must be blueprint or builder")
    raw = _read_bounded(path)
    if path.suffix.lower() == ".json" or raw.lstrip().startswith((b"{", b"[")):
        try: document = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc: raise SchematicError("invalid canonical JSON") from exc
        return _canonical(document, _vec3(list(origin), "origin") if origin is not None else None, include_air, mode)
    parsed = _NBT(raw).root()
    state_palette = _clean_state_palette(palette)
    extension = path.suffix.lower()
    build_origin = _vec3(list(origin), "origin") if origin is not None else [0, 0, 0]
    import_mode = mode or "blueprint"
    if extension == ".schem": return _sponge(parsed, build_origin, state_palette, include_air, import_mode)
    if extension == ".litematic": return _litematica(parsed, build_origin, state_palette, include_air, import_mode)
    if extension not in (".schematic", ""):
        raise SchematicError(f"unsupported schematic extension: {extension}")
    clean_registry = {}
    for key, value in (registry or {}).items():
        numeric = _integer(key, "registry numeric ID", 0, 4095)
        clean_registry[numeric] = _block_name(value, "registry name")
    return _legacy(parsed, build_origin, clean_registry, include_air, import_mode)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path); parser.add_argument("--origin", nargs=3, type=int)
    parser.add_argument("--registry", type=Path, help="JSON object mapping numeric IDs to registry names")
    parser.add_argument("--palette", type=Path, help="JSON object mapping serialized block states to canonical cells")
    parser.add_argument("--schematic-directory", type=Path)
    parser.add_argument("--mode", choices=("blueprint", "builder"))
    parser.add_argument("--include-air", action="store_true")
    args = parser.parse_args(argv)
    try:
        mapping = json.loads(args.registry.read_text(encoding="utf-8")) if args.registry else {}
        mapping = {int(k): v for k, v in mapping.items()}
        palette = json.loads(args.palette.read_text(encoding="utf-8")) if args.palette else {}
        print(json.dumps(import_schematic(args.path, origin=args.origin, registry=mapping, palette=palette,
                                          include_air=args.include_air, schematic_directory=args.schematic_directory,
                                          mode=args.mode), indent=2))
        return 0
    except (OSError, ValueError, SchematicError) as exc:
        print(f"schematic error: {exc}", file=sys.stderr); return 2


if __name__ == "__main__": raise SystemExit(main())
