# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Copy into harness/tools/ (a non-underscore name) and adapt during a run; it reloads on the next call.

This example loads caller-chosen slots in any ordinary inventory layout. It does
not select a recipe, press a start button, claim crafting completion or repeat on
failure. Those decisions belong to the higher-level adapter.
"""
from mbtool import tool, kernel
from mbtools_gtnh.inventory import ContainerSession


@tool(rung=3, coverage=["inventory", "machine"])
def mb_load_inputs(moves: list[dict], destination_policy: str = "passive") -> dict:
    """Load explicit [{source, destination, count}] using currently observed slot indices.
    Errors retain completed and partial receipts. Check the machine's own output,
    progress indicator or other postcondition after loading; don't replay blindly.
    """
    if not 1 <= len(moves) <= 32 or destination_policy not in ("passive", "consuming"):
        raise ValueError("1..32 moves and a passive/consuming policy required")
    for move in moves:
        if set(move) != {"source", "destination", "count"} or any(type(v) is not int for v in move.values()):
            raise ValueError("each move requires integer source, destination and count")
        if not 1 <= move["count"] <= 64 or min(move["source"], move["destination"]) < 0:
            raise ValueError("invalid slot index or quantity")
    session = ContainerSession(kernel())
    for move in moves:
        session.transfer(move["source"], [move["destination"]], move["count"], destination_policy)
    return {"loaded": True, "receipts": session.receipts, "container": session.observe()}
