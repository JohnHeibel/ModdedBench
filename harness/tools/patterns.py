# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Saved click-step plans (patterns): save, list, load, and place them turned and mirrored.

A pattern is the `steps` of an mb_build, stored relative to the minimum corner of its step positions in
harness/patterns/<name>.json: a plain git-tracked file next to the tool code, like harness/scripts, so it
outlives a world, can be read and edited by hand, and can be carried to another base. The turning is pure
geometry and lives here; Java sees ordinary steps.
"""

from __future__ import annotations

import copy
import json
import re
import time
from pathlib import Path
from typing import Any

from mbtool import kernel, tool

PATTERNS = Path(__file__).resolve().parents[1] / "patterns"
NAMES = ["down", "up", "north", "south", "west", "east"]
AXES = ["-y", "+y", "-z", "+z", "-x", "+x"]
# One quarter turn clockwise seen from above: north -> east -> south -> west -> north.
TURN = {0: 0, 1: 1, 2: 5, 5: 3, 3: 4, 4: 2}
MIRROR = {"x": {4: 5, 5: 4}, "z": {2: 3, 3: 2}}


def _face_index(value: Any) -> int | None:
    if isinstance(value, bool): return None
    if isinstance(value, int) and 0 <= value <= 5: return value
    if isinstance(value, str):
        k = value.lower()
        if k in NAMES: return NAMES.index(k)
        if k in AXES: return AXES.index(k)
    return None


def _face_like(original: Any, index: int) -> Any:
    """The new face written the way the original was (0..5, a name, or an axis)."""
    if isinstance(original, int): return index
    return AXES[index] if original.lower() in AXES else NAMES[index]


def _map_face(value: Any, table: dict) -> Any:
    index = _face_index(value)
    return value if index is None else _face_like(value, table.get(index, index))


def _map_faces(value: Any, table: dict) -> Any:
    """A face or a list of faces; anything else (a number that is not a face, a map) is left as it is."""
    if isinstance(value, list): return [_map_faces(v, table) for v in value]
    return _map_face(value, table)


def _pos(value: Any) -> list[int]:
    if isinstance(value, dict): return [int(value["x"]), int(value["y"]), int(value["z"])]
    if isinstance(value, (list, tuple)) and len(value) == 3: return [int(v) for v in value]
    raise ValueError(f"a position is [x, y, z], got {value!r}")


def _yaw(value: Any, fn) -> Any:
    """A yaw (number) or inclusive range [low, high] through fn; a range keeps its width and stays low <= high."""
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return round(((fn(value) + 180) % 360) - 180, 4)
    lo, hi = value
    a, b = fn(lo), fn(hi)
    low, width = min(a, b), abs(hi - lo)
    low = ((low + 180) % 360) - 180
    return [round(low, 4), round(low + width, 4)]


def _transform(steps: list[dict], rotate: int, mirror: str | None) -> list[dict]:
    """Mirror (across x: west<->east, or z: north<->south), then turn clockwise by rotate degrees, about the cell grid.
    Positions, click faces, hit points, look requirements and expectations marked faces:true change; ids, items and
    the rest do not. Positions are not yet normalized."""
    if rotate not in (0, 90, 180, 270): raise ValueError("rotate is 0, 90, 180 or 270 (clockwise seen from above)")
    if mirror not in (None, "x", "z"): raise ValueError('mirror is "x" (swap west and east), "z" (swap north and south) or None')
    out = copy.deepcopy(steps)
    ops: list[str] = ([f"m{mirror}"] if mirror else []) + ["r"] * (rotate // 90)

    def cell(p: list[int], op: str) -> list[int]:
        x, y, z = p
        if op == "mx": return [-x - 1, y, z]
        if op == "mz": return [x, y, -z - 1]
        return [-z - 1, y, x]

    def hit(h: list, op: str) -> list:
        x, y, z = h
        if op == "mx": return [1 - x, y, z]
        if op == "mz": return [x, y, 1 - z]
        return [1 - z, y, x]

    def yaw(v: float, op: str) -> float:
        if op == "mx": return -v
        if op == "mz": return 180 - v
        return v + 90

    table = {"mx": MIRROR["x"], "mz": MIRROR["z"], "r": TURN}
    for step in out:
        for op in ops:
            if "pos" in step: step["pos"] = cell(_pos(step["pos"]), op)
            click = step.get("click")
            if isinstance(click, dict):
                if "face" in click: click["face"] = _map_face(click["face"], table[op])
                if isinstance(click.get("hit"), list) and len(click["hit"]) == 3: click["hit"] = hit(click["hit"], op)
                look = click.get("look")
                if isinstance(look, dict):
                    if "toward" in look: look["toward"] = _map_face(look["toward"], table[op])
                    if "yaw" in look: look["yaw"] = _yaw(look["yaw"], lambda v, op=op: yaw(v, op))
            for e in step.get("expect") or []:
                if not isinstance(e, dict): continue
                if "pos" in e: e["pos"] = cell(_pos(e["pos"]), op)
                if e.get("faces"):
                    for key in ("equals", "contains"):
                        if key in e: e[key] = _map_faces(e[key], table[op])
    return out


def _corner(steps: list[dict]) -> list[int]:
    spots = [_pos(s["pos"]) for s in steps]
    return [min(p[i] for p in spots) for i in range(3)]


def _shift(steps: list[dict], by: list[int]) -> list[dict]:
    """Every step and expectation position moved by `by`."""
    out = copy.deepcopy(steps)
    for s in out:
        s["pos"] = [a + b for a, b in zip(_pos(s["pos"]), by)]
        for e in s.get("expect") or []:
            if isinstance(e, dict) and "pos" in e: e["pos"] = [a + b for a, b in zip(_pos(e["pos"]), by)]
    return out


def _oriented(step: dict) -> bool:
    click = step.get("click") or {}
    return any(k in click for k in ("face", "hit", "look"))


def placed(pattern: dict, at: list[int], rotate: int = 0, mirror: str | None = None) -> dict:
    """The pattern's steps turned, mirrored and placed with their minimum corner at `at`: {steps, origin, ...}."""
    steps = _transform(pattern["steps"], rotate, mirror)
    corner = _corner(steps)
    steps = _shift(steps, [-c for c in corner])
    dropped = []
    if rotate or mirror:
        # Block metadata often encodes a facing in a way each block defines for itself, which cannot be turned
        # generically. Where the click fixes the facing, the click is turned and the metadata it predicts is checked.
        for i, s in enumerate(steps):
            if s.get("kind", "place") == "place" and "meta" in s and _oriented(s):
                dropped.append({"step": s.get("name", i), "meta": s.pop("meta")})
    out = {"steps": steps, "origin": _pos(at)}
    if dropped:
        out["metaDropped"] = dropped
        out["metaDroppedMeaning"] = ("these place steps fix their facing by the click; turned, their saved meta no longer "
                                     "names the facing, so the builder checks the meta its turned click predicts instead. "
                                     "A block with several variants is then chosen by any stack of that block id; add an "
                                     "item selector to the step to choose the variant")
    return out


def _path(name: str) -> Path:
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,48}", name or ""):
        raise ValueError("name is lower_snake_case, at most 49 characters")
    return PATTERNS / f"{name}.json"


def load(name: str) -> dict:
    path = _path(name)
    if not path.is_file():
        raise ValueError(f"no pattern {name!r}; saved: {sorted(p.stem for p in PATTERNS.glob('*.json'))}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("steps"), list) or not data["steps"]:
        raise ValueError(f"pattern {name!r} has no steps")
    return data


def build_params(spec: dict) -> dict:
    """mb_build(pattern={name, at, rotate?, mirror?}) -> the steps and origin to send."""
    if not isinstance(spec, dict) or "name" not in spec or "at" not in spec:
        raise ValueError("pattern is {name, at:[x,y,z], rotate?: 0|90|180|270, mirror?: 'x'|'z'}")
    extra = set(spec) - {"name", "at", "rotate", "mirror"}
    if extra: raise ValueError(f"pattern has no field(s) {sorted(extra)}")
    return placed(load(spec["name"]), spec["at"], int(spec.get("rotate", 0)), spec.get("mirror"))


@tool(effect="interaction", coverage=["machine"])
def mb_pattern(operation: str = "list", name: str | None = None, steps: list[dict] | None = None,
               origin: list[int] | None = None, job_id: str | None = None, note: str | None = None,
               replace: bool = False, at: list[int] | None = None, rotate: int = 0, mirror: str | None = None) -> Any:
    """Keep a click-step plan that worked so it can be built again elsewhere, turned or mirrored.

    operation:
    - "save": store `steps` (with their `origin`, as given to mb_build) or the steps of the build job
      `job_id` under `name`, with an optional `note`. Positions are stored relative to the minimum
      corner of the step positions. A job that did not succeed is saved too, marked proven: false.
      replace=True overwrites a pattern of the same name.
    - "list": names, step counts, sizes, notes and whether each was proven.
    - "load": the pattern; with `at`, also `placed`: {steps, origin} turned by `rotate` degrees clockwise
      seen from above (0, 90, 180, 270) after mirroring across `mirror` ("x" swaps west and east, "z"
      swaps north and south), its minimum corner at `at`. Pass those to mb_build_preview / mb_build, or
      build in one call with mb_build(pattern={name, at, rotate, mirror}).
    Turning moves positions and changes click faces, hit points, look.toward and look.yaw, expectation
    positions (expect.pos), and expectation values marked faces: true. Positions inside expect.params
    are not moved, so give an expectation's block as expect.pos. A turned place step that fixes its
    click drops its saved meta (the receipt lists it in metaDropped), because what a meta value means
    for facing is the block's own. Files live in harness/patterns/<name>.json and can be edited.
    """
    if operation == "list":
        rows = []
        for path in sorted(PATTERNS.glob("*.json")):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                rows.append({"name": path.stem, "steps": len(data.get("steps") or []), "size": data.get("size"),
                             "proven": data.get("proven"), "note": data.get("note")})
            except (OSError, ValueError) as error:
                rows.append({"name": path.stem, "unreadable": str(error)[:200]})
        return {"patterns": rows, "directory": str(PATTERNS)}
    if operation == "load":
        data = load(name)
        return {**data, "placed": placed(data, at, rotate, mirror)} if at is not None else data
    if operation != "save":
        raise ValueError('operation is "save", "list" or "load"')
    path = _path(name)
    if path.exists() and not replace:
        raise ValueError(f"pattern {name!r} exists; replace=True overwrites it")
    source: dict = {}
    proven = None
    if job_id is not None:
        if steps is not None: raise ValueError("give steps or job_id, not both")
        status = kernel().call("nav.work_status", jobId=job_id)
        spec = (status or {}).get("specSummary") or {}
        steps, origin = spec.get("steps"), spec.get("origin")
        if not isinstance(steps, list):
            raise ValueError("that job has no steps to save (a cell build, or a step list too long to summarize)")
        state = ((status or {}).get("receipt") or {}).get("state")
        proven = state == "succeeded"
        source = {"jobId": job_id, "state": state}
    if not steps: raise ValueError("give steps (and their origin) or job_id")
    absolute = _shift(steps, _pos(origin) if origin is not None else [0, 0, 0])
    corner = _corner(absolute)
    stored = _shift(absolute, [-c for c in corner])
    far = [max(_pos(s["pos"])[i] for s in stored) for i in range(3)]
    data = {"name": name, "steps": stored, "size": [f + 1 for f in far], "proven": proven,
            "source": {**source, "savedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "corner": corner}}
    if note: data["note"] = note
    PATTERNS.mkdir(exist_ok=True)
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8", newline="\n")
    return {"saved": name, "path": str(path), "steps": len(stored), "size": data["size"], "proven": proven}
