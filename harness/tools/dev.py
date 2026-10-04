# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Supplied-materials test runs: the model asks the operator for raw materials instead of gathering them.

A request is a file in ``$MODBENCH_OUTBOX/requests`` (the same folder-as-mailbox pattern as the deploy supervisor,
harness/launcher/deploy.py); the operator answers with ``<id>.result.json`` (harness/launcher/supply.py). The
tool touches neither the game nor its clock. Branch ``dev-requests`` only: a real run has no such tool.
"""
from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path

from mbtool import tool

REQUESTS = Path(os.environ.get("MODBENCH_OUTBOX") or Path(__file__).resolve().parents[2] / ".runtime" / "outbox") / "requests"


@tool(lane="read", name="mb_request")
def request(items: list | None = None, reason: str = "", wait_seconds: int = 600, request: str = "") -> dict:
    """Ask the operator for raw materials: anything you would otherwise take from the world by mining, digging,
    chopping, farming, hunting or scooping. items is a list of {"id": registry name, "meta": damage value,
    "count": n}, named the way the game names them (mb_recipes and your inventory show the ids). Nothing made is
    supplied: no crafted, smelted or processed item. The operator reads the request and answers in a minute or
    two of real time; the game clock is left as you set it. Granted items go into your inventory, and what does
    not fit drops at your feet. Returns what was given and what was refused, with the reason. If the answer has
    not come within wait_seconds, returns status "pending": call again with request=<id> to go on waiting."""
    REQUESTS.mkdir(parents=True, exist_ok=True)
    if request: rid = request
    else:
        wanted = [{"id": str(i["id"]), "meta": int(i.get("meta") or 0), "count": int(i["count"])} for i in items or []]
        if not wanted or any(i["count"] < 1 for i in wanted): raise ValueError('items: a list of {"id", "meta", "count"} with count >= 1')
        rid = uuid.uuid4().hex[:8]; tmp = REQUESTS / f"{rid}.tmp"
        tmp.write_text(json.dumps({"request": rid, "time": time.time(), "items": wanted, "reason": reason}), encoding="utf-8"); tmp.replace(REQUESTS / f"{rid}.json")
    if not (REQUESTS / f"{rid}.json").exists(): raise ValueError(f"no request {rid}")
    result = REQUESTS / f"{rid}.result.json"; end = time.monotonic() + max(0, min(int(wait_seconds), 1000))
    while not result.exists():
        if time.monotonic() >= end: return {"request": rid, "status": "pending"}
        time.sleep(1)
    return json.loads(result.read_text(encoding="utf-8"))
