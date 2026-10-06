# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""World notes as files, in the game: a note on a real block is written with what was observed there, surfaces when
the block is looked at, is found by place, and follows the edits made to its file by hand.

    bash harness/smoke/mbtest.sh harness/smoke/notes_course.py

Cases: folder (mb_status names it and it exists), block (a note on a chest: the anchor keeps the observed id; observing
the block surfaces the note once), find (near the player and by region), data (a region note's drawing comes back from
its .json), append (a dated last line), edit (a hand edit of the text surfaces the note again), mangled (a broken header
is named under notesUnreadable and costs the anchors; mended, they are back).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import builder_shell as bs
from mbtools_gtnh import core, notes

CHEST = "minecraft:chest"


class Notes(bs.Shells):
    def run(self) -> list[tuple[str, bool, str]]:
        rows = []
        origin = self.arena(bare=True)
        at = lambda rel: [origin[0] + rel[0], bs.FLOOR + rel[1], origin[2] + rel[2]]
        self.set_block((2, 0, 4), CHEST); self.wait(5); self.stand([origin[0] + 2.5, bs.FLOOR, origin[2] + 2.5]); self.wait(5)
        pos = at((2, 0, 4)); x, y, z = pos
        tag = time.strftime("%H%M%S")

        status = core.mb_status(); folder = Path(status.get("notesFolder") or "/nonexistent")
        rows.append(("folder", folder.is_dir(), f"notesFolder={folder} unreadable={status.get('notesUnreadable')}"))

        made = notes.mb_note_new(f"live-block-{tag}", "live check block", [{"kind": "block", "pos": pos}], text="first line")
        file = folder / f"live-block-{tag}.md"; head = file.read_text() if file.exists() else ""
        first = core.mb_obs("block", {"x": x, "y": y, "z": z}); second = core.mb_obs("block", {"x": x, "y": y, "z": z})
        surfaced = [n.get("id") for n in first.get("notes") or []]
        rows.append(("block", file.exists() and CHEST in head and f"live-block-{tag}" in surfaced and not second.get("notes"),
                     f"made={json.dumps(made)[:160]} headHasChest={CHEST in head} surfaced={surfaced} again={second.get('notes')}"))

        lo, hi = at((0, 0, 0)), at((6, 3, 6)); drawing = {"origin": lo, "layers": [["#"]], "legend": {"#": {"id": "minecraft:dirt"}}}
        notes.mb_note_new(f"live-room-{tag}", "live check room", [{"kind": "region", "min": lo, "max": hi}], data={"drawing": drawing})
        near = [n["id"] for n in notes.mb_notes("find", {"near": "player", "radius": 8})["notes"]]
        region = [n["id"] for n in notes.mb_notes("find", {"region": {"min": lo, "max": hi}})["notes"]]
        rows.append(("find", f"live-block-{tag}" in near and f"live-room-{tag}" in region, f"near={near} region={region}"))

        stored = json.loads((folder / f"live-room-{tag}.json").read_text())
        rows.append(("data", stored.get("drawing") == drawing, f"json={json.dumps(stored)[:120]}"))

        notes.mb_note_append(f"live-block-{tag}", "second entry"); last = file.read_text().rstrip("\n").splitlines()[-1]
        rows.append(("append", last.startswith("[20") and last.endswith("second entry"), f"last={last!r}"))

        time.sleep(1.1); file.write_text(file.read_text() + "edited by hand\n")
        third = core.mb_obs("block", {"x": x, "y": y, "z": z})
        rows.append(("edit", f"live-block-{tag}" in [n.get("id") for n in third.get("notes") or []], f"afterEdit={third.get('notes')}"))

        good = file.read_text(); time.sleep(1.1); file.write_text(good.replace("anchor: {", "anchor: ", 1))
        bad = core.mb_status().get("notesUnreadable"); lost = f"live-block-{tag}" not in [n["id"] for n in notes.mb_notes("find", {"near": "player", "radius": 8})["notes"]]
        time.sleep(1.1); file.write_text(good)
        back = f"live-block-{tag}" in [n["id"] for n in notes.mb_notes("find", {"near": "player", "radius": 8})["notes"]]
        rows.append(("mangled", bool(bad) and lost and back and not core.mb_status().get("notesUnreadable"), f"unreadable={bad} lostAnchors={lost} mended={back}"))
        return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials = 1, 1
    t = Notes(args); t.setup(); rows = []
    try:
        try: rows = t.run()
        except Exception as e: rows.append(("crashed", False, f"{type(e).__name__}: {e}"))
    finally:
        t.teardown()
    for name, ok, why in rows: print(f"{name:8} {'PASS' if ok else 'FAIL'} {why[:600]}", flush=True)
    print(f"{sum(ok for _, ok, _ in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if rows and all(ok for _, ok, _ in rows) else 1)


if __name__ == "__main__":
    main()
