# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The build regression suite: every in-game build scenario in one run, one PASS/FAIL row each, judged by the world.

Each scenario calls the model's own tool (mb_build, mb_work_resume) and then reads the cells back from the server; the
receipt is checked against what the server finds, never taken on its word. Run it after every change to the builder:
a scenario that passed before and fails now is a regression.

    bash harness/smoke/mbtest.sh harness/smoke/build_suite.py [--only NAME ...] [--skip NAME ...] [--keep]

  door_inside, door_outside, sealed_inside   the 7x7 shells of builder_shell.py
  staged        a base, two ends standing on it and a line joining them, as three stages; the ledger must show that order
  unfinishable  a cell with nothing to place it against: stops as no_stance naming that cell, later stages untouched
  missing       a 121-cell shell with one stack: pauses as missing_materials with the list; restocked and resumed, it finishes
  occupied      a plan cell holds another block and replace_existing is off: refused before any input, the cell named
  timeout       a 200-tick budget: pauses as timeout with its jobId; resumed, it finishes
  any_meta      a block that faces the way it is placed, without meta: verified whichever way it landed
  clear         a built shell taken down again by a clear selection: every cell air
  hidden        a block to replace in a wall, a block outside the plan in front of it: seen only from behind, so the job walks round
  buried        the same with every face covered: stops as no_stance naming the cell and what is in the way, nothing else touched
  lining        a roofed room lined on every inside wall face from inside it: the corners under the ceiling lie behind the two cells
                beside them, so they go in first
  terrain       a hall on natural ground, whatever stands there dug out: 11 x 11 and 5 high with a door, 320 cells
  hall          only when named (--only hall): run 2's hall, 25 x 25 and 7 high, 1728 cells, resumed until done

Evidence: .runtime/evidence/build-suite.json (history: one entry per run with the commit), and a table on stdout.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_order  # noqa: E402
import builder_shell as bs  # noqa: E402
import movement_course as mc  # noqa: E402
from movement_course import BridgeError, work  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "build-suite.json"
WORK = Path(os.environ.get("MB_CLIENT_WORK", "/clientwork"))
DIRT, STONE, WOOD, AIR = "minecraft:dirt", "minecraft:cobblestone", "minecraft:planks", "minecraft:air"
HALL, SMALL = (25, 7, 25), (11, 5, 11)


def call(fn, **kw) -> dict:
    """A tool's receipt, whether it came back as a result or inside the error."""
    try: return fn(**kw)
    except BridgeError as e:
        err = (e.reply or {}).get("error") or {}
        return {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "errorCode": e.code, "errorMsg": e.msg}


def hall_cells(size) -> tuple[list[dict], list[list[int]]]:
    """(cells, door gap): floor, a ring of walls five high with a two-high door in the west wall, and a roof."""
    w, h, d = size; door = [[0, y, d // 2] for y in (1, 2)]
    cells = [[x, y, z] for y in range(h) for x in range(w) for z in range(d)
             if (y in (0, h - 1) or x in (0, w - 1) or z in (0, d - 1)) and [x, y, z] not in door]
    return [{"pos": p, "id": DIRT, "meta": 0} for p in cells], door


class Suite(bs.Shells):
    def stacks(self, *items):
        """Inventory slots from 0: (id, count) each; the first two slots are what arena() filled."""
        for slot, (item, count) in enumerate(items): self.s.call(bs.FIX + ".set_stack", slot=slot, id=item, meta=0, count=count)

    def at(self, origin, rel): return [origin[i] + rel[i] for i in range(3)]

    def wrong(self, origin, cells, want=None) -> list:
        world = [self.at(origin, c["pos"]) for c in cells]; found = self.blocks(world)
        return sorted(w for w, c in zip(world, cells) if found.get(tuple(w)) != (want or c["id"]))

    def ledger(self, job: str) -> list[tuple]:
        path = WORK / f"{job}.attempts.jsonl"
        return [tuple(int(v) for v in json.loads(l)["key"].split(",")) for l in path.read_text().splitlines() if l.strip()] if path.is_file() else []

    def start(self, rel=(3, 3)):
        origin = self.arena()
        self.stand([origin[0] + rel[0] + .5, bs.FLOOR, origin[2] + rel[1] + .5])
        return origin

    # -- scenarios: each returns {passed, why, ...evidence}
    def staged(self):
        origin = self.start((2, -2)); self.stacks((DIRT, 64), (STONE, 64), (WOOD, 64))
        drawing = {"origin": origin, "stages": ["#", "AB", "j"],
                   "legend": {"#": {"id": DIRT}, "A": {"id": STONE}, "B": {"id": STONE}, "j": {"id": WOOD}},
                   "layers": [["#####"], ["AjjjB"]]}
        r = call(work.mb_build, drawing=drawing)
        cells = [{"pos": [x, 0, 0], "id": DIRT} for x in range(5)] + [{"pos": [x, 1, 0], "id": STONE if x in (0, 4) else WOOD} for x in range(5)]
        wrong = self.wrong(origin, cells); order = self.ledger(r.get("jobId", ""))
        first = {c: i for i, c in reversed(list(enumerate(order)))}
        at = lambda rel: first.get(tuple(self.at(origin, rel)), -1)
        base, ends, line = [at([x, 0, 0]) for x in range(5)], [at([0, 1, 0]), at([4, 1, 0])], [at([x, 1, 0]) for x in (1, 2, 3)]
        ordered = min(base) >= 0 and max(base) < min(ends) and max(ends) < min(line)
        return {"passed": r.get("state") == "succeeded" and not wrong and ordered, "receipt": r, "wrong": wrong, "ordered": ordered,
                "why": f"state={r.get('state')} wrong={len(wrong)} ordered={ordered} step={r.get('step')}"}

    def unfinishable(self):
        origin = self.start((2, -2))
        cells = ([{"pos": [x, 0, 0], "id": DIRT} for x in range(3)] + [{"pos": [1, 4, 6], "id": DIRT, "stage": 1}]
                 + [{"pos": [x, 1, 0], "id": DIRT, "stage": 2} for x in range(3)])
        r = call(work.mb_build, cells=cells, origin=origin, timeout_ticks=3000)
        stop = r.get("stopped") or {}; floating = self.at(origin, [1, 4, 6])
        base, later = self.wrong(origin, cells[:3]), self.wrong(origin, cells[4:], want=AIR)
        ok = (r.get("state") in ("paused", "failed") and stop.get("reason") == "no_stance" and stop.get("pos") == floating
              and not base and not later and (r.get("left") or {}).get("count") == 4)
        return {"passed": ok, "receipt": r, "why": f"state={r.get('state')} stopped={stop} baseWrong={len(base)} laterTouched={len(later)} left={r.get('left')} ticks={r.get('ticks')}"}

    def missing(self):
        origin = self.start(); self.stacks((DIRT, 64), (STONE, 1)); cells = bs.shell(False)
        r = call(work.mb_build, cells=cells, origin=origin)
        stop, short = r.get("stopped") or {}, r.get("missing") or []
        wrong = self.wrong(origin, cells)
        told = (r.get("state") == "paused" and stop.get("reason") == "missing_materials" and stop.get("pos") in wrong
                and len(wrong) == 121 - 64 == (r.get("left") or {}).get("count") and any((m.get("selector") or {}).get("id") == DIRT for m in short))
        self.stacks((DIRT, 64), (DIRT, 64))
        r2 = call(work.mb_work_resume, job_id=r.get("jobId", "")) if r.get("jobId") else {}
        after = self.wrong(origin, cells)
        return {"passed": told and r2.get("state") == "succeeded" and not after, "receipt": r, "resumed": r2,
                "why": f"first: state={r.get('state')} stopped={stop} wrong={len(wrong)} missing={short[:1]} | resumed: state={r2.get('state')} reason={r2.get('reason')} wrong={len(after)}"}

    def occupied(self):
        origin = self.arena(); cells = bs.shell(True); rel = [6, 1, 3]; cell = self.at(origin, rel)
        self.s.call(bs.FIX + ".set_block", x=bs.PLOT[0] + rel[0], y=bs.FLOOR + rel[1], z=bs.PLOT[1] + rel[2], id=STONE, meta=0)
        self.stand([origin[0] + 3.5, bs.FLOOR, origin[2] + 3.5])   # and a second for the client to see the block
        r = call(work.mb_build, cells=cells, origin=origin, allow_break=True)
        occ = r.get("occupied") or {}; placed = [w for w in self.wrong(origin, cells, want=AIR) if w != cell]
        ok = (r.get("state") == "failed" and (r.get("stopped") or {}).get("reason") == "occupied" and occ.get("count") == 1
              and occ.get("first") == [cell] and not placed and not r.get("placed"))
        return {"passed": ok, "receipt": r, "why": f"state={r.get('state')} code={r.get('errorCode')} stopped={r.get('stopped')} occupied={occ} placedAnyway={len(placed)}"}

    def timeout(self):
        origin = self.start(); cells = bs.shell(True)
        r = call(work.mb_build, cells=cells, origin=origin, timeout_ticks=200)
        stop = r.get("stopped") or {}; told = r.get("state") == "paused" and stop.get("reason") == "timeout" and bool(r.get("jobId"))
        r2 = call(work.mb_work_resume, job_id=r["jobId"], options={"timeoutTicks": 12000}) if r.get("jobId") else {}
        wrong = self.wrong(origin, cells)
        return {"passed": told and r2.get("state") == "succeeded" and not wrong, "receipt": r, "resumed": r2,
                "why": f"first: state={r.get('state')} code={r.get('errorCode')} stopped={stop} placed={r.get('placed')} job={bool(r.get('jobId'))} | resumed: state={r2.get('state')} wrong={len(wrong)}"}

    def any_meta(self):
        origin = self.start((2, -2)); self.stacks((DIRT, 64), ("minecraft:furnace", 4))
        cells = [{"pos": [x, 0, 0], "id": DIRT} for x in range(3)] + [{"pos": [1, 1, 0], "id": "minecraft:furnace"}]
        r = call(work.mb_build, cells=cells, origin=origin)
        wrong = self.wrong(origin, cells)
        return {"passed": r.get("state") == "succeeded" and not wrong and "minecraft:furnace" in ((r.get("anyMeta") or {}).get("ids") or []),
                "receipt": r, "why": f"state={r.get('state')} stopped={r.get('stopped')} wrong={wrong} anyMeta={r.get('anyMeta')}"}

    def clear(self):
        origin = self.start(); cells = bs.shell(True)
        r = call(work.mb_build, cells=cells, origin=origin)
        built = self.wrong(origin, cells)
        self.stand([origin[0] - 1.5, bs.FLOOR, origin[2] + 3.5])
        r2 = call(work.mb_build, selection={"min": origin, "max": self.at(origin, [6, 3, 6]), "shape": "clear"})
        box = [{"pos": [x, y, z], "id": AIR} for x in range(7) for y in range(4) for z in range(7)]
        left = self.wrong(origin, box)
        return {"passed": not built and r2.get("state") == "succeeded" and not left, "receipt": r2,
                "why": f"built wrong={len(built)} | clear: state={r2.get('state')} stopped={r2.get('stopped')} removed={r2.get('removed')} notAir={len(left)} ticks={r2.get('ticks')}"}

    def walled(self, extra):
        """A 3 x 3 cobblestone wall with `extra` blocks beside it, none of it in the plan but the one cell asked for as planks."""
        origin = self.arena(); self.stacks((DIRT, 64), (WOOD, 8))
        rel = [[x, y, 3] for x in (2, 3, 4) for y in range(3)] + extra; cell = extra[0][:2] + [3]
        for r in rel: self.s.call(bs.FIX + ".set_block", x=bs.PLOT[0] + r[0], y=bs.FLOOR + r[1], z=bs.PLOT[1] + r[2], id=STONE, meta=0)
        self.stand([origin[0] + 3.5, bs.FLOOR, origin[2] + .5])
        r = call(work.mb_build, cells=[{"pos": cell, "id": WOOD}], origin=origin, replace_existing=True, timeout_ticks=3000)
        others = self.wrong(origin, [{"pos": p, "id": STONE} for p in rel if p != cell])
        return origin, cell, r, others, self.blocks([self.at(origin, cell)]).get(tuple(self.at(origin, cell)))

    def hidden(self):
        origin, cell, r, others, found = self.walled([[3, 1, 2]])
        return {"passed": r.get("state") == "succeeded" and found == WOOD and not others, "receipt": r,
                "why": f"state={r.get('state')} stopped={r.get('stopped')} cell={found} othersTouched={len(others)} ticks={r.get('ticks')}"}

    def buried(self):
        origin, cell, r, others, found = self.walled([[3, 1, 2], [3, 1, 4]])
        stop = r.get("stopped") or {}; by = stop.get("blockedBy") or {}
        ok = (r.get("state") in ("paused", "failed") and stop.get("reason") == "no_stance" and stop.get("pos") == self.at(origin, cell)
              and by.get("id") == STONE and sum(abs(a - b) for a, b in zip(by.get("pos") or [0, 0, 0], self.at(origin, cell))) == 1 and found == STONE and not others)
        return {"passed": ok, "receipt": r, "why": f"state={r.get('state')} stopped={stop} cell={found} othersTouched={len(others)} ticks={r.get('ticks')}"}

    def lining(self):
        origin = self.arena(); self.stacks((WOOD, 64), (DIRT, 16))
        door = [[3, 0, 6], [3, 1, 6]]
        room = [[x, y, z] for x in range(7) for z in range(7) for y in range(4) if (y == 3 or x in (0, 6) or z in (0, 6)) and [x, y, z] not in door]
        for r in room: self.s.call(bs.FIX + ".set_block", x=bs.PLOT[0] + r[0], y=bs.FLOOR + r[1], z=bs.PLOT[1] + r[2], id=STONE, meta=0)
        ring = [[x, y, z] for y in range(3) for x in range(1, 6) for z in range(1, 6) if (x in (1, 5) or z in (1, 5)) and [x, y, z] not in ([3, 0, 5], [3, 1, 5])]
        cells = [{"pos": c, "id": WOOD} for c in ring]
        self.stand([origin[0] + 3.5, bs.FLOOR, origin[2] + 3.5])
        r = call(work.mb_build, cells=cells, origin=origin, timeout_ticks=6000)
        wrong = self.wrong(origin, cells); others = self.wrong(origin, [{"pos": c, "id": STONE} for c in room])
        return {"passed": r.get("state") == "succeeded" and not wrong and not others, "receipt": r,
                "why": f"state={r.get('state')} stopped={r.get('stopped')} ticks={r.get('ticks')} wrong={len(wrong)} first={[[w[i] - origin[i] for i in range(3)] for w in wrong[:4]]} roomTouched={len(others)}"}

    def terrain(self): return self.hall(SMALL, "terrainRuns", 80)

    def hall(self, size=HALL, counter="hallRuns", south=0):
        """Each run takes fresh ground: 40 blocks east of the last of its kind, the small one 80 south of the large."""
        self.arena()   # the fixture journals the player and lets the suite hand out blocks; the hall stands on real ground
        runs = self.history.get(counter, 0) if self.args.hall_index is None else self.args.hall_index
        if self.args.hall_index is None: self.history[counter] = runs + 1
        ox, oz = self.args.hall_at[0] + 40 * runs, self.args.hall_at[1] + south; cx, cz = ox + size[0] // 2, oz + size[2] // 2
        column = [[cx, y, cz] for y in range(110, 46, -1)]; found = self.blocks(column)
        ground = next((c[1] for c in column if found.get(tuple(c), AIR) != AIR and not any(k in found[tuple(c)].lower() for k in ("leaves", "log", "plant", "tallgrass", "flower", "snow_layer"))), 63)
        origin = [ox, ground, oz]; cells, door = hall_cells(size)
        # every slot the fixture loadout leaves empty (its tools stay): 28 stacks for 1,728 cells
        for slot in (4, 6, 7, 8, 9, 10, 11, *range(15, 36)): self.s.call(bs.FIX + ".set_stack", slot=slot, id=DIRT, meta=0, count=64)
        self.stand([cx + .5, ground + 1, cz + .5])
        print("hall at", origin, "inventory", [(i["slot"], i["id"].split(":")[-1], i["count"]) for i in self.s.call(bs.FIX + ".status")["inventory"] if i["slot"] < 9 or "dirt" not in i["id"]], flush=True)
        t = time.monotonic(); sessions = []; r = call(work.mb_build, cells=cells, origin=origin, replace_existing=True, allow_break=True)
        sessions.append(r)
        while r.get("state") == "paused" and (r.get("stopped") or {}).get("reason") == "timeout" and len(sessions) < 6:
            r = call(work.mb_work_resume, job_id=r["jobId"]); sessions.append(r)
        wrong = self.wrong(origin, cells); gap = self.wrong(origin, [{"pos": p, "id": AIR} for p in door]); player = self.s.call("dev.replay.status")
        ticks = sum(int(s.get("ticks") or 0) for s in sessions); order = self.ledger(r.get("jobId", ""))
        row = {"origin": origin, "sessions": len(sessions), "ticks": ticks, "wallS": round(time.monotonic() - t, 1), "wrong": wrong[:16], "wrongCount": len(wrong),
               "cellsPerMinute": round(len(cells) * 1200 / ticks, 1) if ticks else None, "costs": [s.get("cost") for s in sessions],
               "order": build_order.metrics(order, ticks, [c for c in wrong]) if order else None, "receipt": r, "dead": player["dead"]}
        row["passed"] = r.get("state") == "succeeded" and not wrong and not gap and not player["dead"]
        row["why"] = (f"state={r.get('state')} stopped={r.get('stopped')} sessions={len(sessions)} ticks={ticks} cells/min={row['cellsPerMinute']} wrong={len(wrong)} "
                      f"tickNsMean={[(c or {}).get('tickNsMean') for c in row['costs']]} tickNsMax={[(c or {}).get('tickNsMax') for c in row['costs']]} order={row['order']}")
        return row

    def shell_case(self, name):
        row = self.trial(name); r = row.get("receipt") or {}
        return {**row, "why": f"{row['outcome']} state={r.get('state')} ticks={r.get('ticks')} placed={r.get('placed')} wrong={len(row.get('wrong') or [])}"}

    def run(self):
        all_ = {**{n: (lambda n=n: self.shell_case(n)) for n in bs.CASES},
                **{n: getattr(self, n) for n in ("staged", "unfinishable", "missing", "occupied", "timeout", "any_meta", "clear", "hidden", "buried", "lining", "terrain", "hall")}}
        names = [n for n in (self.args.only or [n for n in all_ if n != "hall"]) if n not in (self.args.skip or [])]
        try: past = json.loads(OUT.read_text())
        except (OSError, ValueError): past = {}
        self.history = past if isinstance(past.get("runs"), list) else {"runs": []}
        rows = {}
        try:
            self.setup()
            for name in names:
                print(f"== {name}", flush=True); t = time.monotonic()
                try: row = all_[name]()
                except Exception as e: row = {"passed": False, "why": f"error {type(e).__name__}: {e}"}
                row["wallS"] = round(time.monotonic() - t, 1); rows[name] = row
                print(f"{name:14} {'PASS' if row['passed'] else 'FAIL'} {row['wallS']:7.1f}s  {row['why']}", flush=True)
        finally:
            self.teardown()
            before = {n: r["passed"] for run in self.history["runs"] for n, r in run["rows"].items()}   # the latest verdict of each
            self.history["runs"].append({"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "commit": self.args.commit,
                                         "rows": {n: {"passed": r["passed"], "why": r["why"], "wallS": r.get("wallS")} for n, r in rows.items()}})
            self.history["last"] = rows
            OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(self.history, indent=1, default=str))
            regressed = [n for n, r in rows.items() if not r["passed"] and before.get(n)]
            print(f"\n{sum(r['passed'] for r in rows.values())}/{len(rows)} passed at {self.args.commit}; regressions: {regressed or 'none'}", flush=True)
            self.evidence["ok"] = bool(rows) and all(r["passed"] for r in rows.values())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", action="append"); ap.add_argument("--skip", action="append")
    ap.add_argument("--keep", action="store_true", help="leave the arena and the journalled player in place")
    ap.add_argument("--server-host", default="127.0.0.1")
    ap.add_argument("--hall-at", type=int, nargs=2, default=[200, -60], metavar=("X", "Z"), help="low corner of the first hall; each run builds 40 blocks further east")
    ap.add_argument("--hall-index", type=int, help="build at this site again instead of a new one")
    args = ap.parse_args(); args.seed, args.trials, args.case = 1, 1, None
    args.commit = os.environ.get("MB_COMMIT") or "unknown"
    suite = Suite(args); suite.run()
    return 0 if suite.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
