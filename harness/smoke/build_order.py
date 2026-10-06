# SPDX-License-Identifier: MIT
"""Build order, measured from a finished job's own files: no game, no model of the builder.

A build job leaves <jobId>.attempts.jsonl beside its journal: one row per right-click the game took, in order,
keyed by the cell the block lands in ("x,y,z"). That is the real placement order (clicks made by the walk as it bridges included).
This reads it back as numbers that can be compared between two runs of the same plan:

  python harness/smoke/build_order.py <work dir> <jobId or prefix>...

What it cannot say is how far the player walked: the ledger has cells, not positions. `jumps` is the nearest
honest thing: two cells clicked one after the other more than JUMP blocks apart cannot both have been in reach
from one spot, so the player crossed at least that distance less two reaches between them.
The ledger spans every session of a job but the receipt's ticks are the last session's, so ticksPerClick and
cellsPerMinute are only right for a job that ran once.

A click made before the placed block was seen, or one that made nothing, shows as a second click into the same
cell. reclicks counts those clicks, reclickedCells the cells they went into and maxAttempts the most any one cell
took (a job stops at 8). overGap knows the cells still wrong at the end only as far as the receipt lists them
(left.first, at most 8).
"""
import json
import math
import sys
from collections import Counter
from pathlib import Path

JUMP = 8.0   # further apart than this, two consecutive placements needed a walk (reach is about 4.5 each way)


def metrics(clicks: list[tuple[int, int, int]], ticks: int | None = None, left: list | None = None) -> dict:
    """clicks: cells in the order clicked. ticks: the job's own tick count. left: cells still wrong at the end."""
    steps = [math.dist(a, b) for a, b in zip(clicks, clicks[1:])]
    jumps = [s for s in steps if s > JUMP]
    first = {}
    for i, c in enumerate(clicks): first.setdefault(c, i)
    unfinished = {tuple(int(v) for v in c) for c in left or []}
    # A cell laid while the cell under it was still to come: later in this ledger, or never (still wrong at the end).
    over_gap = sum(1 for c, i in first.items()
                   if first.get((c[0], c[1] - 1, c[2]), -1) > i or (c[0], c[1] - 1, c[2]) in unfinished)
    counts = Counter(clicks)
    out = {"clicks": len(clicks), "cells": len(first), "reclicks": len(clicks) - len(first),
           "reclickedCells": sum(1 for n in counts.values() if n > 1), "maxAttempts": max(counts.values(), default=0),
           "meanStep": round(sum(steps) / len(steps), 2) if steps else 0.0,
           "jumps": len(jumps), "jumpBlocks": round(sum(jumps), 1), "longestJump": round(max(jumps, default=0.0), 1),
           "layerChanges": sum(1 for a, b in zip(clicks, clicks[1:]) if a[1] != b[1]),
           "overGap": over_gap}
    if ticks:
        out["ticks"] = ticks
        out["ticksPerClick"] = round(ticks / max(1, len(clicks)), 1)
        out["cellsPerMinute"] = round(len(first) * 1200 / ticks, 1)
    return out


def read(base: Path) -> dict:
    """Metrics for the job whose files start with `base` (the path without .json)."""
    rows = [json.loads(line) for line in Path(f"{base}.attempts.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    clicks = [tuple(int(v) for v in r["key"].split(",")) for r in rows]
    journal = json.loads(Path(f"{base}.json").read_text(encoding="utf-8"))
    receipt = journal.get("receipt", {})
    return {"job": base.name[:8], "state": receipt.get("state"), "reason": receipt.get("reason"),
            **({"step": receipt["step"]} if receipt.get("step") else {}),
            **metrics(clicks, receipt.get("ticks"), (receipt.get("left") or {}).get("first"))}


if __name__ == "__main__":
    work = Path(sys.argv[1])
    for job in sys.argv[2:]:
        for ledger in sorted(work.glob(f"{job}*.attempts.jsonl")):
            print(json.dumps(read(ledger.with_name(ledger.name[:-len(".attempts.jsonl")]))))
