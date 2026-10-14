# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""A combat scenario with the whole harness: the model and every tool against a scene set from outside, played fairly.

Between the course (one tool call, judged) and an open game. The scene is built in the course's closed box in the sky,
the player is given a kit, and the run's brief is a task line on top of the standing brief, with no word on how to
fight. The scene is set through the server's dev fixtures, which only the throwaway stack has and which the model's
agent cannot reach (the server bridge listens on the server's own loopback). On the mbcombat stack:

    python harness/smoke/combat_scenario.py brief blaze > .runtime/combat/brief/PROMPT.md
    bash harness/smoke/mbtest.sh harness/smoke/combat_scenario.py setup blaze
    (start the agent loop on the stack, as the console's agent.start does)
    bash harness/smoke/mbtest.sh harness/smoke/combat_scenario.py watch blaze --minutes 40

watch reads the server every 5 seconds (goal items held, health, deaths, the mobs in the box) into
.runtime/evidence/combat-scenario-<name>.json and returns when the goal is held, at the first death, or at the cap.
Natural spawning is off, so the box holds what the scene makes; health regenerates as in any game.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parents[2]
FIX, FLOOR = "dev.combat", 176
ARMOR = [{"slot": i, "id": f"minecraft:iron_{part}"} for i, part in enumerate(("boots", "leggings", "chestplate", "helmet"))]
KIT = [("iron_sword", 1), ("bow", 1), ("iron_pickaxe", 1), ("iron_shovel", 1), ("iron_axe", 1), ("cobblestone", 64), ("cobblestone", 64),
       ("cobblestone", 64), ("cooked_beef", 32), ("torch", 32), ("water_bucket", 1), ("arrow", 64)]
SIGHT = [{"id": "nightVision", "seconds": 100000}]
RULES = {"doMobSpawning": False, "mobGriefing": True, "naturalRegeneration": True, "keepInventory": False}

# arena: the box (x, z its corner); blocks: [id, (x, y, z), spawns?] with y from the floor; at: the player's start, yaw its facing.
SCENES = {
    "blaze": dict(
        task="Farm 8 blaze rods",
        about="You stand in a closed nether-brick room in the sky, 25 blocks square and 10 high, with a blaze spawner in the "
              "middle of its floor. You have iron armour on, and in your inventory weapons, tools, blocks, food, a water bucket "
              "and torches. No other player is in the world.",
        goal=("minecraft:blaze_rod", 8),
        arena=dict(x=1024, z=1024, width=25, depth=25, height=10),
        blocks=[("minecraft:mob_spawner", (1036, 0, 1036), "Blaze")],
        at=(1027.5, 0, 1036.5), yaw=-90, difficulty=1),
}


def brief(name: str) -> str:
    """The standing brief with this scenario's task on top, as the console fills it for a run."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("console", ROOT / "harness" / "console" / "console.py")
    console = importlib.util.module_from_spec(spec); spec.loader.exec_module(console)
    scene = SCENES[name]
    body = console.fill_prompt((ROOT / "PROMPT.md").read_text(encoding="utf-8"), scene["task"], "none: a combat scenario, no quest book")
    item, count = scene["goal"]
    top = (f"# This run: a combat scenario in plain Minecraft 1.7.10. {scene['task']}.\n\n"
           "**Read this section first. Where it and the rest of this file disagree, this section wins.**\n\n"
           "This run is not GT New Horizons. You are in vanilla Minecraft 1.7.10 in survival: Forge with only the\n"
           "ModdedBench mods, so the same harness and the same `mb_*` tools, and nothing else added to the game. There is\n"
           "no quest book and no NEI recipe catalogue. The rest of this file is the standing brief for GT New Horizons, kept\n"
           "whole because the harness, the tools, the clock and its guards, the notes and the goal stack work the same here.\n"
           "Whatever it says about quests, chapters, the quest target, the pack's mods, factory scale and automation is about\n"
           "another game. Use what fits and leave the rest.\n\n"
           f"{scene['about']}\n\n"
           f"**Your mission: hold {count} of `{item}` in your inventory, as fast as you can.** Completion means they are in\n"
           "your inventory and you have observed that they are. Print `MISSION COMPLETE` on its own line only then. Wherever\n"
           "the text below says the session ends when the target quest is claimed and verified, read this instead.\n\n")
    return top + body


def setup(server, name: str) -> dict:
    scene = SCENES[name]
    server.call(FIX + ".clear")
    server.call(FIX + ".rules", rules=RULES, difficulty=scene["difficulty"])
    server.call(FIX + ".arena", **scene["arena"])
    for block, (x, y, z), *spawns in scene["blocks"]:
        server.call(FIX + ".fill", boxes=[{"id": block, "from": [x, FLOOR + y, z], "to": [x, FLOOR + y, z], **({"spawns": spawns[0]} if spawns else {})}])
    x, y, z = scene["at"]
    stacks = [{"slot": i, "id": f"minecraft:{item}", "count": count} for i, (item, count) in enumerate(KIT)]
    server.call(FIX + ".reset", empty=True, armor=ARMOR, stacks=stacks, at=[x, FLOOR + y, z], yaw=scene["yaw"], effects=SIGHT)
    server.call(FIX + ".clear")
    return server.call(FIX + ".status", radius=40)["player"]


def watch(server, name: str, minutes: float) -> dict:
    scene, out = SCENES[name], ROOT / ".runtime" / "evidence" / f"combat-scenario-{name}.json"
    item, count = scene["goal"]
    ev = {"scene": name, "task": scene["task"], "started": time.strftime("%Y-%m-%dT%H:%M:%S"), "samples": []}
    start, low, deaths, was_dead = time.time(), 20.0, 0, False
    while time.time() - start < minutes * 60:
        s = server.call(FIX + ".status", radius=40)
        p = s["player"]; held = sum(i["count"] for i in p["inventory"] if i["id"] == item)
        kinds: dict[str, int] = {}
        for e in s["entities"]:
            if e.get("health") is not None and e.get("type") != "Player": kinds[e["type"]] = kinds.get(e["type"], 0) + 1
        dead = p["dead"]; deaths += dead and not was_dead; was_dead = dead; low = min(low, p["health"])
        ev["samples"].append([round(time.time() - start), held, p["health"], dead, kinds])
        ev.update(held=held, deaths=deaths, lowestHealth=low, minutes=round((time.time() - start) / 60, 1))
        out.parent.mkdir(parents=True, exist_ok=True); out.write_text(json.dumps(ev))
        if held >= count: ev["result"] = "goal"; break
        if deaths: ev["result"] = "died"; break
        time.sleep(5)
    else: ev["result"] = "time"
    out.write_text(json.dumps(ev)); return {k: v for k, v in ev.items() if k != "samples"}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("step", choices=("brief", "setup", "watch")); ap.add_argument("scene", choices=sorted(SCENES))
    ap.add_argument("--minutes", type=float, default=40)
    a = ap.parse_args()
    if a.step == "brief": sys.stdout.buffer.write(brief(a.scene).encode("utf-8")); return  # as written: no newline or code-page translation
    import movement_course as mc
    server = mc.server_kernel(120)
    print(json.dumps(setup(server, a.scene) if a.step == "setup" else watch(server, a.scene, a.minutes)))


if __name__ == "__main__":
    main()
