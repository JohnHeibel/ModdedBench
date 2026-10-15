# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Combat, in the game: one call of the model's own tool (mb_fight) against one scene, judged from the server.

Each trial rebuilds a closed dark box, builds the scene's ground in it (pillars, a fortress corridor with a lava pool,
a two-high cave, a ledge, a lava trench, a deep pool, a wall with a doorway, rough ground; or none), readies the player
(full health and food, iron armour, an iron sword in slot 0, a bow in slot 1, a stack of arrows), summons the scene's
mobs by registry name ahead, beside or behind, and calls mb_fight with the arguments a first attempt would use: once,
and for a pack once with swarm=True, or with --one-by-one once per mob as a model naming each one would, the mob the
last receipt named or else the nearest still standing first. The receipt is what the model is told. The server is what
happened, watched five times a second while the fights run: which of the summoned mobs died (seen at no health), which
left the world alive (a creeper's blast), how much health the rest lost, the bow's wear, the arrows spent, the player's
health, and how long two or more of them stood within 3 blocks of the player (crowdedS). A trial is won when every
summoned mob died, the player did not, and the tool said it had succeeded (one by one: however many fights it took,
each one that ended short being a decision the model would have had to make).
The adversarial scenes go further: a hut with a shut door and zombies camped outside, corridors and corners, towers,
a walled yard, a causeway, lava behind the player, a dead end; witches, splitting slimes, a creeper arriving late. A
scene may set the difficulty, start the player elsewhere, summon a second wave partway through (later), go on after a
first call that left mobs standing one mob at a time with other arguments (then: the model read the receipt), and name
bystanders (spare: neutral mobs) that a win must leave at full health.

    MB_CLIENT_INSTANCE=<instance>/.minecraft bash harness/smoke/mbtest.sh harness/smoke/combat_course.py [--case NAME ...] [--trials 3] [--guards] [--list]

Guards are off unless --guards, which sets the guards a run plays under (threat within 12, health drop, health below 8,
burning, action failed) and starts the fight from the threat pause with resume, as the model does. A guard's pause
holds the fight and asks for a decision: the course answers "carry on" every time and counts the asks (pauses), and
the row says which guard, if any, the world was left paused by. Natural regeneration, mob spawning and mob griefing are off for the course, so that
health lost is damage taken and the box stays whole; the rules are put back afterwards.
Needs the throwaway mbtest stack with the dev fixtures (dev.combat.*), any pack.

Evidence: .runtime/evidence/combat-course.json, every trial's positions in combat-traces.json, and a table on stdout.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import re
import statistics
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import movement_course as mc  # noqa: E402  (Kernel, the server bridge, rejoin)
from movement_course import BridgeError, Course, work  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "combat-course.json"
TRACES = OUT.with_name("combat-traces.json")
FIX = "dev.combat"
ARENA = dict(x=1024, z=1024, width=48, depth=48, height=20)
FLOOR = 176
START = (ARENA["x"] + 10.5, FLOOR, ARENA["z"] + 24.5)   # facing +x, 37 blocks of floor ahead
SWORD, BOW, ARROWS = 0, 1, 9
ARMOR = [{"slot": i, "id": f"minecraft:iron_{part}"} for i, part in enumerate(("boots", "leggings", "chestplate", "helmet"))]
STACKS = [{"slot": SWORD, "id": "minecraft:iron_sword"}, {"slot": BOW, "id": "minecraft:bow"}, {"slot": ARROWS, "id": "minecraft:arrow", "count": 64},
          {"slot": 2, "id": "minecraft:cobblestone", "count": 64}]   # for a fight that may build its way
RULES = {"doMobSpawning": False, "mobGriefing": False, "naturalRegeneration": False, "keepInventory": True}
SIGHT = [{"id": "nightVision", "seconds": 100000}]   # the owner's call: every scene seen as in daylight; the mobs' own light rules still apply
GUARDS = [mc.rule("health_drop"), mc.rule("health_below", 8), mc.rule("burning"), mc.rule("hostile_within", 12), mc.rule("action_failed")]  # --guards
X0, Z0 = int(START[0]), int(START[2])   # the block the player starts on: ground is placed from it, up 0 the first air
STONE, BRICK, AIR, LAVA, DOOR = "minecraft:cobblestone", "minecraft:nether_brick", "minecraft:air", "minecraft:lava", "minecraft:wooden_door"


def sunk(liquid: str, a0: int, s0: int, a1: int, s1: int, deep: int = 1) -> list:
    """A pool let into the floor, its own floor and sides built below the box's."""
    return [(BRICK, (a0 - 1, -1 - deep, s0 - 1), (a1 + 1, -2, s1 + 1)), (liquid, (a0, -deep, s0), (a1, -1, s1))]


def pillars() -> list:
    return [(STONE, (a, 0, s), (a, 19, s)) for row, a in enumerate(range(3, 20, 4)) for s in range(-8 + 2 * (row % 2), 11, 4)]


def rough(keep: set) -> list:
    """Two-block tiles raised zero to two blocks, a few sunk one: the player's tile and the mobs' are left flat."""
    rng, out = random.Random(7), []
    for a in range(-2, 30, 2):
        for s in range(-12, 12, 2):
            up = rng.choice((0, 0, 1, 1, 2))
            if any(a <= x <= a + 1 and s <= z <= s + 1 for x, z in keep): continue
            if up: out.append((STONE, (a, 0, s), (a + 1, up - 1, s + 1)))
            elif rng.random() < .15: out += [(BRICK, (a, -2, s), (a + 1, -2, s + 1)), ("minecraft:air", (a, -1, s), (a + 1, -1, s + 1))]
    return out


def hut(front: int, door: bool = False, windows: tuple = ()) -> list:
    """A roofed hut five blocks square and three high inside, its front wall `front` blocks ahead with a doorway in the
    middle (a shut wooden door: lower half facing 0, upper half 8) and a window one block up at each side offset given."""
    return [(STONE, (front - 6, 0, -3), (front, 3, 3)), (AIR, (front - 5, 0, -2), (front - 1, 2, 2)), (AIR, (front, 0, 0), (front, 1, 0)),
            *([(DOOR, (front, 0, 0), (front, 0, 0), 0), (DOOR, (front, 1, 0), (front, 1, 0), 8)] if door else []),
            *[(AIR, (front, 1, s), (front, 1, s)) for s in windows]]


def tower(ahead: int, side: int, high: int = 4) -> list:
    """A 3x3 tower with no way up and a one-high parapet: a mob in the middle of its top stands in cover to the waist."""
    return [(STONE, (ahead - 1, 0, side - 1), (ahead + 1, high, side + 1)), (AIR, (ahead, high, side), (ahead, high, side))]


NIGHT =[("Zombie", 10, 0, 1, -3), ("Zombie", 12, 0, 1, 3), ("Skeleton", 16, 0, 1), ("Spider", 9, 0, 1, 6), ("Creeper", 14, 0, 1, -6)]
FORT = [(BRICK, (-10, 0, -24), (37, 7, -2)), (BRICK, (-10, 0, 2), (37, 7, 23)), (BRICK, (-10, 0, -1), (-3, 7, 1)), (BRICK, (-2, 4, -1), (21, 7, 1)),
        ("minecraft:air", (22, 0, -7), (34, 7, 7)), (BRICK, (35, 0, -7), (37, 7, 7)), *sunk("minecraft:lava", 29, 3, 34, 6)]
PACK = dict(pack=True, weapon_slot=SWORD)
# An enderman teleports onto the first floor under a spot up to 32 off each way and 32 up or down, and an angry one far off
# hops back 16 at a time the same way. From the box in the sky that is the real ground 90 blocks under (or the roof), and it
# never gets back. A box 64 across and 40 high, centred on where they stand, over 32 blocks of floor, keeps every landing on
# the box's floor as open ground would: a spot under the floor is in stone, and the game tries again.
TELEPORTS = dict(arena=dict(x=X0 - 20, z=Z0 - 32, width=64, depth=64, height=40),
                 ground=[(BRICK, (-20, top - 7, -32), (43, top, 31)) for top in (-2, -10, -18, -26)])
BURNING = [("minecraft:netherrack", (-2, -1, -2), (2, -1, 2)), ("minecraft:fire", (-2, 0, -2), (2, 0, 2))]
# mobs: (registry name, blocks ahead, blocks up, count, blocks to the side?, spread?): a group lies along the side axis.
# fight: mb_fight's arguments; named adds the first mob's entity_id, pack is swarm=True or, --one-by-one, a fight a mob.
# ground: (block, (ahead, up, side), (ahead, up, side), meta?) boxes from the start block, corners included, placed in order; arena: the box's size.
# spare: bystanders, summoned as mobs are, that a win leaves at full health; later: [(seconds into the fight, mob)]; then: the
# arguments a fight a mob goes on with, nearest first, if the first call left any standing; at: the player's start (ahead, up, side);
# difficulty: 1..3 for the trial, set before the summons (a mob's gear and effects are rolled at spawn); settle: seconds between the
# summons and the fight, the player looking straight ahead.
CASES: dict[str, dict] = {
    "melee_zombie": dict(mobs=[("Zombie", 8, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_skeleton": dict(mobs=[("Skeleton", 12, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_spider": dict(mobs=[("Spider", 8, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_cave_spider": dict(mobs=[("CaveSpider", 8, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_creeper": dict(mobs=[("Creeper", 8, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_enderman": dict(TELEPORTS, mobs=[("Enderman", 8, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "melee_blaze": dict(mobs=[("Blaze", 10, 0, 1)], fight=dict(named=True, weapon_slot=SWORD)),
    "hold_zombie": dict(mobs=[("Zombie", 12, 0, 1)], fight=dict(hold=True, weapon_slot=SWORD)),
    "swarm_zombies": dict(mobs=[("Zombie", 12, 0, 3)], fight=dict(swarm=True, weapon_slot=SWORD)),
    "swarm_silverfish": dict(mobs=[("Silverfish", 6, 0, 6)], fight=dict(swarm=True, weapon_slot=SWORD)),
    "ranged_zombie": dict(mobs=[("Zombie", 20, 0, 1)], fight=dict(named=True, weapon_slot=BOW, ranged={"meleeSlot": SWORD})),
    "ranged_skeleton": dict(mobs=[("Skeleton", 16, 0, 1)], fight=dict(named=True, weapon_slot=BOW, ranged=True)),
    "ranged_blaze": dict(mobs=[("Blaze", 16, 0, 1)], fight=dict(named=True, weapon_slot=BOW, ranged=True)),
    "ranged_ghast": dict(mobs=[("Ghast", 24, 8, 1)], fight=dict(named=True, weapon_slot=BOW, ranged=True)),
    # A bow fight that runs out mid-draw (a draw is 20 ticks), then goes on: one that ends with the bow drawn must not loose it unaimed.
    "ranged_cut_short": dict(mobs=[("Zombie", 18, 0, 1)], fight=dict(named=True, weapon_slot=BOW, ranged=True, duration_ticks=12),
                             then=dict(weapon_slot=BOW, ranged=True)),
    # A ghast across a wider arena, 52 blocks off: a long shot at a mob that drifts, with its fireballs on the line of fire.
    "ranged_ghast_far": dict(arena=dict(width=64, height=24), mobs=[("Ghast", 50, 14, 1)], fight=dict(named=True, weapon_slot=BOW, ranged={"maxRange": 64})),
    "ranged_crystal": dict(mobs=[("EnderCrystal", 20, 6, 1)], fight=dict(named=True, weapon_slot=BOW, ranged=True)),
    # a firing slit: a wall 6 ahead with a gap one high at your feet; the mob just behind it (it cannot see you, so it stays)
    # shows its legs through the gap, not its eyes
    "ranged_slit": dict(ground=[(STONE, (6, 0, -24), (6, 19, 23)), ("minecraft:air", (6, 0, -2), (6, 0, 2))], mobs=[("Zombie", 8, 0, 1)],
                        fight=dict(named=True, hold=True, weapon_slot=BOW, ranged={"meleeSlot": SWORD})),
    # A crowd of neutral mobs across the line to the target: a shot into one would turn it, and its kind, on you.
    "ranged_past_bystander": dict(mobs=[("Blaze", 14, 0, 1)], spare=[("PigZombie", 6, 0, 5, 0, .8)],
                                  fight=dict(named=True, hold=True, weapon_slot=BOW, ranged={"meleeSlot": SWORD})),
    # packs, in the open
    "pack_night": dict(mobs=NIGHT, fight=PACK),
    "pack_creeper_inside": dict(mobs=[("Zombie", 10, 0, 3, 0, 3), ("Creeper", 11, 0, 1, 1.5)], fight=PACK),
    "pack_archers": dict(mobs=[("Skeleton", 16, 0, 2, 0, 8), ("Zombie", 8, 0, 1)], fight=PACK),
    "pack_blazes": dict(mobs=[("Blaze", 12, 1, 3, 0, 4)], fight=PACK),
    "pack_flank": dict(mobs=[("Zombie", 8, 0, 1), ("Zombie", -7, 0, 1), ("Spider", 0, 0, 1, 8)], fight=PACK),
    "swarm_cave_spiders": dict(mobs=[("CaveSpider", 6, 0, 4)], fight=dict(swarm=True, weapon_slot=SWORD)),
    # packs, on ground
    "ground_pillars": dict(ground=pillars(), mobs=[("Zombie", 14, 0, 2, 0, 6), ("Skeleton", 21, 0, 1, 1)], fight=PACK),
    "ground_fortress": dict(arena=dict(height=8), ground=FORT, mobs=[("Blaze", 27, 2, 3, 0, 4)], fight=PACK),
    "ground_low_cave": dict(arena=dict(height=4), ground=[(STONE, (-10, 2, -24), (37, 3, 23))],
                            mobs=[("CaveSpider", 8, 0, 2, 0, 3), ("Zombie", 12, 0, 1, 3), ("Spider", 10, 0, 1, -4)], fight=PACK),
    # a cliff 3 high across the whole arena with a skeleton on top: the way up is built, with blocks in hand, and the swing is with the sword
    "climb_place": dict(ground=[(STONE, (6, 0, -24), (20, 2, 23))], mobs=[("Skeleton", 10, 3, 1)], fight=dict(named=True, weapon_slot=SWORD, allow_place=True)),
    "ground_ledge": dict(ground=[(STONE, (12, 0, -6), (17, 2, 6)), (STONE, (10, 0, -1), (10, 0, 1)), (STONE, (11, 0, -1), (11, 1, 1))],
                         mobs=[("Skeleton", 15, 3, 2, 0, 6), ("Zombie", 7, 0, 1, 2)], fight=PACK),
    "ground_lava_trench": dict(ground=sunk("minecraft:lava", 5, -10, 6, -1) + sunk("minecraft:lava", 5, 1, 6, 10),
                               mobs=[("Zombie", 12, 0, 2, 0, 4), ("Creeper", 14, 0, 1, 5)], fight=PACK),
    "ground_pool": dict(ground=sunk("minecraft:water", 3, -6, 12, 6, deep=2), mobs=[("Zombie", 16, 0, 2, 0, 4), ("Skeleton", 18, 0, 1)], fight=PACK),
    "ground_doorway": dict(ground=[(STONE, (6, 0, -24), (6, 19, 23)), ("minecraft:air", (6, 0, 0), (6, 1, 0))],
                           mobs=[("Zombie", 12, 0, 2, 0, 4), ("Creeper", 14, 0, 1, 5), ("Skeleton", 16, 0, 1, -3)], fight=PACK),
    "ground_rough": dict(ground=rough({(0, 0)} | {(m[1], (m[4] if len(m) > 4 else 0) + d) for m in NIGHT for d in (-1, 0, 1)}), mobs=NIGHT, fight=PACK),
    # Adversarial: what a run meets at night, as a model would first call it. Kit for all: iron armour, iron sword, bow, 64
    # arrows. Each comment is why a competent human with that kit wins. All of it is dark (the box is closed).
    # Shut in a hut: open the door and take them one at a time in the doorway (or through the windows beside it).
    "base_door_shut": dict(ground=hut(3, door=True, windows=(-1, 1)), mobs=[("Zombie", 6, 0, 3, 0, 2), ("Zombie", -7, 0, 1)], fight=PACK),
    # The same on Hard: a zombie hits 4.5 (1.8 through iron), still one at a time in the doorway. Doors hold: mob griefing is off.
    "base_door_shut_hard": dict(ground=hut(3, door=True, windows=(-1, 1)), mobs=[("Zombie", 6, 0, 3, 0, 2), ("Zombie", -7, 0, 1)], fight=PACK, difficulty=3),
    # Standing in a 1-wide doorway: one zombie reaches at a time; the late creeper is knocked back out by each hit and dies in 4.
    "base_doorway_hold": dict(ground=hut(1), mobs=[("Zombie", 9, 0, 3, 0, 3)], later=[(7, ("Creeper", 13, 0, 1))], fight=dict(hold=True, weapon_slot=SWORD)),
    # A 1-wide, 2-high corridor, two each end: never more than one per side in reach; turn to whichever is hitting.
    "corridor_pincer": dict(ground=[(STONE, (-9, 0, -2), (9, 3, 2)), (AIR, (-9, 0, 0), (9, 1, 0))],
                            mobs=[("Zombie", 5, 0, 1), ("Zombie", 8, 0, 1), ("Zombie", -5, 0, 1), ("CaveSpider", -7, 0, 1)], fight=PACK),
    # The same on Normal: zombies hit 3 (1.2 through iron), the cave spider poisons for 7 s; still one per side.
    "corridor_pincer_normal": dict(ground=[(STONE, (-9, 0, -2), (9, 3, 2)), (AIR, (-9, 0, 0), (9, 1, 0))],
                                   mobs=[("Zombie", 5, 0, 1), ("Zombie", 8, 0, 1), ("Zombie", -5, 0, 1), ("CaveSpider", -7, 0, 1)], fight=PACK, difficulty=2),
    # An L-shaped 2-wide tunnel: fight at the corner; the creeper that comes round it later is heard, hit first, and backed off down the 10-block leg.
    "corner_creeper": dict(ground=[(STONE, (-3, 0, -3), (11, 3, 13)), (AIR, (-2, 0, -1), (9, 2, 0)), (AIR, (8, 0, 1), (9, 2, 12))],
                           mobs=[("Zombie", 8.5, 0, 2, 6, 2), ("Spider", 8.5, 0, 1, 10)], later=[(5, ("Creeper", 8.5, 0, 1, 11))], fight=PACK),
    # Three ahead, then two zombies and a skeleton from the flanks 6 s in: turn to them, one at a time, in the open. (The
    # skeleton is 12 to the side: at 18 it never took the player as its target and stood idle out of the fight.)
    "second_wave": dict(mobs=[("Zombie", 10, 0, 3, 0, 3)], later=[(6, ("Zombie", 4, 0, 2, -18, 3)), (6, ("Skeleton", 4, 0, 1, 12))], fight=PACK),
    # A witch behind two zombies: rush her (26 health, 5 hits) through or round them; her poison cannot kill.
    "witch_coven": dict(mobs=[("Witch", 12, 0, 1), ("Zombie", 8, 0, 2, 0, 4)], fight=PACK),
    # Slimes and a magma cube that split when killed: the small ones die in a hit or two, and the smallest slimes do no harm.
    "slimes_split": dict(mobs=[("Slime", 9, 0, 2, 0, 6), ("LavaSlime", 12, 0, 1)], fight=PACK),
    # Zombie pigmen among the zombies: hit only the green ones; pigmen stay neutral unless struck.
    "bystander_pigmen": dict(mobs=[("Zombie", 10, 0, 3, 0, 4)], spare=[("PigZombie", 6, 0, 2, 0, 6)], fight=PACK),
    # The same, the model saying what counts as hostile, since it can see the pigmen.
    "bystander_pigmen_named": dict(mobs=[("Zombie", 10, 0, 3, 0, 4)], spare=[("PigZombie", 6, 0, 2, 0, 6)], fight=dict(PACK, hostile=[{"type": "Zombie"}])),
    # An enderman wandering by a pack, hostiles named: fight the pack without looking it in the face.
    "bystander_enderman": dict(TELEPORTS, mobs=[("Zombie", 9, 0, 2, 0, 4), ("Skeleton", 14, 0, 1)], spare=[("Enderman", 6, 0, 1, -5)],
                               fight=dict(PACK, hostile=[{"type": "Zombie"}, {"type": "Skeleton"}])),
    # A walled yard, open above: wait in the middle and hit the spiders as they come over the wall and drop; the zombie comes by the door.
    "spider_yard": dict(ground=[(STONE, (-5, 0, -5), (5, 3, 5)), (AIR, (-4, 0, -4), (4, 3, 4)), (AIR, (5, 0, 0), (5, 1, 0))],
                        mobs=[("Spider", -8, 0, 1), ("Spider", 1, 0, 1, 10), ("Spider", 1, 0, 1, -10), ("Zombie", 11, 0, 1)], fight=PACK),
    # Lava a block and a half behind: step forward to meet them, so a knockback lands on floor.
    "lava_behind": dict(ground=sunk(LAVA, -4, -10, -2, 10), mobs=[("Zombie", 8, 0, 3, 0, 3), ("Spider", 10, 0, 1, 5)], fight=PACK),
    # Started in a burning patch (fire on netherrack never goes out) with zombies coming: step off it and fight on clean floor.
    "fire_floor": dict(ground=BURNING, mobs=[("Zombie", 8, 0, 3, 0, 3)], fight=PACK),
    # Blazes, shot at from a burning patch, as the blaze run's model did (swarm, hold, the bow, the sword for whatever closes).
    "fire_blazes": dict(ground=BURNING, mobs=[("Blaze", 12, 1, 2, 0, 4)],
                        fight=dict(swarm=True, hold=True, weapon_slot=BOW, ranged={"meleeSlot": SWORD, "minRange": 0, "maxRange": 24})),
    # A 3-wide dead end with a creeper behind two zombies: go forward to the open end, or hit the creeper first (4 hits, each a knockback).
    "dead_end_creeper": dict(ground=[(STONE, (-2, 0, -2), (14, 2, 2)), (AIR, (-1, 0, -1), (14, 2, 1))], mobs=[("Zombie", 8, 0, 2, 0, 2), ("Creeper", 12, 0, 1)], fight=PACK),
    # On a 3-wide causeway 6 up: face them along it, so knockback is along it; a fall costs 3 and the fight goes on below.
    "causeway": dict(at=(0, 6, 0), ground=[(STONE, (-8, 0, -1), (26, 5, 1))],
                     mobs=[("Zombie", 8, 6, 1), ("Zombie", 11, 6, 1, 1), ("Zombie", 14, 6, 1, -1), ("Skeleton", 24, 6, 1)], fight=PACK),
    # Skeletons in cover on two towers with no way up, zombies below: sword the zombies, then shoot the skeletons (heads above the parapet).
    "tower_archers": dict(ground=tower(12, -5) + tower(12, 5), mobs=[("Skeleton", 12, 4, 1, -5), ("Skeleton", 12, 4, 1, 5), ("Zombie", 7, 0, 2, 0, 3)],
                          fight=PACK, then=dict(weapon_slot=BOW, ranged={"meleeSlot": SWORD})),
    # The same, bow first at a skeleton with the sword for whatever closes, then the rest the same way.
    "tower_archers_bow": dict(ground=tower(12, -5) + tower(12, 5), mobs=[("Skeleton", 12, 4, 1, -5), ("Skeleton", 12, 4, 1, 5), ("Zombie", 7, 0, 2, 0, 3)],
                              fight=dict(named=True, weapon_slot=BOW, ranged={"meleeSlot": SWORD}), then=dict(weapon_slot=BOW, ranged={"meleeSlot": SWORD})),
    # Endermen to fight, angry from the start: one straight ahead at pitch 0 is in the look cone (body centre within 1-0.025/d),
    # so the settle before the fight angers it. No skeleton near one and no water: either would teleport it. All in the
    # TELEPORTS box, where a teleport lands on the floor.
    # One angry enderman, open floor: 40 health is 5 critical hits; it hits 1.8 through iron on Easy, and melee never teleports it.
    "enderman_angry": dict(TELEPORTS, mobs=[("Enderman", 12, 0, 1)], settle=.6, fight=dict(named=True, weapon_slot=SWORD)),
    # The same on Normal: 2.8 a hit through iron, so a 5-hit kill costs 8 to 14 of 20.
    "enderman_angry_normal": dict(TELEPORTS, mobs=[("Enderman", 12, 0, 1)], settle=.6, fight=dict(named=True, weapon_slot=SWORD), difficulty=2),
    # One angry, one neutral off to the side: fight the angry one and keep your eyes off the other.
    "enderman_pair": dict(TELEPORTS, mobs=[("Enderman", 12, 0, 1)], spare=[("Enderman", 6, 0, 1, -8)], settle=.6, fight=dict(named=True, weapon_slot=SWORD)),
    # An angry enderman with two zombies and a spider: a night pack, the enderman the hardest of it; one at a time as each comes.
    "enderman_pack": dict(TELEPORTS, mobs=[("Enderman", 12, 0, 1), ("Zombie", 8, 0, 2, 0, 6), ("Spider", 10, 0, 1, 5)], settle=.6, fight=PACK),
    # Two angry on Normal, the player two blocks under a two-high roof it has stood under on purpose: a 2.9-tall enderman cannot
    # come under, and from the edge its reach (2) falls short where a sword's (3) does not.
    "enderman_shelter_normal": dict(TELEPORTS, at=(-2, 0, 0), ground=TELEPORTS["ground"] + [(STONE, (-6, 0, -3), (0, 2, 3)), (AIR, (-5, 0, -2), (0, 1, 2))],
                                    mobs=[("Enderman", 10, 0, 1), ("Enderman", 14, 0, 1)], settle=.6, fight=dict(hold=True, weapon_slot=SWORD), difficulty=2),
}
TOLD = ("state", "reason", "phase", "ticks", "attacks", "criticalHits", "runningHits", "kills", "shots", "hitsObserved", "adjustments", "failure", "movedFromStart", "leash", "pausedBy", "suspendedJobId", "hostileBy", "wentOff", "passedOver", "heldAgainstThrows", "struckAside", "taken")


class Combat(Course):
    def __init__(self, args):
        super().__init__(args)
        mc.mbtool.set_kernel_factory(lambda: self.c)   # the tool must act through the session that owns the clock
        self.evidence = {"ok": False, "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "guards": args.guards, "avoidance": args.avoidance,
                         "oneByOne": args.one_by_one, "cases": {}}
        self.traces: dict[str, list] = {}
        self.looks: dict[str, list] = {}

    def setup(self):
        self.ensure_world()
        self.clock_rules = self.c.call("time.rules")["rules"]
        self.quiet()
        was = self.s.call(FIX + ".rules")
        self.previous_rules = {k: was["rules"][k] == "true" for k in RULES}
        self.difficulty = self.evidence["difficulty"] = self.s.call(FIX + ".rules", rules=RULES)["difficulty"]
        mc.baseline(self.c)
        # Route rules last as long as the client: the course measures the built-in ones as they start, unless asked for
        # mobs and spawners on (what the avoidance setting was).
        mine = [r["name"] for r in self.c.call("nav.route_rules", operation="get")["rules"] if not r["builtin"]]
        if mine: self.c.call("nav.route_rules", operation="remove", names=mine)
        self.c.call("nav.route_rules", operation="reset")
        if self.args.avoidance:
            self.c.call("nav.route_rules", operation="set", rules=[{"name": "mobs", "enabled": True}, {"name": "spawners", "enabled": True}])
        rows = self.c.call("nav.settings", operation="get")["settings"]
        self.evidence["settings"] = {r["name"]: r["value"] for r in rows if r.get("value") != r.get("default")}
        print("difficulty:", self.evidence["difficulty"], "non-default settings:", self.evidence["settings"] or "none", flush=True)

    def teardown(self):
        was = {"difficulty": self.evidence["difficulty"]} if self.difficulty != self.evidence["difficulty"] else {}
        for step in (self.quiet, lambda: self.s.call(FIX + ".clear"), lambda: self.s.call(FIX + ".rules", rules=self.previous_rules, **was),
                     lambda: None if self.args.keep else self.s.call(FIX + ".arena", **ARENA, remove=True),
                     lambda: None if self.args.keep else self.s.call(FIX + ".fill", boxes=[{"id": "minecraft:air", "from": [ARENA["x"] - 1, FLOOR - 4, ARENA["z"] - 1],
                                                                                            "to": [ARENA["x"] + ARENA["width"], FLOOR - 2, ARENA["z"] + ARENA["depth"]]}]),
                     lambda: mc.put_back(self.c, self.clock_rules)):
            try: step()
            except Exception as e: self.evidence.setdefault("teardownErrors", []).append(str(e))

    def quiet(self):
        """No rules, the world running, nothing in hand: between trials nothing may pause or hold the body."""
        mc.quiet(self.c)
        if self.c.call("time.status")["state"].get("paused"): self.c.call("time.resume")
        try: self.c.call("act.stop")
        except BridgeError: pass

    def revive(self):
        if not self.s.call(FIX + ".status")["player"]["dead"]: return
        until = time.monotonic() + 20
        while time.monotonic() < until:
            try: self.c.call("gui.button", index=0)   # the death screen's Respawn
            except BridgeError: pass
            time.sleep(1)
            if not self.s.call(FIX + ".status")["player"]["dead"]: time.sleep(1); return
        raise RuntimeError("the player did not respawn")

    def seen(self, ids: set[int]):
        """The client must hold the mobs before the tool is asked to fight one by id."""
        until = time.monotonic() + 5
        while time.monotonic() < until:
            if ids <= {e.get("entityId") for e in self.c.call("obs.entities", radius=64, limit=128).get("entities", [])}: return
            time.sleep(.1)
        raise RuntimeError(f"the client never showed entities {sorted(ids)}")

    # -- one trial
    def trial(self, name: str) -> dict:
        case = CASES[name]
        self.quiet(); self.s.call(FIX + ".clear"); self.revive()   # what a lost trial left standing would kill the idle player before the reset
        self.s.call(FIX + ".arena", **{**ARENA, **case.get("arena", {})})   # rebuilt: craters, fire, placed blocks and the last ground gone
        for boxes in batches(case.get("ground", [])): self.s.call(FIX + ".fill", boxes=boxes)
        want = case.get("difficulty", self.evidence["difficulty"])
        if want != self.difficulty: self.difficulty = self.s.call(FIX + ".rules", difficulty=want)["difficulty"]
        self.s.call(FIX + ".reset", empty=True, armor=ARMOR, stacks=STACKS, at=[c + d for c, d in zip(START, case.get("at", (0, 0, 0)))], yaw=-90, effects=SIGHT)
        self.s.call(FIX + ".clear"); time.sleep(.5)
        mobs = [m for mob in case["mobs"] for m in summon(self.s, mob)]
        spared = [m for mob in case.get("spare", []) for m in summon(self.s, mob)]
        self.seen({m["entityId"] for m in mobs})
        time.sleep(case.get("settle", 0))                  # the player still looking straight ahead: an enderman there takes it as a stare
        each = case["fight"].get("pack") and self.args.one_by_one
        kwargs = {k: v for k, v in case["fight"].items() if k not in ("named", "pack")}
        if case["fight"].get("pack") and not each: kwargs["swarm"] = True
        if case["fight"].get("named"): kwargs["entity_id"] = mobs[0]["entityId"]
        if self.args.aim_height is not None: kwargs["aim_height"] = self.args.aim_height
        if self.args.guards:
            self.c.call("time.rules", replace=GUARDS)
            until = time.monotonic() + 2                    # the hostile_within pause the model fights from, if the mob has noticed
            while time.monotonic() < until and not self.c.call("time.status")["state"].get("paused"): time.sleep(.05)
        before = self.s.call(FIX + ".status", radius=160)
        watch = Watch(self.s, mobs, spared, case.get("later", [])); watch.start()   # the server session is the watch's until it ends
        started, most = time.monotonic(), len(mobs) + sum(m[3] for _, m in case.get("later", []))
        try:
            if each: receipts, pauses = self.pack(watch, kwargs, most)
            else:
                receipt, pauses = self.fight(kwargs); receipts = [receipt]
                # the receipt read, whoever is left is taken on one at a time (a named fight succeeds on its own mob alone)
                if case.get("then") and (receipt.get("state") != "succeeded" or case["fight"].get("named")):
                    more, asked = self.pack(watch, {**case["then"], **({"aim_height": self.args.aim_height} if self.args.aim_height is not None else {})}, most)
                    receipts += more; pauses += asked
            wall = time.monotonic() - started
            clock = self.c.call("time.status")["state"]
            self.quiet(); time.sleep(2)                     # an arrow in flight lands, a dying mob is seen dying
        finally:
            watch.end()
        mobs += watch.added
        after = self.s.call(FIX + ".status", radius=160)   # a mob that teleported out of the box is alive, not killed
        left = {e["uuid"]: e for e in after["entities"]}
        died = [m for m in mobs if (watch.lowest[m["uuid"]] is not None and watch.lowest[m["uuid"]] <= 0) or (m.get("health") is None and m["uuid"] not in left)]
        hp = lambda rows: sum(r.get("health") or 0 for r in rows)
        item = lambda status, slot: next((i for i in status["player"]["inventory"] if i["slot"] == slot), {})
        receipt = receipts[-1] if receipts else {"state": "no_fight"}
        row = {k: receipt.get(k) for k in TOLD if receipt.get(k) not in (None, [], {})}
        if each or len(receipts) > 1:
            row.update({k: sum(r.get(k) or 0 for r in receipts) for k in ("ticks", "attacks", "criticalHits", "kills", "shots", "hitsObserved")},
                       fights=[str(r.get("reason") or r.get("state"))[:40] for r in receipts])
        row.update(error=receipt.get("error"), endedBy=clock.get("reason") if clock.get("paused") else None,
                   killed=len(died), vanished=sum(m["uuid"] not in left and m not in died for m in mobs) or None, mobs=len(mobs),
                   damage=round(hp(mobs) - hp(left[m["uuid"]] for m in mobs if m["uuid"] in left), 1),
                   bowWear=item(after, BOW).get("meta", 0) - item(before, BOW).get("meta", 0),
                   arrowsSpent=item(before, ARROWS).get("count", 0) - item(after, ARROWS).get("count", 0),
                   healthLost=round(before["player"]["health"] - after["player"]["health"], 1), died=after["player"]["dead"], wallS=round(wall, 1),
                   crowdedS=round(sum(.2 for _, me, _, around, *_ in watch.trace if sum(math.dist(me, at) <= 3 for _, at, _ in around) >= 2), 1),
                   difficulty=self.difficulty if "difficulty" in case else None, watchError=watch.error,
                   lineBlockedAt=(receipt.get("target") or {}).get("lineBlockedAt"), shotBlockedBy=(receipt.get("target") or {}).get("shotBlockedBy"))
        if spared: row["provoked"] = sum(watch.spared[m["uuid"]] < m["health"] for m in spared)   # struck, or hit by anything: not left alone
        self.traces.setdefault(name, []).append(watch.trace); self.looks.setdefault(name, []).append(watch.looks)
        if self.args.guards: row["pauses"] = pauses
        row["ok"] = row["killed"] == row["mobs"] and not row["died"] and (bool(each) or row.get("state") == "succeeded") and not row.get("provoked")
        return {k: v for k, v in row.items() if v is not None}

    def fight(self, kwargs: dict) -> tuple[dict, list]:
        """One mb_fight call, from the pause the world is in if it is. Under guards a pause holds the fight and asks for a
        decision: the course always answers "carry on", and counts the asks, each a round trip to the model."""
        call = lambda: work.mb_fight(**kwargs, timeout_s=90)
        paused = self.c.call("time.status")["state"].get("paused")
        receipt, pauses = told(lambda: mc.with_resume(True, call) if paused else call()), []
        while self.args.guards and receipt.get("state") == "suspended" and receipt.get("pausedBy") and len(pauses) < 20:
            pauses.append(receipt["pausedBy"]); held = receipt["suspendedJobId"]
            receipt = told(lambda: mc.with_resume(True, work.mb_work_resume, held))
        return receipt, pauses

    def pack(self, watch: "Watch", kwargs: dict, count: int) -> tuple[list, list]:
        """A pack taken on as a model would: a fight with the mob the last receipt named (a creeper swelling) or else the
        nearest still standing, then the next, until none stands, the player is dead, there have been twice as many
        fights as mobs, or two in a row ended the same way (a model changes something before it asks a third time)."""
        receipts, pauses, named = [], [], None
        why = lambda r: str(r.get("reason") or r.get("state")).split(":")[0]
        while len(receipts) < 2 * count and not (len(receipts) >= 2 and why(receipts[-1]) == why(receipts[-2]) != "target_dead"):
            now = watch.since(time.monotonic())
            standing = [e for e in now["entities"] if e["uuid"] in watch.lowest and (e.get("health") or 0) > 0]
            if now["player"]["dead"] or not standing: break
            target = next((e for e in standing if e["entityId"] == named), None) or min(standing, key=lambda e: math.dist(e["pos"], now["player"]["pos"]))
            receipt, asked = self.fight({**kwargs, "entity_id": target["entityId"]})
            receipts.append(receipt); pauses += asked
            named = next((int(n) for n in re.findall(r"entity (\d+)", str(receipt.get("reason") or ""))), None)
        return receipts, pauses


class Watch(threading.Thread):
    """The server's view five times a second while the fights run: a mob seen with no health died, and one that left
    the world with health left (a creeper's blast, a despawn) did not, whatever a receipt says."""

    def __init__(self, server, mobs: list[dict], spare: list[dict] = (), later: list = ()):
        super().__init__(daemon=True)
        self.server, self.halt = server, threading.Event()
        self.lowest = {m["uuid"]: m.get("health") for m in mobs}
        self.spared = {m["uuid"]: m.get("health") for m in spare}
        self.later, self.added, self.error = sorted(later, key=lambda w: w[0]), [], None   # a wave is summoned here: this thread owns the server session
        self.now, self.at, self.started = None, 0.0, time.monotonic()
        # [seconds, player [x, z], player health, [[entityId, [x, z], health] of each summoned mob alive], and with bystanders the same of them]
        self.trace: list = []
        self.looks: list = []   # [seconds, [yaw, pitch], eye y, ids of the mobs the look meets the middle of]

    def run(self):
        while not self.halt.is_set():
            try:
                while self.later and self.later[0][0] <= time.monotonic() - self.started:
                    for m in summon(self.server, self.later.pop(0)[1]): self.lowest[m["uuid"]] = m.get("health"); self.added.append(m)
                now = self.server.call(FIX + ".status", radius=160)
            except Exception as e: self.error = f"{type(e).__name__}: {e}"[:200]; time.sleep(.1); continue
            for e in now["entities"]:
                for seen in (self.lowest, self.spared):
                    if e["uuid"] in seen and e.get("health") is not None:
                        was = seen[e["uuid"]]; seen[e["uuid"]] = e["health"] if was is None else min(was, e["health"])
            self.now, self.at = now, time.monotonic()
            pos = lambda e: [round(e["pos"][0], 1), round(e["pos"][2], 1)]
            row = [round(self.at - self.started, 1), pos(now["player"]), now["player"]["health"],
                   [[e["entityId"], pos(e), e.get("health")] for e in now["entities"] if e["uuid"] in self.lowest and (e.get("health") or 0) > 0]]
            if self.spared: row.append([[e["entityId"], pos(e), e.get("health")] for e in now["entities"] if e["uuid"] in self.spared])
            self.trace.append(row)
            self.looks.append([row[0], now["player"].get("look"), now["player"].get("eyeY"), [e["entityId"] for e in now["entities"] if e.get("stare")]])
            time.sleep(.2)

    def since(self, t: float) -> dict:
        """The first view taken after t."""
        until = time.monotonic() + 5
        while self.at <= t and time.monotonic() < until: time.sleep(.05)
        if self.now is None: raise RuntimeError("the server's status never came back")
        return self.now

    def end(self):
        self.halt.set(); self.join(5)


def batches(ground: list) -> list[list[dict]]:
    """The scene's boxes in world coordinates, in as few fill calls as the fixture's cap allows."""
    out, cells = [[]], 0
    for block, a, b, *meta in ground:
        box = {"id": block, "from": [X0 + a[0], FLOOR + a[1], Z0 + a[2]], "to": [X0 + b[0], FLOOR + b[1], Z0 + b[2]], **({"meta": meta[0]} if meta else {})}
        n = math.prod(abs(b[i] - a[i]) + 1 for i in range(3))
        if cells + n > 32768 and out[-1]: out.append([]); cells = 0
        out[-1].append(box); cells += n
    return [b for b in out if b]


def summon(server, mob: tuple) -> list[dict]:
    kind, ahead, up, count, *more = mob
    return server.call(FIX + ".summon", type=kind, x=START[0] + ahead, y=FLOOR + up, z=START[2] + (more[0] if more else 0),
                       count=count, spread=more[1] if len(more) > 1 else 1.5)["summoned"]


def told(call) -> dict:
    """What the tool answered, as a receipt: its result, or its error with the receipt the error carried."""
    try: return call()
    except BridgeError as e:
        err = (e.reply or {}).get("error") or {}
        return {"state": "failed", **(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "error": e.msg[:200]}
    except Exception as e:
        return {"state": "error", "error": f"{type(e).__name__}: {e}"[:200]}


def summary(name: str, rows: list[dict]) -> str:
    med = lambda key: statistics.median(r.get(key) or 0 for r in rows)
    reasons = sorted({str(r.get("reason") or r.get("state")) for r in rows})
    return (f"{name:18} won {sum(r['ok'] for r in rows)}/{len(rows)}  killed {sum(r['killed'] for r in rows)}/{sum(r['mobs'] for r in rows)}"
            f"  ticks {med('ticks'):>5.0f}  swings {med('attacks'):>3.0f}  shots told {sum(r.get('shots') or 0 for r in rows):>2} / arrows spent {sum(r.get('arrowsSpent') or 0 for r in rows):>2}"
            f"  health lost {med('healthLost'):>4.1f}  crowded {med('crowdedS'):>4.1f}s  died {sum(r['died'] for r in rows)}"
            + (f"  fights {sum(len(r.get('fights') or []) for r in rows)}" if any("fights" in r for r in rows) else "")
            + (f"  vanished {sum(r.get('vanished') or 0 for r in rows)}" if any(r.get("vanished") for r in rows) else "")
            + (f"  provoked {sum(r.get('provoked') or 0 for r in rows)}" if any("provoked" in r for r in rows) else "")
            + (f"  asked {sum(len(r.get('pauses') or []) for r in rows)}x" if any("pauses" in r for r in rows) else "") + f"  {', '.join(reasons)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", action="append")
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--guards", action="store_true", help="fight under a run's guards, from the threat pause")
    ap.add_argument("--avoidance", action="store_true", help="with the route rules mobs and spawners on (the old avoidance setting)")
    ap.add_argument("--aim-height", type=float, help="every fight's aim_height, in place of the tool's default")
    ap.add_argument("--one-by-one", action="store_true", help="fight each pack with one named mb_fight a mob, in place of swarm=True")
    ap.add_argument("--keep", action="store_true", help="leave the box standing")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed = 1
    if args.list: print("\n".join(CASES)); return
    unknown = [c for c in args.case or [] if c not in CASES]
    if unknown: sys.exit(f"unknown case {unknown}; --list names them")
    t = Combat(args); t.setup()
    try:
        for name in args.case or CASES:
            rows = t.evidence["cases"][name] = []
            for _ in range(args.trials):
                try: row = t.trial(name)
                except Exception as e: row = {"ok": False, "killed": 0, "mobs": 0, "died": False, "state": "course_error", "error": f"{type(e).__name__}: {e}"[:300]}
                rows.append(row)
                print(f"{name} {'WON ' if row['ok'] else 'LOST'} {json.dumps({k: v for k, v in row.items() if k != 'ok'})}", flush=True)
    finally:
        t.teardown()
        t.evidence["ok"] = bool(t.evidence["cases"]) and all(r["ok"] for rows in t.evidence["cases"].values() for r in rows)
        OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(t.evidence, indent=1), encoding="utf-8")
        TRACES.write_text(json.dumps(t.traces), encoding="utf-8"); TRACES.with_name("combat-looks.json").write_text(json.dumps(t.looks), encoding="utf-8")
    print()
    for name, rows in t.evidence["cases"].items(): print(summary(name, rows), flush=True)
    won, total = (sum(r["ok"] for rows in t.evidence["cases"].values() for r in rows), sum(len(rows) for rows in t.evidence["cases"].values()))
    print(f"\n{won}/{total} fights won", flush=True)
    sys.exit(0 if t.evidence["ok"] else 1)


if __name__ == "__main__":
    main()
