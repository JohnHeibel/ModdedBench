# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Editable example: combine threats, player state and an exact inventory selector.

Arm with file=<this absolute path>, queries={player:{method:obs.player},
nearby:{method:obs.entities,params:{radius:8}}}, effects=[notify,cancel].
Adapt TARGET/COUNT and the predicate for the procedure being monitored.
"""

TARGET = {"id": "minecraft:obsidian", "meta": 0}
COUNT = 4


def evaluate(context):
    player = context.values["player"]
    entities = context.values["nearby"]["entities"]
    stacks = context.read("obs.find", selector=TARGET, scope="player")["matches"]
    collected = sum(match["stack"]["count"] for match in stacks)
    threatened = player["health"] < 8 and any(e["hostile"] for e in entities)
    context.state["samples"] = context.state.get("samples", 0) + 1
    return {
        "match": threatened or collected >= COUNT,
        "payload": {"threatened": threatened, "collected": collected,
                    "samples": context.state["samples"]},
    }
