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
Natural spawning is off, so the box holds what the scene makes; health regenerates as in any game. setup leaves the
world paused (the guard clock's pause): the run starts when the model first lets it run.
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

FORTRESS_KIT = [item for item in KIT if item[0] != "water_bucket"]  # as one comes to the Nether: water boils there
NB, FENCE = "minecraft:nether_brick", "minecraft:nether_brick_fence"
# arena: the box (x, z its corner); boxes: [id, (x, y, z), (x, y, z), spawns?] with y from the floor, filled in order;
# mobs: [registry name, (x, y, z), count?]; kit: the inventory, KIT if none; at: the player's start, yaw its facing.
SCENES = {
    "blaze": dict(
        task="Farm 8 blaze rods",
        about="You stand in a closed nether-brick room in the sky, 25 blocks square and 10 high, with a blaze spawner in the "
              "middle of its floor. You have iron armour on, and in your inventory weapons, tools, blocks, food, a water bucket "
              "and torches. No other player is in the world.",
        goal=("minecraft:blaze_rod", 8),
        arena=dict(x=1024, z=1024, width=25, depth=25, height=10),
        boxes=[("minecraft:mob_spawner", (1036, 0, 1036), (1036, 0, 1036), "Blaze")],
        at=(1027.5, 0, 1036.5), yaw=-90, difficulty=1),
    # A fortress bridge and its blaze spawner as the game builds them (the Straight piece and the Throne), over a lava sea
    # 15 blocks down, with a gap of open air between the start and the bridge: where the dragon runs lost their footing.
    "fortress": dict(
        task="Farm 8 blaze rods",
        about="You are in a closed box in the sky, 64 blocks square and 40 high, built as a piece of a Nether fortress: a "
              "lava sea fills its floor. You stand on a netherrack outcrop 15 blocks above the lava, facing east. Eight blocks "
              "east of its edge, across open air over the lava, a nether-brick bridge 5 wide with low walls on both sides runs "
              "19 blocks east to a stepped platform with a blaze spawner on it. A netherrack cliff rises along the north wall. "
              "Two ghasts fly in the box and two zombie pigmen stand on the bridge. You have iron armour on, and in your "
              "inventory weapons, tools, blocks, food and torches. No other player is in the world.",
        goal=("minecraft:blaze_rod", 8),
        arena=dict(x=1004, z=992, width=64, depth=64, height=40),
        boxes=[("minecraft:lava", (1004, 0, 992), (1067, 1, 1055)),
               ("minecraft:netherrack", (1004, 2, 992), (1067, 30, 999)),              # the cliff
               ("minecraft:netherrack", (1004, 2, 1017), (1010, 16, 1029)),            # the outcrop
               (NB, (1019, 2, 1021), (1021, 14, 1025)), (NB, (1035, 2, 1021), (1037, 14, 1025)),  # the bridge's piers
               (NB, (1019, 15, 1021), (1037, 16, 1025)),                                # its deck
               (NB, (1019, 17, 1021), (1037, 17, 1021)), (NB, (1019, 17, 1025), (1037, 17, 1025)),  # its walls
               (NB, (1038, 2, 1020), (1046, 14, 1026)),                                 # the throne's footing
               (NB, (1038, 15, 1021), (1045, 16, 1025)), (NB, (1039, 17, 1021), (1045, 17, 1025)),
               (NB, (1040, 18, 1021), (1045, 18, 1025)), (NB, (1041, 19, 1021), (1045, 19, 1025)),  # its steps
               (NB, (1038, 17, 1021), (1040, 19, 1021)), (NB, (1038, 17, 1025), (1040, 19, 1025)),
               (NB, (1040, 20, 1021), (1041, 20, 1021)), (NB, (1040, 20, 1025), (1041, 20, 1025)),
               (NB, (1041, 20, 1020), (1046, 20, 1020)), (NB, (1041, 20, 1026), (1046, 20, 1026)), (NB, (1046, 20, 1021), (1046, 20, 1025)),
               (FENCE, (1041, 21, 1021), (1041, 21, 1021)), (FENCE, (1041, 21, 1025), (1041, 21, 1025)),
               (FENCE, (1041, 21, 1020), (1046, 21, 1020)), (FENCE, (1041, 21, 1026), (1046, 21, 1026)),
               (FENCE, (1046, 21, 1021), (1046, 22, 1025)), (FENCE, (1046, 23, 1022), (1046, 23, 1024)),
               ("minecraft:mob_spawner", (1043, 20, 1023), (1043, 20, 1023), "Blaze")],
        mobs=[("Ghast", (1030, 30, 1040)), ("Ghast", (1050, 28, 1005)), ("PigZombie", (1027.5, 17, 1022.5)), ("PigZombie", (1031.5, 17, 1023.5))],
        kit=FORTRESS_KIT, at=(1008.5, 17, 1023.5), yaw=-90, difficulty=1),
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
    from kernel import BridgeError, Kernel
    client, until = Kernel(timeout=120), time.monotonic() + 20
    client.call("time.configure", healthDrop=False, healthBelow=-1, airBelow=-1, foodBelow=-1, burning=False, threatWithin=-1,
                actionFailed=False, pauseOnDisconnect=False)  # no guard of a run before this one; the model sets its own
    if client.call("time.status")["state"].get("paused"): client.call("time.resume")
    while server.call(FIX + ".status")["player"]["dead"]:  # a body left dead (a course's last scene) respawns first
        if time.monotonic() > until: raise RuntimeError("the player did not respawn")
        try: client.call("gui.button", index=0)  # the death screen's Respawn
        except BridgeError: pass
        time.sleep(1)
    client.call("time.pause")  # the scene is handed over still: the model looks before anything in it moves
    server.call(FIX + ".clear")
    server.call(FIX + ".rules", rules=RULES, difficulty=scene["difficulty"])
    server.call(FIX + ".arena", **scene["arena"])
    for block, (x, y, z), (x2, y2, z2), *spawns in scene["boxes"]:  # one call a box: the largest is under the fixture's cap
        server.call(FIX + ".fill", boxes=[{"id": block, "from": [x, FLOOR + y, z], "to": [x2, FLOOR + y2, z2], **({"spawns": spawns[0]} if spawns else {})}])
    x, y, z = scene["at"]
    stacks = [{"slot": i, "id": f"minecraft:{item}", "count": count} for i, (item, count) in enumerate(scene.get("kit", KIT))]
    server.call(FIX + ".reset", empty=True, armor=ARMOR, stacks=stacks, at=[x, FLOOR + y, z], yaw=scene["yaw"], effects=SIGHT)
    server.call(FIX + ".clear")
    for kind, (x, y, z), *count in scene.get("mobs", []):
        server.call(FIX + ".summon", type=kind, x=x, y=FLOOR + y, z=z, count=count[0] if count else 1)
    return server.call(FIX + ".status", radius=40)["player"]


def watch(server, name: str, minutes: float) -> dict:
    scene, out = SCENES[name], ROOT / ".runtime" / "evidence" / f"combat-scenario-{name}.json"
    item, count = scene["goal"]
    ev = {"scene": name, "task": scene["task"], "started": time.strftime("%Y-%m-%dT%H:%M:%S"), "samples": []}
    start, low, deaths, was_dead = time.time(), 20.0, 0, False
    while time.time() - start < minutes * 60:
        s = server.call(FIX + ".status", radius=64)
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
