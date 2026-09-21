# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The drawing: one format to look at a place, to plan in it and to build from.

A drawing is {origin:[x,y,z], layers:[[row, ...], ...], legend:{char: {id, meta?}}}: layers bottom first, rows north
to south (z grows), one character per block west to east (x grows), origin the bottom north-west corner. mb_view
produces it from the world, a region note keeps one as a plan (data.drawing), and mb_build(drawing=...) builds it.
"""
from __future__ import annotations

from typing import Any

from mbtool import kernel, tool
from mbtools_gtnh import notes

AIR, PLAYER, PLANNED, SKIP = ".", "@", "+", " "
CHARS = "#=%*&$~^ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
MAX_CELLS, RARE, LOOKUPS = 9000, 6, 32


def cells(drawing: dict) -> list[dict]:
    """The drawing's blocks as mb_build cells, relative to its origin. '.', ' ' and '+' are left alone."""
    legend = {c: ({"id": v} if isinstance(v, str) else v) for c, v in (drawing.get("legend") or {}).items()}
    out = []
    for dy, layer in enumerate(drawing["layers"]):
        for dz, row in enumerate(layer):
            for dx, c in enumerate(row):
                if c in (AIR, SKIP, PLANNED, PLAYER): continue
                if c not in legend or not legend[c].get("id"): raise ValueError(f"drawing uses {c!r} at layer {dy} row {dz} column {dx}, which its legend does not define")
                out.append({"pos": [dx, dy, dz], **{k: v for k, v in legend[c].items() if k in ("id", "meta", "item")}})
    if not out: raise ValueError("the drawing has no blocks: every character is '.', ' ' or '+'")
    return out


def labels_at(k, lo: list[int], hi: list[int]) -> list[dict]:
    """Your own region notes that overlap a box: what a receipt echoes back."""
    try: found = notes.read_notes(k, "search", {"region": {"min": lo, "max": hi}, "kind": "region", "limit": 8})["notes"]
    except Exception: return []
    return [{"id": n["id"], "title": n["title"]} for n in found if "auto" not in n.get("tags", [])]


@tool(lane="read", coverage=["building", "memory"])
def mb_view(bounds: dict | None = None, radius: int = 10, below: int = 2, above: int = 2, look_down: bool = False) -> Any:
    """Look at a place as a drawing, with everything you have recorded there on it.

    Default: radius blocks around you, from two layers under your feet (the floor and what
    runs beneath it) to two above them (your head and the ceiling): below and above change
    that. bounds {min:[x,y,z],max:[x,y,z]} picks any box instead: every y in it is one layer, bottom first (up to
    9000 cells a call). Rows run north to south, characters west to east; `columns` and `rows`
    give the world x and z of each edge. '.' is air, '@' you, '+' a planned block that is not
    built yet, and legend maps every other character to its block. things lists what stands
    in the box that is more than a block: machines and other rare blocks by name, your notes
    on blocks, locations and regions (with note ids), waypoints, protected regions, containers
    you have notes on. Regions are given as world boxes, not drawn, so that they hide nothing.
    look_down=True draws one layer instead: the highest block of each column in the box, as on
    a map, for surveying ground rather than rooms; its default box reaches 24 below and 24 above.
    The result is a drawing: edit its layers and hand it to mb_build(drawing=...) or
    mb_build_preview, or keep it as a plan by writing it to a region note's data.drawing
    (mb_note_write); the view then shows the unbuilt part of that plan as '+'.
    A note's data holds 16 KB: a large plan is several region notes, one per part.
    """
    k = kernel(); me = [int(v // 1) for v in k.call("obs.player")["pos"]]
    if bounds is None:
        if look_down and below == 2 and above == 2: below = above = 24  # a map is no use if it stops at the ceiling
        bounds = {"min": [me[0] - radius, max(0, me[1] - below), me[2] - radius], "max": [me[0] + radius, min(255, me[1] + above), me[2] + radius]}
    lo, hi = bounds["min"], bounds["max"]; size = [hi[i] - lo[i] + 1 for i in range(3)]
    if min(size) < 1: raise ValueError("bounds min must not exceed max")
    if size[0] * size[1] * size[2] > MAX_CELLS * (4 if look_down else 1) or size[0] > 96 or size[2] > 96:
        raise ValueError(f"{size[0]}x{size[1]}x{size[2]} is too much for one view: at most {MAX_CELLS} cells and 96 wide; look at fewer layers or a smaller box")
    found = k.call("nav.copy", bounds=bounds, includeAir=False)["plan"]["cells"]
    grid = {tuple(c["pos"]): (c["id"], c.get("meta", 0)) for c in found}
    if look_down:
        top = {}
        for (x, y, z), block in grid.items():
            if (x, z) not in top or y > top[x, z][0]: top[x, z] = (y, block)
        grid, heights, size = {(x, 0, z): b for (x, z), (y, b) in top.items()}, {(x, z): lo[1] + y for (x, z), (y, b) in top.items()}, [size[0], 1, size[2]]
    count: dict = {}
    for block in grid.values(): count[block] = count.get(block, 0) + 1
    ranked = sorted(count, key=lambda b: -count[b])
    if len(ranked) > len(CHARS): raise ValueError(f"{len(ranked)} kinds of block in view, more than there are characters: look at a smaller box")
    char = {block: CHARS[i] for i, block in enumerate(ranked)}
    layers = [[[char.get(grid.get((x, y, z)), AIR) for x in range(size[0])] for z in range(size[2])] for y in range(size[1])]

    things = []
    rare = [(pos, b) for pos, b in grid.items() if count[b] <= RARE][:LOOKUPS]  # a machine's identity is in its tile, not its id
    for pos, block in rare:
        world = [lo[0] + pos[0], heights[pos[0], pos[2]] if look_down else lo[1] + pos[1], lo[2] + pos[2]]
        try: seen = k.call("obs.block", x=world[0], y=world[1], z=world[2])
        except Exception: continue
        name = (seen.get("pickedItem") or {}).get("name") or seen.get("name")
        if name: things.append({"what": "block", "name": name, "pos": world, "char": char[block], **({"tile": True} if seen.get("tileEntity") or seen.get("tile") else {})})
    try: recorded = notes.read_notes(k, "search", {"region": {"min": lo, "max": hi}, "limit": 40, "detail": "full"})["notes"]
    except Exception as error: recorded = []; things.append({"what": "notes unavailable", "why": str(error)[:120]})
    for note in recorded:
        a = next((a for a in note["attachments"] if a["kind"] in ("region", "block", "location")), None)
        if a is None: continue
        entry = {"what": a["kind"] + " note", "id": note["id"], "title": note["title"], **({"tags": note["tags"]} if note.get("tags") else {}),
                 **({"box": {"min": a["min"], "max": a["max"]}} if a["kind"] == "region" else {"pos": a.get("pos")})}
        plan = (note.get("data") or {}).get("drawing") if isinstance(note.get("data"), dict) else None
        if plan and not look_down:
            unbuilt = 0
            for c in from_drawing(plan)[0]:
                p = tuple(plan["origin"][i] + c["pos"][i] - lo[i] for i in range(3))
                if all(0 <= p[i] < size[i] for i in range(3)) and grid.get(p, (None,))[0] != c["id"]:
                    unbuilt += 1
                    if p not in grid: layers[p[1]][p[2]][p[0]] = PLANNED
            entry["plan"] = {"unbuiltInView": unbuilt}
        things.append(entry)
    try: memory = k.call("memory.status")
    except Exception: memory = {}
    for name, pos in (memory.get("waypoints") or {}).items():
        if isinstance(pos, dict): pos = [pos[axis] for axis in ("x", "y", "z")]
        if all(lo[i] <= pos[i] <= hi[i] for i in range(3)): things.append({"what": "waypoint", "name": name, "pos": pos})
    for name, box in (memory.get("regions") or {}).items():
        if isinstance(box, dict):
            box = {**box, **{edge: [box[edge][axis] for axis in ("x", "y", "z")] if isinstance(box[edge], dict) else box[edge] for edge in ("min", "max")}}
        if isinstance(box, dict) and all(box["min"][i] <= hi[i] and box["max"][i] >= lo[i] for i in range(3)):
            things.append({"what": "protected region", "name": name, "box": {"min": box["min"], "max": box["max"]}, "mode": box.get("mode")})
    here = [me[0] - lo[0], 0 if look_down else me[1] - lo[1], me[2] - lo[2]]
    if all(0 <= here[i] < size[i] for i in range(3)): layers[here[1]][here[2]][here[0]] = PLAYER
    out = {"origin": lo if not look_down else [lo[0], hi[1], lo[2]], "columns": {"west": lo[0], "east": hi[0]}, "rows": {"north": lo[2], "south": hi[2]},
           "layers": [{"y": "highest block per column" if look_down else lo[1] + y, "rows": ["".join(row) for row in layer]} for y, layer in enumerate(layers)],
           "legend": {c: {"id": b[0], "meta": b[1], "count": count[b]} for b, c in char.items()}, "things": things, "you": me}
    return out


def from_drawing(drawing: dict) -> tuple[list[dict], list[int]]:
    """(cells, origin) for the build tools; accepts layers as lists of rows or as mb_view's {y, rows}."""
    if not isinstance(drawing, dict) or "layers" not in drawing or "origin" not in drawing: raise ValueError("a drawing needs origin, layers and legend")
    layers = [layer["rows"] if isinstance(layer, dict) else layer for layer in drawing["layers"]]
    return cells({**drawing, "layers": layers}), list(drawing["origin"])
