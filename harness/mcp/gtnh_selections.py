# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Pure multi-region selection and clipboard construction helpers.

Selections and clipboards are scoped by the caller's stable world identity.
Copy accepts only a complete, explicit observation snapshot; it never queries or
mutates Minecraft and never treats an omitted coordinate as air.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable

from gtnh_schematics import SchematicError, _item, _settings, _verify

MAX_CELLS = 16_384
MAX_BUILDER_CELLS = 1_048_576
_DIRECTIONS = {"down": (1, -1), "up": (1, 1), "north": (2, -1),
               "south": (2, 1), "west": (0, -1), "east": (0, 1)}


class SelectionError(ValueError): pass


def _world(value: Any) -> str:
    if not isinstance(value, str) or not value or len(value) > 1024:
        raise SelectionError("world identity must be a non-empty bounded string")
    return value


def _pos(value: Any, name="position") -> tuple[int, int, int]:
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise SelectionError(f"{name} must be [x,y,z]")
    out = []
    for index, item in enumerate(value):
        if isinstance(item, bool) or not isinstance(item, int): raise SelectionError(f"{name}[{index}] must be an integer")
        low, high = ((-255, 255) if index == 1 else (-30_000_000, 30_000_000))
        if not low <= item <= high: raise SelectionError(f"{name}[{index}] is outside {low}..{high}")
        out.append(item)
    return tuple(out)


def _block_id(value: Any) -> str:
    if not isinstance(value, str) or not value or ":" not in value or len(value) > 256:
        raise SelectionError("observed block id must be a namespaced registry ID")
    return value


@dataclass(frozen=True)
class Selection:
    pos1: tuple[int, int, int]
    pos2: tuple[int, int, int]

    @property
    def minimum(self): return tuple(min(a, b) for a, b in zip(self.pos1, self.pos2))
    @property
    def maximum(self): return tuple(max(a, b) for a, b in zip(self.pos1, self.pos2))
    @property
    def size(self): return tuple(b - a + 1 for a, b in zip(self.minimum, self.maximum))

    def positions(self):
        low, high = self.minimum, self.maximum
        for y in range(low[1], high[1] + 1):
            for z in range(low[2], high[2] + 1):
                for x in range(low[0], high[0] + 1): yield (x, y, z)

    def transform(self, action: str, direction: str, blocks: int) -> "Selection":
        if direction not in _DIRECTIONS: raise SelectionError("direction must be down/up/north/south/west/east")
        if isinstance(blocks, bool) or not isinstance(blocks, int) or blocks < 0:
            raise SelectionError("blocks must be a non-negative integer")
        axis, sign = _DIRECTIONS[direction]
        a, b = list(self.pos1), list(self.pos2)
        if action == "shift": a[axis] += sign * blocks; b[axis] += sign * blocks
        elif action in ("expand", "contract"):
            negative = sign < 0
            pos2_is_further = ((b[axis] > a[axis]) ^ negative)
            change_pos2 = pos2_is_further if action == "expand" else not pos2_is_further
            target = b if change_pos2 else a
            target[axis] += sign * blocks
        else: raise SelectionError("action must be expand, contract, or shift")
        return Selection(_pos(a), _pos(b))


@dataclass
class _Clipboard:
    offset: tuple[int, int, int]
    size: tuple[int, int, int]
    cells: list[dict[str, Any]]
    regions: list[dict[str, list[int]]]
    requirements: list[dict[str, Any]]


@dataclass
class _State:
    pending: tuple[int, int, int] | None = None
    selections: list[Selection] = field(default_factory=list)
    clipboard: _Clipboard | None = None
    history: list[tuple[Any, Any, Any]] = field(default_factory=list)


class SelectionStore:
    """In-memory selection state keyed by stable world/dimension identity."""

    def __init__(self): self._worlds: dict[str, _State] = {}

    def _state(self, world): return self._worlds.setdefault(_world(world), _State())

    @staticmethod
    def _remember(state: _State):
        state.history.append((state.pending, list(state.selections), state.clipboard))
        if len(state.history) > 256: del state.history[0]

    def view(self, world) -> dict[str, Any]:
        state = self._state(world)
        return {"pos1": list(state.pending) if state.pending else None,
                "selections": [{"pos1": list(x.pos1), "pos2": list(x.pos2),
                                "min": list(x.minimum), "max": list(x.maximum), "size": list(x.size)}
                               for x in state.selections], "hasClipboard": state.clipboard is not None}

    def pos1(self, world, position):
        state = self._state(world); self._remember(state); state.pending = _pos(position); return self.view(world)

    def pos2(self, world, position):
        state = self._state(world)
        if state.pending is None: raise SelectionError("set pos1 before pos2")
        self._remember(state); state.selections.append(Selection(state.pending, _pos(position))); state.pending = None
        return self.view(world)

    def add(self, world, pos1, pos2):
        state = self._state(world); self._remember(state); state.selections.append(Selection(_pos(pos1), _pos(pos2)))
        return self.view(world)

    def remove(self, world, index=-1):
        state = self._state(world)
        if not state.selections: raise SelectionError("no selections")
        if isinstance(index, bool) or not isinstance(index, int) or not -len(state.selections) <= index < len(state.selections):
            raise SelectionError("selection index is out of range")
        self._remember(state); state.selections.pop(index); return self.view(world)

    def clear(self, world):
        state = self._state(world); self._remember(state); state.pending = None; state.selections.clear(); return self.view(world)

    def undo(self, world):
        state = self._state(world)
        if not state.history: raise SelectionError("nothing to undo")
        state.pending, state.selections, state.clipboard = state.history.pop(); return self.view(world)

    def transform(self, world, action, target, direction, blocks):
        state = self._state(world)
        if not state.selections: raise SelectionError("no selections")
        aliases = {"a": "all", "n": "newest", "o": "oldest"}; target = aliases.get(target, target)
        if target not in ("all", "newest", "oldest"): raise SelectionError("target must be all, newest, or oldest")
        indexes = range(len(state.selections)) if target == "all" else [len(state.selections) - 1 if target == "newest" else 0]
        self._remember(state)
        for index in indexes: state.selections[index] = state.selections[index].transform(action, direction, blocks)
        return self.view(world)

    def selected_positions(self, world, limit: int | None = None) -> set[tuple[int, int, int]]:
        state = self._state(world)
        result = set()
        for selection in state.selections:
            for pos in selection.positions():
                result.add(pos)
                if limit is not None and len(result) > limit: raise SelectionError(f"selection union exceeds {limit} cells")
        return result

    def copy(self, world, anchor, observations: Iterable[dict[str, Any]], block_states_only: bool = False) -> dict[str, Any]:
        state = self._state(world)
        if not state.selections: raise SelectionError("no selections")
        if not isinstance(block_states_only, bool): raise SelectionError("block_states_only must be boolean")
        expected = self.selected_positions(world, MAX_BUILDER_CELLS)
        observed = {}
        for row in observations:
            if not isinstance(row, dict) or set(row) - {"pos", "loaded", "id", "meta", "tileClass", "pickedItem", "placementItem"}:
                raise SelectionError("observation has unknown fields")
            pos = _pos(row.get("pos"), "observation pos")
            if pos in observed: raise SelectionError(f"duplicate observation at {list(pos)}")
            if pos not in expected: raise SelectionError(f"observation outside selection union at {list(pos)}")
            if row.get("loaded") is not True: raise SelectionError(f"unloaded observation at {list(pos)}")
            picked = row.get("pickedItem")
            verify = None
            if "pickedItem" in row and not isinstance(picked, dict): raise SelectionError("observed pickedItem must be an object")
            if isinstance(picked, dict) and picked.get("empty") is not True:
                try: verify = _verify({"pickedItem": {key: picked[key] for key in ("id", "meta", "nbt", "ore") if key in picked}})
                except SchematicError as exc: raise SelectionError(str(exc)) from exc
            placement = row.get("placementItem")
            material = None
            if "placementItem" in row:
                if not isinstance(placement, dict): raise SelectionError("observed placementItem must be an object")
                try: material = _item({key: placement[key] for key in ("id", "meta", "nbt") if key in placement})
                except SchematicError as exc: raise SelectionError(str(exc)) from exc
            observed[pos] = {"id": _block_id(row.get("id")), "meta": row.get("meta"),
                             "tileClass": row.get("tileClass"), "verify": verify, "item": material}
            if isinstance(row.get("meta"), bool) or not isinstance(row.get("meta"), int) or not 0 <= row["meta"] <= 15:
                raise SelectionError("observed metadata must be in 0..15")
        missing = expected - observed.keys()
        if missing: raise SelectionError(f"snapshot is incomplete; missing {len(missing)} selected cells")
        minimum = tuple(min(pos[i] for pos in expected) for i in range(3))
        maximum = tuple(max(pos[i] for pos in expected) for i in range(3))
        anchor = _pos(anchor, "anchor")
        cells, requirements = [], []
        for pos in sorted(expected, key=lambda p: (p[1], p[2], p[0])):
            block = observed[pos]; local = [pos[i] - minimum[i] for i in range(3)]
            cell = {"pos": local, "id": block["id"], "meta": block["meta"]}
            if block["id"] == "minecraft:air": cell["clear"] = True
            if block["verify"] is not None: cell["verify"] = block["verify"]
            if block["item"] is not None: cell["item"] = block["item"]
            cells.append(cell)
            tile_class = block["tileClass"]
            if tile_class is not None and not block_states_only:
                if not isinstance(tile_class, str) or not tile_class: raise SelectionError("observed tileClass must be a non-empty string or null")
                requirements.append({"kind": "tile_entity_state", "pos": local, "tileClass": tile_class,
                                     "supported": False, "reason": "copy scan does not expose tile NBT; requires a mod-specific adapter"})
        regions = [{"min": [x.minimum[i] - minimum[i] for i in range(3)],
                    "max": [x.maximum[i] - minimum[i] for i in range(3)]} for x in state.selections]
        clipboard = _Clipboard(tuple(minimum[i] - anchor[i] for i in range(3)),
                               tuple(maximum[i] - minimum[i] + 1 for i in range(3)), cells, regions, requirements)
        self._remember(state); state.clipboard = clipboard
        return {"cells": len(cells), "size": list(clipboard.size), "offset": list(clipboard.offset),
                "regions": regions, "requirements": requirements, "blockStatesOnly": block_states_only}

    def paste(self, world, anchor, **options) -> dict[str, Any]:
        state = self._state(world)
        if state.clipboard is None: raise SelectionError("copy a selection before paste")
        anchor = _pos(anchor, "anchor"); clip = state.clipboard
        if clip.requirements: raise SelectionError("clipboard has unsupported tile-entity state requirements")
        allowed = {"replaceExisting", "timeoutTicks", "overrideProtection", "allowBreak", "allowPlace", "settings"}
        if set(options) - allowed: raise SelectionError("paste has unknown options")
        for key in ("replaceExisting", "overrideProtection", "allowBreak", "allowPlace"):
            if key in options and not isinstance(options[key], bool): raise SelectionError(f"paste {key} must be boolean")
        timeout = options.get("timeoutTicks", 12000)
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 72_000:
            raise SelectionError("paste timeoutTicks must be in 1..72000")
        try: settings = _settings(options.get("settings"))
        except SchematicError as exc: raise SelectionError(str(exc)) from exc
        paste_origin = [anchor[i] + clip.offset[i] for i in range(3)]
        for cell in clip.cells:
            absolute = [paste_origin[i] + cell["pos"][i] for i in range(3)]
            if abs(absolute[0]) > 30_000_000 or not 1 <= absolute[1] <= 254 or abs(absolute[2]) > 30_000_000:
                raise SelectionError("pasted cell is outside native world bounds")
        plan = {"origin": paste_origin, "size": list(clip.size),
                "cells": [dict(cell) for cell in clip.cells], "mode": "builder",
                "replaceExisting": options.get("replaceExisting", False),
                "allowBreak": options.get("allowBreak", False), "allowPlace": options.get("allowPlace", False),
                "timeoutTicks": timeout,
                "overrideProtection": options.get("overrideProtection", False)}
        if settings: plan["settings"] = settings
        return plan

    def shape_cells(self, world, block: dict[str, Any], shape="filled", axis="y") -> list[dict[str, Any]]:
        """Return canonical cells over each selected box; supports Java mask geometry."""
        state = self._state(world)
        if not state.selections: raise SelectionError("no selections")
        if not isinstance(block, dict) or set(block) - {"id", "meta"}: raise SelectionError("block must contain id and optional meta")
        target = {"id": _block_id(block.get("id")), "meta": block.get("meta", 0)}
        if isinstance(target["meta"], bool) or not isinstance(target["meta"], int) or not 0 <= target["meta"] <= 15: raise SelectionError("block meta must be in 0..15")
        if shape not in ("filled", "ellipsoid", "hollow_ellipsoid", "cylinder", "hollow_cylinder"):
            raise SelectionError("unsupported shape")
        if axis not in ("x", "y", "z"): raise SelectionError("axis must be x, y, or z")
        origin = tuple(min(x.minimum[i] for x in state.selections) for i in range(3)); cells = {}
        for selection in state.selections:
            dims = selection.size
            for pos in selection.positions():
                local_box = tuple(pos[i] - selection.minimum[i] for i in range(3))
                if shape != "filled" and not _shape_part(local_box, dims, shape, axis): continue
                local = tuple(pos[i] - origin[i] for i in range(3)); cells[local] = {"pos": list(local), **target}
        if len(cells) > MAX_CELLS: raise SelectionError("shape exceeds 16384 cells")
        return list(cells.values())


def _shape_part(pos, dims, shape, axis):
    hollow = shape.startswith("hollow_")
    if "ellipsoid" in shape:
        center = tuple(x / 2.0 for x in dims); radii = tuple(x * x for x in center)
        def outside(delta): return sum(delta[i] * delta[i] / radii[i] for i in range(3)) > 1
        delta = tuple(abs(pos[i] + .5 - center[i]) for i in range(3))
        return not outside(delta) and (not hollow or any(outside(tuple(delta[i] + (1 if i == j else 0) for i in range(3))) for j in range(3)))
    axes = {"x": (1, 2), "y": (0, 2), "z": (0, 1)}[axis]
    centers = tuple(dims[i] / 2.0 for i in axes); radii = tuple((x - 1) ** 2 for x in centers)
    delta = tuple(abs(pos[axes[i]] + .5 - centers[i]) for i in range(2))
    def outside(d): return sum(d[i] * d[i] / radii[i] for i in range(2)) > 1
    return not outside(delta) and (not hollow or any(outside(tuple(delta[i] + (1 if i == j else 0) for i in range(2))) for j in range(2)))


_STORE = SelectionStore()


def get_selection_store() -> SelectionStore:
    """Return the process-wide store, stable across GTNH profile hot reloads."""
    return _STORE
