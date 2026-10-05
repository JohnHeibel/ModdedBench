# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The drawing: one format to look at a place, to plan in it and to build from.

A drawing is {origin:[x,y,z], layers:[[row, ...], ...], legend:{char: {id, meta?}}}: layers bottom first, rows north
to south (z grows), one character per block west to east (x grows), origin the bottom north-west corner. mb_view
produces it from the world, a region note keeps one as a plan (data.drawing), and mb_build(drawing=...) builds it.
A drawing to build may add stages:[chars, ...]: which legend characters are built after which (mb_build_preview).
"""
from __future__ import annotations

from typing import Any

from mbtool import kernel, tool
from mbtools_gtnh import notes

AIR, PLAYER, PLANNED, SKIP = ".", "@", "+", " "
CHARS = "#=%*&$~^ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
MAX_CELLS, RARE, LOOKUPS = 9000, 6, 32
# What a legend entry says of a block to build, and what mb_view writes there besides (read, and not built).
BUILT, VIEWED = ("id", "meta", "item", "verify", "clear", "replace", "click", "expect"), ("count", "name", "tile")


def cells(drawing: dict) -> list[dict]:
    """The drawing's blocks as mb_build cells, relative to its origin. '.', ' ' and '+' are left alone."""
    legend = {c: ({"id": v} if isinstance(v, str) else v) for c, v in (drawing.get("legend") or {}).items()}
    for c, entry in legend.items():
        unknown = sorted(set(entry) - set(BUILT) - set(VIEWED))
        if unknown: raise ValueError(f"legend entry {c!r} has {unknown}, which is not built: a legend entry holds {', '.join(BUILT)}")
    stage = _stages(drawing.get("stages"), legend)
    out = []
    heights = set()
    for index, layer in enumerate(drawing["layers"]):
        dy = index
        if isinstance(layer, dict):
            y = layer.get("y", drawing["origin"][1] + index)
            if type(y) is not int: raise ValueError("drawing layer y must be an integer world height")
            dy = y - drawing["origin"][1]
            layer = layer["rows"]
        if dy in heights: raise ValueError("drawing layers must have distinct heights")
        heights.add(dy)
        for dz, row in enumerate(layer):
            for dx, c in enumerate(row):
                if c in (AIR, SKIP, PLANNED, PLAYER): continue
                if c not in legend or not (legend[c].get("id") or legend[c].get("clear") is True): raise ValueError(f"drawing uses {c!r} at layer {dy} row {dz} column {dx}, which its legend does not define")
                out.append({"pos": [dx, dy, dz], **{k: v for k, v in legend[c].items() if k in BUILT},
                            **({"stage": stage[c]} if stage.get(c) else {})})
    if not out: raise ValueError("the drawing has no blocks: every character is '.', ' ' or '+'")
    return out


def _stages(stages: Any, legend: dict) -> dict:
    """{char: stage} from a drawing's stages, an ordered list of strings of legend characters. A character no stage
    names is in the first. The order is the drawing's own: nothing here knows what any block is."""
    if stages is None: return {}
    if not isinstance(stages, list) or not stages or not all(isinstance(s, str) and s for s in stages):
        raise ValueError('drawing stages is a list of strings of legend characters, first stage first, like ["#", "MC", "p"]')
    out: dict = {}
    for n, chars in enumerate(stages):
        for c in chars:
            if c not in legend: raise ValueError(f"drawing stages names {c!r}, which its legend does not define")
            if c in out: raise ValueError(f"drawing stages names {c!r} twice: a character is in one stage")
            out[c] = n
    return out


def labels_at(k, lo: list[int], hi: list[int]) -> list[dict]:
    """Your own region notes that overlap a box: what a receipt echoes back."""
    try: found = notes.lookup(k, region={"min": lo, "max": hi}, kind="region")[:8]
    except Exception: return []
    return [{"id": n["id"], "title": n["title"]} for n in found]


@tool(lane="read", coverage=["building", "memory"])
def mb_view(bounds: dict | None = None, radius: int = 10, below: int = 2, above: int = 2, look_down: bool = False,
            rare: int = RARE, lookups: int = LOOKUPS) -> Any:
    """Look at a place as a drawing, with everything you have recorded there on it.

    Default: radius blocks around you, from two layers under your feet (the floor and what
    runs beneath it) to two above them (your head and the ceiling): below and above change
    that. bounds {min:[x,y,z],max:[x,y,z]} picks any box instead: every y in it is one layer, bottom first (up to
    9000 cells a call). Rows run north to south, characters west to east; `columns` and `rows`
    give the world x and z of each edge. '.' is air, '@' you, '+' a planned block that is not
    built yet, and legend maps every other character to its block. A block with a tile entity
    (machines, chests, cables) is marked tile:true in the legend and named as you see it on hover,
    each name its own character, so ten machines sharing one id draw as ten; if names would not
    fit the characters, the most common id's names share one character ("names merged" in things).
    things lists what stands in the box that is more than a block: other rare blocks by name (a kind
    found at most `rare` times, the first `lookups` of them, one look each, 0 skips them; a thing
    "unnamed" counts those left out), your notes on blocks, locations and regions (with note
    ids), waypoints, protected regions, containers you have notes on. Regions are given as world boxes, not drawn, so that they hide nothing.
    look_down=True draws one layer instead: the highest block of each column in the box, as on
    a map, for surveying ground rather than rooms; its default box reaches 24 below and 24 above.
    The result is a drawing: edit its layers and hand it to mb_build(drawing=...) or
    mb_build_preview (they build by id and meta; name and tile are for you), or keep it as a plan by writing it to a region note's data as {"drawing": ...}
    (mb_note_new, or the note's <id>.json); the view then shows the unbuilt part of that plan as '+'.
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
    grid = {tuple(c["pos"]): (c["id"], c.get("meta", 0), c.get("name") if c.get("tile") else None) for c in found}  # a machine's identity is its name, not its id
    tiled = {grid[tuple(c["pos"])] for c in found if c.get("tile")}
    if look_down:
        top = {}
        for (x, y, z), block in grid.items():
            if (x, z) not in top or y > top[x, z][0]: top[x, z] = (y, block)
        grid, heights, size = {(x, 0, z): b for (x, z), (y, b) in top.items()}, {(x, z): lo[1] + y for (x, z), (y, b) in top.items()}, [size[0], 1, size[2]]
    kinds = len({b[:2] for b in grid.values()})
    if kinds > len(CHARS): raise ValueError(f"{kinds} kinds of block in view, more than there are characters: look at a smaller box")
    things, count = [], {}
    while True:
        count.clear()
        for block in grid.values(): count[block] = count.get(block, 0) + 1
        if len(count) <= len(CHARS): break
        named: dict = {}  # too many named kinds (cables come in dozens): the most common id gives its names up first
        for b in count: named.setdefault(b[:2], []).extend([b] if b[2] is not None else [])
        merge = max((base for base, bs in named.items() if len(bs) + ((*base, None) in count) > 1), key=lambda base: sum(count[b] for b in named[base]))
        things.append({"what": "names merged", "id": merge[0], "meta": merge[1], "kinds": len(named[merge]), "why": f"more named kinds than {len(CHARS)} characters: these share one, unnamed"})
        grid = {pos: (*merge, None) if b[:2] == merge else b for pos, b in grid.items()}; tiled.add((*merge, None))
    ranked = sorted(count, key=lambda b: -count[b])
    char = {block: CHARS[i] for i, block in enumerate(ranked)}
    layers = [[[char.get(grid.get((x, y, z)), AIR) for x in range(size[0])] for z in range(size[2])] for y in range(size[1])]

    candidates = [(pos, b) for pos, b in grid.items() if count[b] <= rare and b not in tiled]  # tile blocks are named in the legend already
    if len(candidates) > lookups: things.append({"what": "unnamed", "count": len(candidates) - lookups, "why": f"only the first lookups={lookups} rare blocks are looked up"})
    for pos, block in candidates[:lookups]:
        world = [lo[0] + pos[0], heights[pos[0], pos[2]] if look_down else lo[1] + pos[1], lo[2] + pos[2]]
        try: seen = k.call("obs.block", x=world[0], y=world[1], z=world[2])
        except Exception: continue
        name = (seen.get("pickedItem") or {}).get("name") or seen.get("name")
        if name: things.append({"what": "block", "name": name, "pos": world, "char": char[block], **({"tile": True} if seen.get("tileEntity") or seen.get("tile") else {})})
    try: recorded = notes.lookup(k, region={"min": lo, "max": hi})[:40]
    except Exception as error: recorded = []; things.append({"what": "notes unavailable", "why": str(error)[:120]})
    for note in recorded:
        a = next((a for a in note["anchors"] if a["kind"] in ("region", "block", "location")), None)
        if a is None: continue
        entry = {"what": a["kind"] + " note", "id": note["id"], "title": note["title"], **({"tags": note["tags"]} if note.get("tags") else {}),
                 **({"box": {"min": a["min"], "max": a["max"]}} if a["kind"] == "region" else {"pos": a.get("pos")})}
        try: plan = notes.data(note).get("drawing")
        except ValueError as error: plan = None; entry["plan"] = {"unreadable": str(error)[:200]}
        if plan and not look_down:
            unbuilt = 0
            for c in from_drawing(plan)[0]:
                p = tuple(plan["origin"][i] + c["pos"][i] - lo[i] for i in range(3))
                mismatch = p in grid if c.get("clear") else grid.get(p, ("minecraft:air",))[0] != c["id"]
                if all(0 <= p[i] < size[i] for i in range(3)) and mismatch:
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
            things.append({"what": "protected region", "name": name, "box": {"min": box["min"], "max": box["max"]}})
    here = [me[0] - lo[0], 0 if look_down else me[1] - lo[1], me[2] - lo[2]]
    if all(0 <= here[i] < size[i] for i in range(3)): layers[here[1]][here[2]][here[0]] = PLAYER
    out = {"origin": lo if not look_down else [lo[0], hi[1], lo[2]], "columns": {"west": lo[0], "east": hi[0]}, "rows": {"north": lo[2], "south": hi[2]},
           "layers": [{"y": "highest block per column" if look_down else lo[1] + y, "rows": ["".join(row) for row in layer]} for y, layer in enumerate(layers)],
           "legend": {c: {"id": b[0], "meta": b[1], **({"name": b[2]} if b[2] else {}), "count": count[b], **({"tile": True} if b in tiled else {})} for b, c in char.items()},
           "things": things, "you": me}
    return out


def from_drawing(drawing: dict) -> tuple[list[dict], list[int]]:
    """(cells, origin) for the build tools; accepts layers as lists of rows or as mb_view's {y, rows}."""
    if not isinstance(drawing, dict) or "layers" not in drawing or "origin" not in drawing: raise ValueError("a drawing needs origin, layers and legend")
    return cells(drawing), list(drawing["origin"])
