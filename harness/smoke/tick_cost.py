# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""What a case cost the game thread, judged in one place for every live suite.

A job's receipt carries `cost` (Cost.status(): tickNsMax, tickNsMean, ticks). A case whose worst tick or mean tick is
over its suite's limit fails, and the figure is printed either way. No game is needed to import or test this.

The first job after a client starts pays for class loading and the JIT: `--warm-up` prints the first costed case's
figures and does not judge them.

PROVISIONAL limits. They come from the receipts on record on one machine (2026-10-05: 21 build receipts, 91 walks),
not from a calibration run: builds had a worst tick of 42.9 ms and one of 98.3, means up to 1.43 ms; walks a worst
tick of 20.6 ms and one of 43.4 on a job's first tick, means up to 1.0 ms (2.6 in an earlier live run). Set them again
from the suites' own printed figures once the audit of the paths in TickBudgetTest's findings is done.
"""
from __future__ import annotations

LIMITS = {"build": {"tickMsMax": 120.0, "tickMsMean": 3.0}, "walk": {"tickMsMax": 60.0, "tickMsMean": 5.0}}   # PROVISIONAL
MEAN_TICKS = 40   # a mean over fewer ticks is mostly the job's first tick (Cost.MEAN_TICKS)


def costs(result, depth: int = 5) -> list[dict]:
    """Every cost map in a case's row: its receipt's, each session's of a resumed job, a comparison walk's."""
    if depth < 0: return []
    if isinstance(result, dict):
        own = [result] if isinstance(result.get("tickNsMax"), (int, float)) else []
        return own + [c for v in result.values() for c in costs(v, depth - 1)]
    if isinstance(result, (list, tuple)): return [c for v in result for c in costs(v, depth - 1)]
    return []


def figures(result) -> dict | None:
    """The worst tick of any job in the row and the worst mean of those that ran MEAN_TICKS or more, in ms; None when the row has no cost."""
    found = costs(result)
    if not found: return None
    means = [c["tickNsMean"] for c in found if (c.get("ticks") or 0) >= MEAN_TICKS and isinstance(c.get("tickNsMean"), (int, float))]
    return {"tickMsMax": round(max(c["tickNsMax"] for c in found) / 1e6, 1), "tickMsMean": round(max(means) / 1e6, 2) if means else None}


def over(found: dict | None, limits: dict) -> dict:
    """The figures over their limit."""
    return {k: v for k, v in (found or {}).items() if v is not None and v > limits[k]}


def argument(parser):
    parser.add_argument("--warm-up", action="store_true", help="the client has just started: print the first costed case's game-thread cost and do not judge it")


class Gate:
    """One suite's run: `gate(name, row)` after each case. A case over the limit has row[key] made false."""
    def __init__(self, kind: str, warm_up: bool = False, out=print):
        self.kind, self.limits, self.warm, self.out = kind, LIMITS[kind], bool(warm_up), out

    def __call__(self, name: str, row: dict, key: str = "passed") -> str | None:
        found = figures(row)
        if found is None: return None   # no job ran, or it died before a receipt
        warm, self.warm = self.warm, False
        bad = over(found, self.limits)
        row["tickCost"] = {**found, "limits": self.limits, **({"warmUp": True} if warm else {}), **({"over": bad} if bad else {})}
        said = (f"tick cost {name}: worst {found['tickMsMax']} ms (limit {self.limits['tickMsMax']:g}), "
                f"mean {found['tickMsMean'] if found['tickMsMean'] is not None else 'n/a'} ms (limit {self.limits['tickMsMean']:g})")
        if not bad: self.out(said); return None
        if warm: self.out(said + " OVER, warm-up: not judged"); return None
        why = "game thread over budget: " + ", ".join(f"{k} {v} ms > {self.limits[k]:g}" for k, v in bad.items())
        row[key] = "fail" if isinstance(row.get(key), str) else False
        if isinstance(row.get("failures"), list): row["failures"].append(why)
        if isinstance(row.get("why"), str): row["why"] = f"{why}; {row['why']}"
        self.out(said + " OVER"); return why
