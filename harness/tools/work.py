# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Navigation, mining, building, schematics/copy, scans and the source engine's settings/cache.

Every tool forwards one Java method; Java validates ranges and shapes. Work outcomes and arrivals
pass through ``notes.tracked`` so nearby world notes surface and important outcomes are journaled.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from statistics import median
from typing import Any

from kernel import BridgeError
from mbtool import kernel, tool
from mbtools_gtnh import notes
from mbtools_gtnh import plan
from mbtools_gtnh.recipes_quests import _delta, _held

REPORT_KEYS = ("size", "count", "skipped", "tileEntities")
# mb_mine(vein=...) defaults: GregTech's ore-vein grid as this harness understands it, and what counts as its ore.
# Veins centre on chunks whose |chunk coordinate| % period == offset and reach spanChunks chunks around that one,
# `height` blocks up and down from the ore you saw. Yours to correct: edit these, or pass vein_grid / items / bounds.
VEIN_GRID = {"period": 3, "offset": 1, "spanChunks": 1, "height": 8}
VEIN_ITEMS = [{"id": "gregtech:gt.metaitem.03"}]
LEFT = Path(__file__).resolve().parents[2] / ".state" / "mining-left.json"  # paused mining jobs with targets still known


def _any_meta(params: dict) -> dict | None:
    """A cell without meta means "this block, any variant": meta 0 is a facing no furnace, chest or machine can be placed
    with. The job reads each cell that way itself; this only says back which ids were read so (None when none were)."""
    named = params.get("cells") or [(params.get("selection") or {}).get("block")]
    ids = sorted({c["id"] for c in named if isinstance(c, dict) and "id" in c and "meta" not in c})
    return {"ids": ids, "meaning": "cells without meta accept any meta of these ids; give a cell meta to demand a variant or a facing"} if ids else None


def _wait(ticks: int) -> float:
    """Real seconds to wait on a job whose budget is `ticks` game ticks: that budget at two thirds speed, and slack.
    The tick budget is the one timeout. The wait only has to outlast it, so the job ends itself and says why."""
    return ticks / 20 * 1.5 + 30


def _with(receipt: Any, key: str, fact: Any) -> Any:
    """A harness decision said back in the receipt."""
    return {**receipt, key: fact} if fact is not None and isinstance(receipt, dict) else receipt


CLOSING = 6000  # the most ticks a build takes past its budget to put back what it took out and take away its scaffolds


def _plan(cells, selection, uses, origin, size, **options) -> dict:
    """The request of a build or its preview: cells or a selection, uses with either or alone."""
    if cells is not None and selection is not None or cells is None and selection is None and not uses:
        raise ValueError("provide exactly one of cells, selection or drawing (uses may stand alone)")
    params = dict(options)
    if cells is not None: params["cells"] = cells
    if selection is not None: params["selection"] = selection
    if uses: params["uses"] = uses
    if origin is not None: params["origin"] = origin
    if size is not None: params["size"] = size
    return params


def _build_call(method: str, params: dict) -> Any:
    """One request: a job's plan is at most 4096 cells (Java says so when it is more)."""
    closing = CLOSING if params.get("allowBreak") or params.get("allowPlace") else 0  # without either there is nothing to put back
    timeout_s = _wait(params["timeoutTicks"] + closing) if params.get("timeoutTicks") else None  # a preview has no budget: the default wait
    return notes.tracked(method, timeout_s, **params)


def _spec(result: dict, **overrides) -> dict:
    """The nested plan {cells,origin,size} of an import/copy result, with explicit overrides applied."""
    plan = result.get("plan") if isinstance(result, dict) else None
    if not isinstance(plan, dict) or not isinstance(plan.get("cells"), list):
        raise ValueError("Java did not return a plan with cells")
    spec = dict(plan)
    spec.update({k: v for k, v in overrides.items() if v is not None})
    return spec


def _echo(spec: dict) -> dict:
    return {k: (len(v) if k == "cells" else v) for k, v in spec.items()}


@tool(coverage=["move"])
def mb_route(name: str, reverse: bool = False, start_index: int = 0,
             allow_break: bool = False, allow_place: bool = False,
             override_protection: bool = False, timeout_ticks: int = 1200) -> Any:
    """Follow a saved route, approaching its first anchor then following bounded corridors.

    Each leg replans against current terrain; blocked corridors fail instead of
    silently taking a distant shortcut. reverse reverses the anchor order; start_index
    indexes that resulting order. A cancelled route can be restarted at the reported
    nextIndex. Raise timeout_ticks for long journeys (<=72000); it counts simulation
    ticks and is the only timeout. With allow_break/allow_place a protected region still
    refuses this walk's digging and placing unless override_protection is true.
    Notes near the arrival position are returned under "notes".
    """
    return notes.tracked("nav.route", _wait(timeout_ticks), name=name, reverse=reverse, startIndex=start_index,
                         allowBreak=allow_break, allowPlace=allow_place,
                         overrideProtection=override_protection, timeoutTicks=timeout_ticks)


@tool(rung=1, coverage=["move"])
def mb_follow(target: dict, duration_ticks: int = 1200, radius: int = 2,
              offset_distance: float = 0, offset_direction: float = 0,
              allow_break: bool = False, allow_place: bool = False,
              override_protection: bool = False, timeout_s: float = 90.0,
              stall_ticks: int | None = None) -> Any:
    """Follow loaded native entities through source FollowProcess for a bounded duration.

    target requires one or more exact selectors from entityId, uuid, type and name;
    every supplied selector must match. duration_ticks is simulated client time
    (1..72000), while timeout_s is only the real-time RPC wait and must cover that
    duration when the world is not paused. The action fails if no matching loaded
    entity remains. Radius is 0..64; offset direction/distance define the source
    GoalXZ follow offset. Completion after duration means the bounded follow window
    elapsed, not that the entity was reached or remains present afterwards.
    Ticks within radius+2 of a followed entity count as progress, so waiting beside one that stands
    still is not a stall; stall_ticks (default the stallTicks setting, 200; 0 off) ticks away from
    every target on ground already covered end it as stalled_no_progress_near_x,y,z (paused if it
    had been beside one this job, else failed). A death ends any job as failed, player_died.
    """
    if not isinstance(target, dict) or not target or not set(target) <= {"entityId", "uuid", "type", "name"}:
        raise ValueError("target needs entityId, uuid, type or name selectors")
    return notes.tracked("nav.follow", timeout_s, target=target, durationTicks=duration_ticks,
                         radius=radius, offsetDistance=offset_distance, offsetDirection=offset_direction,
                         allowBreak=allow_break, allowPlace=allow_place, overrideProtection=override_protection,
                         **({} if stall_ticks is None else {"stallTicks": stall_ticks}))


@tool(rung=1, coverage=["combat"])
def mb_fight(entity_id: int | None = None, hold: bool = False, swarm: bool = False, leash: float = 16, bail_health: float = 8,
             max_attackers: int = 2, max_health_loss: float = 10, max_growth: int = 3, weapon_slot: int | None = None, duration_ticks: int = 600,
             crit: bool | None = None, block: bool | None = None, ranged: dict | bool | None = None,
             timeout_s: float = 60.0, target: dict | None = None, hostile: list[dict] | None = None,
             allow_break: bool = False, allow_place: bool = False) -> Any:
    """Fight one mob as a job, the way mb_mine mines: you choose the mob and the limits, it does the footwork.

    entity_id comes from mb_obs entities or the clock's threats. It paths to the mob (never breaking
    or placing), and in reach blocks with a sword between swings and times each swing to land while
    falling, a critical hit at the fastest rate the game counts. It backs away from a creeper it is
    fighting while that creeper swells. hold=True never moves: it hits the chosen mob, or with no
    entity_id the nearest hostile in sight, whenever one comes into reach, and succeeds once none is
    in sight. Against more than one melee mob, first stand where only one can reach you (a doorway,
    a one-wide tunnel, a pillar two blocks up) and use hold; pursuing one mob of a group walks you
    into the others. It never chooses to chase a different mob.
    swarm=True is for many small mobs at once (silverfish, a spawner): it stands like hold but
    always swings at whichever hostile is nearest in reach, never stops as outnumbered, and
    defaults crit and block off so every swing lands at the weapon's full rate. It takes no
    entity_id or target.
    While a fight runs, being hit does not trip the healthDrop guard, and a mob taking you as its
    target within 4 blocks (8 with hold or swarm) does not trip threat: that is the fight you
    chose. Every other guard (health, air, burning, food, a swelling creeper, a threat farther
    out) still pauses the world and ends the fight.
    weapon_slot is the hotbar slot to fight with (default: whatever is in hand). leash is how far
    from where you started the mob may be before the job stops chasing; bail_health is the health
    at which it stops; max_attackers is how many hostile mobs may be within 4 blocks.
    It also stops while it is going badly, with health left to act on: max_health_loss is how
    much health this fight may cost (health_lost), and max_growth how many more hostiles may be
    in sight within 8 blocks than when it began (swarm_growing: more are arriving than you kill).
    Both hold in swarm mode. The call blocks until the fight ends, so these limits and a short
    duration_ticks are where you get to change your mind: fight in short spans and decide again
    from each receipt whether to go on, retreat, pillar up or wall in.
    It stops, as a failure that the actionFailed guard turns into a pause, the moment the fight
    gets worse than the one you chose: health_at_bail_line, health_lost, swarm_growing, outnumbered, creeper_swelling (a
    different creeper), target_beyond_leash, target_lost, cannot_reach_target, duration_elapsed.
    ranged=True fights with whatever launcher or throwable is in weapon_slot, and knows no
    weapon by name: it holds use and releases; if nothing flies it clicks (a crossbow loads,
    then fires); if still nothing it winds longer, and after three tries ends with
    no_projectile_fired (no ammunition?). It watches its own projectile to measure speed,
    gravity and drag, aims by simulating that flight and leads a moving target. After each
    fight fit_ballistics (this file: yours to improve) turns the shots into numbers kept per
    weapon name in notes/ballistics.json, so a new weapon's first fight is its calibration.
    It closes only until it has a line of sight within maxRange and steps back inside
    minRange when the ground behind is safe. A mob within 3.5 blocks ends the ranged fight
    (hostile_in_melee_range) unless ranged={meleeSlot: n} names the hotbar slot of a melee
    weapon to finish with. ranged={drawTicks, reloadTicks, minRange:6,
    maxRange:20, clickAfterLoad, speed, gravity, drag} overrides what it has learned; the
    result reports shots, hitsObserved and ballistics.
    The result lists hostilesInSight: decide again from there (fight the next, retreat, eat, wall
    in). Arrows are not blocked by chasing: close on a skeleton along cover, or break line of sight.
    target {entityId|uuid|type|name|class} names any entity instead of entity_id (class matches
    superclasses and interfaces). hostile [selectors, same form] is what counts as hostile for hold
    and max_attackers (default [{class:"net.minecraft.entity.monster.IMob"}]); it never refuses a
    target. allow_break / allow_place let the approach dig or pillar. The receipt shows jobSettings
    (settings forced for the job, restored after), hostileRule, target class/hostile/health, and for
    ranged fights adjustments and shotEntities (a shot is any new non-living entity flying away from you).
    """
    if entity_id is None and target is None and not hold and not swarm:
        raise ValueError("entity_id or target is required unless hold=True or swarm=True")
    if swarm and (entity_id is not None or target is not None):
        raise ValueError("swarm fights whatever is in reach: pass no entity_id or target")
    params = {"hold": hold, "leash": leash, "bailHealth": bail_health, "maxAttackers": max_attackers,
              "maxHealthLoss": max_health_loss, "maxGrowth": max_growth,
              "durationTicks": duration_ticks, "crit": not swarm if crit is None else crit,
              "block": not swarm if block is None else block}
    if entity_id is not None: params["entityId"] = entity_id
    if swarm: params["swarm"] = True
    if weapon_slot is not None: params["weaponSlot"] = weapon_slot
    if target is not None: params["target"] = target
    if hostile is not None: params["hostile"] = hostile
    if allow_break: params["allowBreak"] = True
    if allow_place: params["allowPlace"] = True
    if not ranged: return notes.tracked("nav.fight", timeout_s, **params)
    book = notes.notes_dir() / "ballistics.json"  # what each weapon has taught so far: yours to read and correct
    known = json.loads(book.read_text()) if book.is_file() else {}
    held = kernel().call("obs.inventory")["main"][weapon_slot if weapon_slot is not None else kernel().call("obs.player")["selectedSlot"]].get("stack") or {}
    weapon = f"{held.get('id')}|{held.get('name')}"
    params["ranged"] = {**known.get(weapon, {}), **(ranged if isinstance(ranged, dict) else {})}
    try: result = notes.tracked("nav.fight", timeout_s, **params)
    except BridgeError as error: result = ((error.reply or {}).get("error") or {}).get("receipt") or {}; raise
    finally:
        learned = fit_ballistics(known.get(weapon, {}), result if isinstance(result, dict) else {})
        if learned: known[weapon] = learned; book.parent.mkdir(parents=True, exist_ok=True); book.write_text(json.dumps(known, indent=1))
    return result


def fit_ballistics(before: dict, receipt: dict) -> dict | None:
    """One weapon's numbers after a fight: how it was used, and speed, gravity and drag from each shot's velocity samples.

    A projectile steps v = v * drag - gravity (vertical) and v * drag (horizontal) each tick, so consecutive samples
    give both; the first sample is about a tick old, so launch speed is that sample with one tick of drag undone.
    """
    if not receipt.get("shots"): return None
    out = {**before, **{k: v for k, v in receipt.get("ballistics", {}).items() if k in ("drawTicks", "reloadTicks", "clickAfterLoad")}}
    for shot in receipt.get("tracks", []):
        pairs = list(zip(shot, shot[1:]))
        ratios = [b[0] / a[0] for a, b in pairs if a[0]]  # a shot straight up or down has no horizontal speed to measure drag by
        if not pairs: continue  # one sample measures nothing
        drag = min(1.0, max(.9, median(ratios))) if ratios else out.get("drag", 1.0)
        # An impact can slow a still-live projectile before Java stops tracking it.
        # Fit the consistent flight samples, not that final collision impulse.
        flight = [(a, b) for a, b in pairs if not a[0] or abs(b[0] / a[0] - drag) <= .02 * drag]
        if not flight: continue
        gravity = min(.3, max(0.0, median(a[1] * drag - b[1] for a, b in flight)))
        new = {"speed": (shot[0][0] ** 2 + (shot[0][1] + gravity) ** 2) ** .5 / drag, "gravity": gravity, "drag": drag}
        n = out.get("shotsMeasured", 0)
        for key, value in new.items(): out[key] = round(value if not n else (out[key] * min(n, 4) + value) / (min(n, 4) + 1), 5)
        out["shotsMeasured"] = n + 1
    return out


@tool(rung=1, coverage=["move"])
def mb_process(process: str, duration_ticks: int = 1200, goal: dict | None = None,
               center: list[int] | None = None, radius: int = 24, block: dict | None = None,
               allow_break: bool = False, allow_place: bool = False,
               explore_for_blocks: bool = True, open_on_arrival: bool = False,
               enter_portal: bool = False, override_protection: bool = False,
               timeout_s: float = 90.0, stall_ticks: int | None = None,
               crops: list[dict] | None = None, soils: list[dict] | None = None, seeds: list[dict] | None = None,
               fertilizers: list[dict] | None = None, collect: list[dict] | None = None) -> Any:
    """Run one bounded source process: goal, explore, get_to_block, or farm.

    goal needs a structured source goal for process='goal': block, near, adjacent,
    two_blocks, xz, y, axis, inverted, composite, run_away, or breathable. Positions are arrays:
    {type:"block",pos:[x,y,z]}, {type:"near",pos:[x,y,z],radius:2}, {type:"xz",x:10,z:-20},
    {type:"y",y:64}. {type:"breathable",radius:12} (1..24) is the way out of water: the nearest
    cells where your head is in open air, on ground or floating at the surface; with allow_break
    it may dig up to them. An xz goal ends wherever that column is reachable, which can be in
    water or a hole: prefer near/block with a y you have seen on the map or in a scan. get_to_block needs
    block {id,meta?}; explore uses center (defaults to player feet) with no
    radius bound. Farm uses center and radius 1..64. duration_ticks is simulation time, whereas timeout_s is the real RPC
    wait. Goal/get_to_block report success only when source completion reaches their
    native condition, and fail with timeout when duration runs out first. Explore and
    farm have no end of their own: their duration running out is state paused, reason
    timeout, not a success. stall_ticks (default the stallTicks setting, 200; 0 off) ticks
    on ground already covered with no progress (for farm: no inventory change) end it as
    stalled_no_progress_near_x,y,z.
    pathRules: which of your block rules decided about which block, as in mb_mine.
    get_to_block also takes block {item:{id,meta?}} or {ore}: found by what pick-block returns
    (GregTech ores and machines keep their kind there, not in meta).
    Farm: crops, soils, seeds, fertilizers, collect are selectors; the receipt's farmRules shows the
    defaults it used (vanilla crops, farmland and soul sand, bone meal, any item on the ground; seed:
    any plantable the soil accepts) and farmSeen what it could not work (openSoilWithoutSeed,
    cropSelectorsMatchingNothing). A crop selector with meta names its ripe state.
    Explicit empty selector lists disable their phases; omitted fields keep defaults.
    For pickup only, pass crops=[], soils=[], seeds=[], fertilizers=[] and the wanted collect selectors.
    Harvesting breaks crop blocks; use native interactions for crops harvested by right-click.
    """
    if process not in {"goal", "explore", "get_to_block", "farm"}:
        raise ValueError("process must be goal, explore, get_to_block or farm")
    if process == "goal" and not isinstance(goal, dict):
        raise ValueError("goal process requires a goal object")
    if process == "get_to_block" and not isinstance(block, dict):
        raise ValueError("get_to_block requires block {id,meta?}")
    params = {"process": process, "durationTicks": duration_ticks,
              "allowBreak": allow_break, "allowPlace": allow_place,
              "exploreForBlocks": explore_for_blocks, "openOnArrival": open_on_arrival,
              "enterPortal": enter_portal, "overrideProtection": override_protection}
    if goal is not None: params["goal"] = goal
    if center is not None: params["center"] = center
    if block is not None: params["block"] = block
    if process == "farm": params["radius"] = radius
    if stall_ticks is not None: params["stallTicks"] = stall_ticks
    for key, value in (("crops", crops), ("soils", soils), ("seeds", seeds), ("fertilizers", fertilizers), ("collect", collect)):
        if value is not None: params[key] = value
    return notes.tracked("nav.process", timeout_s, **params)


@tool(rung=1, lane=lambda kw: "read" if kw.get("operation", "get") == "get" else "act", coverage=["machine"])
def mb_settings(operation: str = "get", query: str = "", values: dict | None = None,
                save: bool = False) -> Any:
    """Read, atomically set, or reset pinned source settings while the source engine is idle.

    operation is get, set, or reset. Set values accepts JSON scalar/list/map forms
    converted to the source parser syntax; reset resets only named values. A failed
    later value leaves every runtime setting unchanged. save writes atomically before
    applying the candidate; a disk failure leaves runtime settings and the existing
    settings path unchanged. The response uses source-string value/default fields.
    A declared setting can still be rejected when its native runtime support is absent.
    Block rules are yours, as lists of "modid:name" (every meta) or "modid:name:meta": hazards
    (never walked into or stood on; defaults fire, cactus, web, tripwire, end portal, any of which
    you may remove), standOn and neverStandOn (override what the block's collision box says;
    neverStandOn wins), blocksToDisallowBreaking (defaults ice, silverfish stone). Otherwise the
    path search stands on a block whose collision box tops out near its top and walks through one
    with no collision box. A job's symptoms show what hurt or slowed you, and where.
    hazards also takes "item=modid:item[:damage]": matched against what pick-block returns there
    (GregTech ores and machines). toolsToAvoid: item ids never swung (the tools measured breaking
    nothing are listed separately by mb_obs tools, per slot with the game's strength and harvestable).
    """
    if operation not in {"get", "set", "reset"}:
        raise ValueError("operation must be get, set or reset")
    if operation in {"set", "reset"} and values is None:
        raise ValueError("set/reset requires values")
    params: dict[str, Any] = {"operation": operation, "query": query, "save": save}
    if values is not None:
        params["values"] = values
    return kernel().call("nav.settings", **params)


@tool(rung=1, lane=lambda kw: "read" if kw.get("operation", "status") in ("status", "block", "locations", "result") else "act", coverage=["machine"])
def mb_cache(operation: str = "status", pos: list[int] | None = None, range: int = 2,
             block: str | None = None, meta: int | None = None, limit: int = 64,
             region_distance_squared: int = 2, task_id: str | None = None) -> Any:
    """Inspect or administer the source terrain cache; cached cells are approximate evidence.

    status describes the current world cache. block(pos) returns an approximate
    registry state that must be rechecked with loaded native observation before an
    action. repack(range 0..16) captures nearby loaded chunks. save/reload/locations
    are asynchronous disk/cache operations: they return {id,state:'running'}; poll
    result with task_id until complete or failed. locations needs block and optional
    metadata, uses cached regions only, and is not proof that a block still exists.
    """
    if operation not in {"status", "block", "repack", "save", "reload", "locations", "result"}:
        raise ValueError("unknown cache operation")
    if operation == "block" and pos is None: raise ValueError("block requires pos")
    if operation == "locations" and not block: raise ValueError("locations requires block")
    if operation == "result" and not task_id: raise ValueError("result requires task_id")
    params: dict[str, Any] = {"operation": operation}
    if operation == "block": params["pos"] = pos
    if operation == "repack": params["range"] = range
    if operation == "locations":
        params.update(block=block, limit=limit, regionDistanceSquared=region_distance_squared)
        if meta is not None: params["meta"] = meta
    if operation == "result": params["id"] = task_id
    return kernel().call("nav.cache", **params)


def vein_bounds(pos: list[int], grid: dict = VEIN_GRID) -> dict:
    """The box mb_mine(vein=pos) searches: the vein-centre chunk nearest pos on each axis, the chunks around it, grid height up and down."""
    period, offset, span, height = grid["period"], grid["offset"], grid["spanChunks"], grid["height"]
    chunk = lambda v: min((c for c in range((v >> 4) - period, (v >> 4) + period + 1) if abs(c) % period == offset), key=lambda c: abs(c * 16 + 8 - v))
    cx, cz = chunk(pos[0]), chunk(pos[2])
    return {"min": [(cx - span) * 16, max(1, pos[1] - height), (cz - span) * 16],
            "max": [(cx + span + 1) * 16 - 1, min(254, pos[1] + height), (cz + span + 1) * 16 - 1]}


def _drops(before: dict, after: dict) -> dict:
    """What a job changed in your inventory, by name: dropsObserved is every item that went up, spent every one that went
    down (plugs placed, tools worn out), each item netted on its own so a spent stack never hides a gained one."""
    delta = _delta(before, after)
    return {"dropsObserved": {n: v for n, v in delta.items() if v > 0}, "spent": {n: -v for n, v in delta.items() if v < 0}}


@tool(rung=1, coverage=["move"])
def mb_mine(blocks: list[dict] | None = None, items: list[dict] | None = None, quantity: int = 1,
            bounds: dict | None = None, radius: int = 24,
            allow_break: bool = False, allow_place: bool = False,
            override_protection: bool = False, timeout_ticks: int = 12000,
            beside_fluid: bool | None = None,
            vein: list[int] | None = None, vein_grid: dict | None = None, stall_ticks: int | None = None,
            tool_slot: int | None = None, cleanup_scaffold: bool | None = None) -> Any:
    """Run bounded native quantity mining and return its terminal receipt.

    blocks are the block selectors to mine: {id, meta?}, or {id, item:{id, meta?}} to match by the block's
    pick-block item. items is optional: without it, quantity counts any inventory gain (each item netted on
    its own, so cobblestone spent on plugs does not cancel ore gained); with it, only items matching those
    selectors count. Either way quantity is summed over the job's sessions (each measured from its own start,
    so smelting or storing ore between sessions costs nothing). Whatever items says, the receipt's
    dropsObserved {name: n} lists everything that arrived during this call and spent what went down.
    Supply inclusive world bounds, or radius 1..64 around the player. The job scans, approaches safe faces,
    mines, loiters/paths for matching drops, counts actual inventory gain and records
    unreachable targets. The tool for each block is whichever stack anywhere in your
    inventory the game itself says harvests it fastest; nothing is judged by its kind,
    and broken or near-empty tools are skipped. What a swing really did is measured:
    blocksBroken, and extraBroken/extraBrokenAt for blocks around the target that went
    too (a hammer, a vein miner, a laser); an extra break of a protected block stops
    the job. dropsLeftInBounds counts matching items still lying in the bounds when it
    ends: drops it never picked up. It stops on full inventory and never equates a vanished
    block with collection. A returned jobId is durable; inspect with mb_work_status
    and use mb_work_resume after correcting a blocked job. A protected region (mb_memory
    protect): the job's path will not dig or place in it and no target inside it is taken
    (skipped as protected_region:<names>); override_protection=True lifts that for this
    job. The override and the terrain permissions apply only to this attempt.
    timeout_ticks is a budget, not a verdict: the mining rate varies widely (walking,
    digging down, tool swaps); in a dense vein it is typically tens of blocks a minute,
    walking included, and the receipt's blocksPerMinute is the number to size the next
    budget from. A job that runs out of budget with something gained stops as
    paused (reason timeout_with_progress), which is not a failure: mb_work_resume
    continues it. A paused receipt says remainingTargets (the targets it still knew of)
    and, for a vein, vein; mb_status lists paused jobs that still have targets. Paused or failed is judged on this session's gain alone, and so is
    blocksPerMinute. The receipt's bounds is the box it scanned (radius covers y-16..y+16 within 1..254).
    stall_ticks (default: the stallTicks setting, 200; 0 is off) is the shared watchdog: that many
    ticks with nothing gained or broken and no block stood in that it had not stood in since, and the
    job ends with reason stalled_no_progress_near_x,y,z, as paused if this session gained something,
    else failed. Pacing or circling on the same ground counts as standing still.
    Every target it leaves is in skipped [{pos, block, why}] (the first 16; skippedCount
    counts them): unreachable (no path found), will_not_break_here (with the fluid beside it),
    protected_region:<names>, no_tool_in_inventory_harvests_it, not_exposed, between_bedrock,
    below/above_min/maxYLevelWhileMining. Unreachable targets are journaled: a resume does not
    retry them unless its options pass retry: true.
    vein=[x,y,z], one ore block you have seen, mines the vein it belongs to. Its defaults, each
    in the receipt's veinDefaults with where it came from: bounds are the chunk that vein is
    centred on plus the chunks around it, 8 blocks up and down (VEIN_GRID in this file; vein_grid
    {period, offset, spanChunks, height} or your own bounds replace it); blocks is the id of the
    block at vein, which for GregTech ore is every ore in the box (the kind lives in its tile
    entity); items is VEIN_ITEMS (GregTech raw ore). Ask for the quantity the next chapter needs,
    with allow_break and allow_place.
    beside_fluid (default: on whenever allow_place is) breaks blocks that have water or oil
    beside or above them and, on the next tick, before the fluid moves, puts a throwaway
    block (cobblestone, dirt: keep a stack in the hotbar) where the broken one was; plugged
    lists them. A block dug out of your own way through is not plugged beside water, which
    only wets you there. Lava is never mined beside. Without it such targets are skipped as
    will_not_break_here, with the fluid beside each.
    tool_slot (0..35) forces the tool in that slot for this job (the kind, so a swap to the hotbar
    keeps it); the receipt shows forcedTool, and a tool measured breaking nothing is still reported.
    cleanup_scaffold is required with allow_place: the path places blocks to climb or bridge
    (a pillar up a tree, a step over a gap), and the job records each one. True breaks them again
    once the work ends as work does (done, inventory full, out of reachable targets, out of time,
    stalled), on its own budget and without placing new ones; a death, an unplugged fluid, a
    protected block broken or a cancel leaves them standing, as does False (an escape pillar, a
    way out of a ravine you want to keep). Fluid plugs are never scaffold. The receipt's
    scaffold {cleanup, placed, at, removed, left [{pos, block, why}]} says what it placed and what
    still stands: a block that changed since (dirt grown to grass) or now holds fluid back is left.
    symptoms: what happened to you during the job (damage and its type, effects gained or
    lost, air lost, burning, webbed, slowed), each first seen with the feet/head/under blocks
    there and a count. pathRules: which of your block rules (hazards, standOn, neverStandOn,
    blocksToDisallowBreaking; see mb_settings) decided about which block, as search checks.
    """
    params = dict(blocks=blocks, quantity=quantity, radius=radius,
                  allowBreak=allow_break, allowPlace=allow_place,
                  overrideProtection=override_protection, timeoutTicks=timeout_ticks)
    k, facts = kernel(), {}
    if vein is not None:
        grid = {**VEIN_GRID, **(vein_grid or {})}
        if not blocks: params["blocks"] = [{"id": k.call("obs.block", x=vein[0], y=vein[1], z=vein[2])["id"]}]
        facts["veinDefaults"] = {"bounds": "your bounds" if bounds is not None else {"from": "vein grid", "grid": grid},
                                 "blocks": "your blocks" if blocks else {"from": "id of the block at vein", "used": params["blocks"]},
                                 "items": "your items" if items else {"from": "VEIN_ITEMS", "used": VEIN_ITEMS}}
        bounds = bounds if bounds is not None else vein_bounds(vein, grid)
        items = items or VEIN_ITEMS
    elif not blocks: raise ValueError("blocks are required unless vein is given")
    if allow_place and cleanup_scaffold is None:
        raise ValueError("allow_place lets the path place blocks to climb or bridge: pass cleanup_scaffold=True to break "
                         "them again when the work ends, or False to leave them standing")
    if cleanup_scaffold and not allow_place: raise ValueError("cleanup_scaffold needs allow_place: without it nothing is placed")
    if cleanup_scaffold is not None: params["cleanupScaffold"] = cleanup_scaffold
    if tool_slot is not None: params["toolSlot"] = tool_slot
    if items is not None: params["items"] = items  # absent: the job counts any gain (the Java side decides what that means)
    if allow_place if beside_fluid is None else beside_fluid: params["besideFluid"] = True
    if bounds is not None: params["bounds"] = bounds
    if stall_ticks is not None: params["stallTicks"] = stall_ticks
    before = _held(k)
    try: result = notes.tracked("nav.mine", _wait(timeout_ticks), **params)
    except BridgeError as error:
        receipt = ((error.reply or {}).get("error") or {}).get("receipt")
        if isinstance(receipt, dict): receipt.update(_drops(before, _held(k)), **facts); _left(receipt)
        raise
    return _left({**result, **_drops(before, _held(k)), **facts}, vein) if isinstance(result, dict) else result


def _left(receipt: dict, vein: list[int] | None = None) -> dict:
    """A mining job that ended paused with targets still known is remembered for mb_status, and its receipt says how many
    are left; any other end of that job forgets it."""
    if receipt.get("action") != "mine" or receipt.get("state") not in ("succeeded", "failed", "cancelled", "paused"): return receipt
    try: left = json.loads(LEFT.read_text(encoding="utf-8"))
    except (OSError, ValueError): left = {}
    job, known = str(receipt.get("jobId")), len(receipt.get("targets") or [])
    vein = vein or (left.get(job) or {}).get("vein")
    if receipt["state"] == "paused": receipt = {**receipt, "remainingTargets": known, **({"vein": vein} if vein else {})}
    if receipt["state"] == "paused" and known:
        box = receipt.get("bounds") or {}
        at = vein or ([(lo + hi) // 2 for lo, hi in zip(box["min"], box["max"])] if box.get("min") and box.get("max") else receipt.get("endedAt"))
        left[job] = {"vein": vein, "at": at, "left": known, "gained": receipt.get("gained"), "pausedAt": time.time()}
    else: left.pop(job, None)
    try:
        LEFT.parent.mkdir(parents=True, exist_ok=True); tmp = LEFT.with_suffix(".tmp")
        tmp.write_text(json.dumps(dict(sorted(left.items(), key=lambda kv: -kv[1]["pausedAt"])[:20])), encoding="utf-8"); tmp.replace(LEFT)
    except OSError: pass
    return receipt


def paused_mining(limit: int = 5) -> list[dict]:
    """Mining jobs that paused with targets still known, newest first: mb_status lists them."""
    try: left = json.loads(LEFT.read_text(encoding="utf-8"))
    except (OSError, ValueError): return []
    rows = sorted(left.items(), key=lambda kv: -kv[1]["pausedAt"])[:limit]
    return [{"job": job, "vein" if e.get("vein") else "at": e["at"], "left": e["left"], "gained": e.get("gained"),
             "minutesAgo": int((time.time() - e["pausedAt"]) // 60)} for job, e in rows]


@tool(lane="read", coverage=["machine"])
def mb_build_preview(cells: list[dict] | None = None, selection: dict | None = None,
                     origin: list[int] | None = None, replace_existing: bool = False,
                     override_protection: bool = False, allow_break: bool = False,
                     allow_place: bool = False, size: list[int] | None = None,
                     drawing: dict | None = None, uses: list[dict] | None = None) -> Any:
    """Read-only: what mb_build would find and need for the same plan. Nothing in the world changes.

    It takes what mb_build takes (cells, selection or drawing, and uses; mb_build has the formats,
    the build order and the caps) and answers with counts and the first few of each list:
    total, correct, mismatched, matches; unloaded, unsupported (no item places that
    block: name one with the cell's item); conflicts (cells that want a block and hold another: the
    build stops as occupied unless replace_existing); materials, one row per item {selector, needed,
    allocated, missing} against what you carry now, and missingItems, their sum; differences, the
    first 8 cells that do not match with what is there; steps, the build order as {stage, y, cells}.
    anyMeta lists the ids read as "any variant". It does not load chunks, reserve inventory or prove
    that a plain cell can be reached. For click cells and uses it does look: clicks {count, checked
    (the first 32), ready, problemCount, problems: up to 8 of {click, pos, reason}} says whether each
    has a spot to stand and a face in view once the cells before it stand, with mb_build's reason
    words; no route is searched.
    """
    if drawing is not None:
        if cells is not None or selection is not None: raise ValueError("provide exactly one of cells, selection or drawing")
        cells, origin = plan.from_drawing(drawing)
    params = _plan(cells, selection, uses, origin, size, replaceExisting=replace_existing, overrideProtection=override_protection,
                   allowBreak=allow_break, allowPlace=allow_place)
    return _with(_build_call("nav.build_preview", params), "anyMeta", _any_meta(params))


@tool(rung=1, coverage=["machine"])
def mb_build(cells: list[dict] | None = None, selection: dict | None = None,
             origin: list[int] | None = None, replace_existing: bool = False,
             override_protection: bool = False, timeout_ticks: int = 12000,
             allow_break: bool = False, allow_place: bool = False,
             size: list[int] | None = None, drawing: dict | None = None,
             uses: list[dict] | None = None) -> Any:
    """Build a plan of blocks and return the job's receipt. A build runs one way; nothing selects another.

    What to build, said in exactly one of three ways. A job takes at most 4096 cells: a larger build
    is several jobs.
      cells: [{pos, id, meta?, item?, verify?: {pickedItem: itemSelector}, clear?, replace?, stage?,
        click?, expect?}], pos relative to origin when origin is given. clear: true asks for the
        cell to be empty.
      selection: inclusive {min, max} with shape fill|replace|walls|shell|clear|sphere|hsphere|
        cylinder|hcylinder (with axis), block {id, meta?} and an optional replace selector.
      drawing: {origin: [x,y,z], layers, legend, stages?} in the format mb_view returns: layers
        bottom first, rows north to south, one character per block west to east, legend
        {char: {id, meta?, item?, verify?, replace?, click?, expect?}}; '.', ' ' and '+' are left alone. Dictionary
        layers with y use that absolute height, including subsets or gaps; plain row lists use
        consecutive heights from origin.
    Registry ids are required. A cell, legend entry or selection block without meta accepts any
    variant of the block, which is what you want for blocks that face the way they are placed
    (furnace, chest, machines); give meta to demand one. The receipt's anyMeta lists the ids read
    that way. Tile NBT is refused, not ignored: the job places blocks and makes the clicks you
    name, so whether a piece joined its neighbours is yours to check (an expect can), and anything
    set in a GUI is yours to do after.

    What the job does. It walks, places from the inventory you carry with ordinary clicks, and digs
    out the cells marked clear. Inside the plan it needs no permission. allow_place lets it put
    scaffold blocks outside the plan, allow_break lets it dig outside the plan to get somewhere;
    without them it touches plan cells only. replace_existing lets it dig out a plan cell that holds
    a different block; a plan cell that already matches is never dug through. Tall grass, a snow
    layer or water in a cell is not an occupant: the block goes in as it would by hand. Nothing is
    checked against your inventory up front: the job builds what it can and stops when it runs out.
    timeout_ticks is the job's budget in game ticks.

    Build order. Every cell that must hold a block has a step: its stage, then its height. The job
    works only the cells of steps up to the current one and moves on when they all match. With no
    stages named every cell is stage 0, so a plan goes up one layer at a time from the bottom.
    Nothing, neither a plan block nor a scaffold, is placed in a cell whose step has not come; cells
    that must be empty are not delayed. Stages put one part of a plan after another: a drawing may
    carry stages, an ordered list of strings of legend characters, first stage first (a character
    no stage names is in the first); a cell may carry stage: n (0 is first); a selection is one
    stage. Inside a stage the order is still bottom-up. Stage what must stand before something else
    is placed against or between it.

    Example, no stages: a closed room 5 x 5 and 4 high.
      {"origin": [100, 64, 200], "legend": {"#": {"id": "minecraft:cobblestone"}},
       "layers": [["#####", "#####", "#####", "#####", "#####"],
                  ["#####", "#...#", "#...#", "#...#", "#####"],
                  ["#####", "#...#", "#...#", "#...#", "#####"],
                  ["#####", "#####", "#####", "#####", "#####"]]}
      steps: y64 floor (25 cells), y65 wall row (16), y66 wall row (16), y67 roof (25).
      Drawn like this it has no way in: put '.' where the door goes.
    Example, stages: a base, two blocks standing on it and a line of blocks joining them.
      {"origin": [100, 64, 200], "stages": ["#", "AB", "j"],
       "legend": {"#": {"id": "minecraft:stonebrick"}, "A": {"id": "mod:machine"},
                  "B": {"id": "mod:container"}, "j": {"id": "mod:pipe"}},
       "layers": [["#####"], ["AjjjB"]]}
      steps: stage 0 y64 base (5), stage 1 y65 the two ends (2), stage 2 y65 the line (3).
      The line is placed last, so both ends stand when its pieces go in.

    Clicks, for a block whose facing, side or connection comes from how it is clicked in or from a
    click made on it afterwards. All of it is optional; leave out what does not matter.
      click, on a cell or on a legend entry (then every cell drawn with that character):
        {face?, hit?, look?, sneak?}. face: which face of the block beside the cell is clicked (up is
        the top of the block below; east is the east face of the block to its west). hit: [x, y, z],
        the point on that block, 0..1 each. look: {toward: north|south|east|west|up|down} or
        {yaw?, pitch?} (each [low, high] in degrees; yaw 0 south, 90 west; pitch 90 straight down): how
        you face as you click. sneak: held unless false. {} asks only for a careful, checked click.
      expect, beside click: up to 4 of {method, params?, pos?, path, equals | contains | changed: true}:
        an obs.* read (of the cell unless pos is given), a path into its result (a.b[0].c, "" the
        whole) and what must hold after the click. The job compares; it never interprets the value.
      uses: [{pos, item, click?, expect?, id?, stage?, name?}], right clicks on blocks that stand, in
        the order given: item is a selector {id, meta?, ...} to hold or {empty: true}, face and hit
        are of the block itself, sneak is held only if true, id is what must stand there. pos is
        relative to origin (the drawing's). uses may be the whole call, with no cells.
    In each stage the plain cells go first, then its click cells (the job orders them: what a click
    lands on stands first, and a block that would hide another's click goes after it), then its uses.
    A job takes at most 256 clicks, and the click cells of one stage lie near each other (a plan
    that asks otherwise is refused as clicks_too_spread). A click is made from a place to
    stand with the face in plain view and in reach, and with allow_break the job may take out up to
    3 ordinary blocks that are in the way and puts the same blocks back (never a tile entity, a
    fluid, a plan cell or a block within 4 of a tile entity).
    Example: a machine placed while you face south, pipes laid each against the one before, a tool
    used on the machine's top.
      mb_build(drawing={"origin": [100, 64, 200], "stages": ["#", "M", "p"],
                        "legend": {"#": {"id": "minecraft:stonebrick"},
                                   "M": {"id": "mod:machine", "click": {"look": {"toward": "south"}}},
                                   "p": {"id": "mod:pipe", "click": {"face": "east"}}},
                        "layers": [["#####"], ["Mppp."]]},
               uses=[{"pos": [0, 1, 0], "item": {"id": "mod:tool"}, "click": {"face": "up"},
                      "expect": [{"method": "obs.block", "path": "meta", "changed": True}]}])

    Receipt. state succeeded (reason schematic_verified) means every cell was looked at again and
    matches. Every receipt has jobId, placed, removed, left {count, first: up to 8 cells still
    wrong}, step {stage, y, index, of, left, first} (where the order stands: the index-th of `of`
    steps, `left` cells of it and of earlier steps still wrong), symptoms (what happened to you, as
    in mb_mine), and labels (the region notes of yours the build touches). A plan with clicks adds
    clicks {of, done, verified (its expect held), unverified, alreadyPresent, unverifiedFirst: up to
    8}; the full list is in the job's .clicks.jsonl. Before it ends, a job puts back what it took out
    for a click and takes away the scaffolds it placed outside the plan (state closing while it
    does); accessLeft and scaffoldLeft {count, first} list what it could not, and are yours to mend.
    A job that ends any other way adds stopped {reason, pos, step: {stage, y}}: one reason, and the
    one cell it is about (pos is absent only when no cell is to blame). In a click phase step is
    {stage, phase: clicks|uses} and stopped.click is that click in full: what was aimed at and hit,
    what is present, what the expect read, and for a click with no stance what blocks the view. The
    reasons:
      occupied           pos wants a block and holds a different one, and replace_existing is false.
                         A plan that starts that way is refused before any input (state failed), with
                         `occupied` {count, first: up to 8}.
      missing_materials  nothing you carry goes into any cell the order allows now; pos is one such
                         cell and `missing` lists {selector, needed, allocated, missing}.
      attempt_limit      8 clicks the game took into pos without the block appearing there.
      mismatch           what is at pos, or what the click would make there, is another variant than
                         the plan's (a facing): give the cell a click with a look, or leave its meta out.
      no_stance          no standing spot from which a face to place pos against is in view; often
                         there is nothing to place it against yet (stage it later, or allow_place).
                         Where pos holds a block to remove, none from which that block is in view:
                         `blockedBy` {pos, id} is what the view ends on, a block outside the plan.
      no_route           everything reachable was searched and none of it is a place to work pos from.
      stalled            200 ticks (the stallTicks setting) with nothing placed, cleared or pending and
                         no new ground stood on, and neither of the two above explains it.
      timeout            timeout_ticks ran out.
      requested          mb_build_pause.
      no_vantage         a click: there are places to stand but from none is the face in view and reach
                         (stopped.click.blocking names what is in the way; allow_break may open it).
      look_unreachable   a click: the face can be clicked, but not while facing the way look asks.
      support_missing    a click: no block stands where the click would have to land.
      hit_not_on_face    a click: the game's look did not land on the hit point (stopped.click.shape:
                         [min, max] of what the block fills of its cell, as it showed then).
      no_route_from_here a click: a stance exists and no walk reaches it (allow_place may).
      aim_mismatch       a click: aimed at the point from 16 stances and the game's ray hit elsewhere.
      placement_rejected a click: the game took 3 clicks and no block appeared.
      expect_failed, expect_timeout   a click was made and the read did not show what expect asks,
                         or did not answer. The click is not made again.
      gui_opened         a use opened a screen; the job closed it and stopped. The use counts as made.
      unknown_after_restart   a use was cut off between the press and its result: look, then resume.
      use_target_changed a use: the block at pos is not the id it names.
      access_failed      a block in the way of a click could not be taken out.
      no_empty_hand      a use with {empty: true}: no hotbar slot is empty.
    A job can also end as any job does: player_died, or cancelled (superseded, interrupted,
    gui_opened, world_or_player_changed), or with the game's own error text as the reason.
    Protected regions (mb_memory protect) never refuse the cells or uses you name here. They
    bind the way there: scaffold, digging through, and a block taken out of a click's way are
    not done inside one unless override_protection is true.
    A stop is state paused, or failed (error code build_failed, the receipt inside) when it stalled
    or timed out with nothing done this session or never started. Either way mb_work_resume(jobId)
    continues it once what the reason names is dealt with; a resumed or repeated job reads the
    world, so what is built is not built again and nothing of a later step was begun.
    A finished or failed build is journaled as an auto world note at its location.
    """
    if drawing is not None:
        if cells is not None or selection is not None: raise ValueError("provide exactly one of cells, selection or drawing")
        cells, origin = plan.from_drawing(drawing)
    params = _plan(cells, selection, uses, origin, size, replaceExisting=replace_existing, overrideProtection=override_protection,
                   allowBreak=allow_break, allowPlace=allow_place, timeoutTicks=timeout_ticks)
    return _with(_labelled(_build_call("nav.build", params), params), "anyMeta", _any_meta(params))


def _labelled(receipt: Any, params: dict) -> Any:
    """Name the region notes of the model's own that a build touched: its plan, said back to it, never a refusal."""
    if not isinstance(receipt, dict): return receipt
    at = params.get("origin") or [0, 0, 0]
    if "selection" in params: spots = [params["selection"].get("min"), params["selection"].get("max")]
    else: spots = [[at[i] + c["pos"][i] for i in range(3)] for c in params.get("cells", []) + params.get("uses", [])]
    if not spots or not all(isinstance(s, list) for s in spots): return receipt
    labels = plan.labels_at(kernel(), [min(s[i] for s in spots) for i in range(3)], [max(s[i] for s in spots) for i in range(3)])
    return {**receipt, "labels": labels} if labels else receipt


@tool(lane="read", coverage=["machine"])
def mb_schematic_import(path: str, origin: list[int] | None = None, include_air: bool = False) -> Any:
    """Import a file under the game's schematics/ directory without building: MCEdit .schematic or a canonical JSON plan.

    Java reads only those two formats (Sponge .schem and Litematica are not supported). Returns
    {plan:{cells,origin,size},size:[w,h,l],count,skipped:{air,unknown},tileEntities}; the nested plan
    is the spec mb_build_preview/mb_build take. Unknown legacy IDs are skipped and counted, never
    guessed. Tile entity NBT is never attached to placement.
    """
    params: dict[str, Any] = {"path": path, "includeAir": include_air}
    if origin is not None: params["origin"] = origin
    return kernel().call("nav.schematic_import", **params)


@tool(rung=1, lane=lambda kw: "read" if kw.get("preview", True) else "act", coverage=["machine"])
def mb_schematic_build(path: str, origin: list[int] | None = None, include_air: bool = False,
                       preview: bool = True, timeout_ticks: int = 12000,
                       allow_break: bool | None = None, allow_place: bool | None = None,
                       replace_existing: bool | None = None, override_protection: bool | None = None) -> Any:
    """Import a schematic, then preview (default) or build it as mb_build does (at most 4096 cells a job).

    Set preview=false only after reviewing the material/conflict preview. The nested plan of the
    import result is forwarded; omitted options keep Java's defaults, explicit ones override them.
    """
    imported = mb_schematic_import(path, origin, include_air)
    spec = _spec(imported, allowBreak=allow_break, allowPlace=allow_place, replaceExisting=replace_existing,
                 overrideProtection=override_protection, timeoutTicks=None if preview else timeout_ticks)
    result = _build_call("nav.build_preview" if preview else "nav.build", spec)
    return {"imported": {k: imported.get(k) for k in REPORT_KEYS if k in imported}, "request": _echo(spec),
            "preview" if preview else "result": result}


@tool(rung=1, lane=lambda kw: "act" if kw.get("build") else "read", coverage=["machine"])
def mb_copy(bounds: dict, origin: list[int] | None = None, include_air: bool = False,
            at: list[int] | None = None, preview: bool = False, build: bool = False,
            allow_break: bool = False, allow_place: bool = False, replace_existing: bool = False,
            override_protection: bool = False, timeout_ticks: int = 12000) -> Any:
    """Copy loaded blocks inside inclusive bounds {min,max} into a build plan; optionally rebuild it elsewhere.

    Java returns {plan:{cells,origin,size},size,count,skipped,tileEntities} with cell positions
    relative so that bounds.min maps to origin (default [0,0,0]); tile state is counted, never
    copied. With preview=true or build=true the nested plan is
    forwarded to nav.build_preview/nav.build at `at` (default: the copy origin),
    so one call copies a region and rebuilds it at another position. Unloaded cells fail.
    """
    if not isinstance(bounds, dict) or not isinstance(bounds.get("min"), list) or not isinstance(bounds.get("max"), list):
        raise ValueError("bounds must contain min and max [x,y,z]")
    params: dict[str, Any] = {"bounds": bounds, "includeAir": include_air}
    if origin is not None: params["origin"] = origin
    plan = kernel().call("nav.copy", **params)
    if not (preview or build):
        return plan
    spec = _spec(plan, origin=at, allowBreak=allow_break, allowPlace=allow_place, replaceExisting=replace_existing,
                 overrideProtection=override_protection, timeoutTicks=timeout_ticks if build else None)
    result = _build_call("nav.build" if build else "nav.build_preview", spec)
    return {"copied": {k: plan.get(k) for k in REPORT_KEYS if k in plan}, "request": _echo(spec),
            "result" if build else "preview": result}


@tool(lane="read", coverage=["move", "machine"])
def mb_scan(blocks: list[dict] | None = None, bounds: dict | None = None, cursor: list[int] | None = None,
            limit: int = 256, max_s: float = 20.0, detail: str = "summary") -> Any:
    """Scan loaded blocks in bounds {min:[x,y,z],max:[x,y,z]} for selectors {id, meta?} / {ore:"oreIron"} / {item:{...}}.

    One call covers the whole volume: it is split into layers and paged for you, and stops at
    limit matches (1..256), at the end (done:true), or after max_s seconds (1..120); a value outside
    those ranges is brought inside and the result's clamped says so. If done is false,
    call again with the returned cursor and the same bounds. The footprint may be at most
    512x512 blocks. Selectors are exact: {ore:...} takes a full ore-dictionary name, not a
    prefix. GregTech ore that is still buried has no name (null, counted in notRevealed): the
    pack does not tell the client what an ore is until a face is exposed, so the scan shows
    THAT ore is there, and the vein's exposed ores or prospecting say WHAT it is.
    Unloaded cells are counted, never loaded or generated. Matches are observations, not
    proof that mining will succeed (you may lack the tool to harvest them).
    detail="summary" (default) answers per kind of block: count, bounding box and the 8
    positions nearest you. "rows" lists every match as pos/id/meta/name; "full" adds the
    picked and placement items with their NBT, which is large: ask for it on a small box.
    """
    if bounds is None: raise ValueError("bounds are required")
    lo, hi = bounds["min"], bounds["max"]
    area = (hi[0] - lo[0] + 1) * (hi[2] - lo[2] + 1)
    if area > 262144: raise ValueError("scan footprint exceeds 512x512 blocks; scan a smaller area")
    height = max(1, 262144 // area)  # the bridge caps one scan at 262144 cells, so taller volumes go layer by layer
    layers = [(y, min(y + height - 1, hi[1])) for y in range(lo[1], hi[1] + 1, height)]
    layer, inner = cursor or [0, 0]
    asked = {"limit": limit, "max_s": max_s}
    limit, max_s = max(1, min(limit, 256)), max(1.0, min(max_s, 120.0)); deadline = time.monotonic() + max_s
    out = {"matches": [], "scanned": 0, "unloaded": 0, "volume": area * (hi[1] - lo[1] + 1), "done": False}
    clamped = {k: {"asked": v, "used": {"limit": limit, "max_s": max_s}[k]} for k, v in asked.items() if v != {"limit": limit, "max_s": max_s}[k]}
    if clamped: out["clamped"] = clamped
    while layer < len(layers) and len(out["matches"]) < limit and time.monotonic() < deadline:
        box = {"min": [lo[0], layers[layer][0], lo[2]], "max": [hi[0], layers[layer][1], hi[2]]}
        page = kernel().call("obs.scan", bounds=box, cursor=inner, limit=limit - len(out["matches"]), budget=4096,
                             **({"blocks": blocks} if blocks is not None else {}))
        out["matches"] += page["matches"]; out["scanned"] += page["scanned"]; out["unloaded"] += page["unloaded"]
        layer, inner = (layer + 1, 0) if page["done"] else (layer, page["cursor"])
    out["done"] = layer >= len(layers)
    if not out["done"]: out["cursor"] = [layer, inner]
    if detail == "full": return out
    rows = [{"pos": m.get("pos"), "id": m.get("id"), "meta": m.get("meta"), "name": (m.get("pickedItem") or {}).get("name")} for m in out["matches"]]
    hidden = [r for r in rows if _untranslated(r["name"])]
    for r in hidden: r["name"] = None
    out["found"] = len(rows)
    if hidden: out["notRevealed"] = {"count": len(hidden), "meaning": "name is null: the game has not told the client which variant these are "
                                     "(buried ore stays unnamed until a face is exposed). Their neighbours or the vein's exposed ores say what they likely are."}
    if detail == "rows": out["matches"] = rows; return out
    me = kernel().call("obs.player").get("pos") or [0, 0, 0]; kinds = {}
    for row in rows: kinds.setdefault((row["id"], row["meta"], row["name"]), []).append(row["pos"])
    del out["matches"]
    out["kinds"] = [{"id": k[0], "meta": k[1], "name": k[2], "count": len(at),
                     "box": {"min": [min(p[i] for p in at) for i in range(3)], "max": [max(p[i] for p in at) for i in range(3)]},
                     "nearest": sorted(at, key=lambda p: sum((p[i] - me[i]) ** 2 for i in range(3)))[:8]}
                    for k, at in sorted(kinds.items(), key=lambda kv: -len(kv[1]))]
    return out


def _untranslated(name: str | None) -> bool:
    """A display name that is still a lang key ("tile.foo.0.name"): the client lacks what it needs to name the block."""
    return bool(name) and name.endswith(".name") and " " not in name


@tool(rung=1, lane="control", coverage=["machine"])
def mb_build_pause() -> Any:
    """Pause the active build and return its receipt (stopped.reason requested); mb_work_resume continues it.

    A build that took blocks out for a click or placed scaffolds puts them right first: the receipt then says state
    closing, and the mb_build call that is waiting (or mb_work_status(jobId)) has the final one."""
    return kernel().call("nav.build_pause")


@tool(lane="read", coverage=["machine"])
def mb_build_materials() -> Any:
    """Read approximate placeable states in current inventory without changing work."""
    return kernel().call("nav.build_materials")


@tool(lane="read", coverage=["move", "machine"])
def mb_work_status(job_id: str) -> Any:
    """Read a bounded durable mining/build summary, progress and last receipt.

    Active execution is also visible in nav.status. Job identity and
    checkpoints survive a client restart; active execution does not. Resume performs
    fresh observation before continuing and never assumes an in-flight effect failed.
    The complete plan and per-click ledger stay on disk; large collections are reported as
    {omitted: true, count: N}.
    """
    return kernel().call("nav.work_status", jobId=job_id)


@tool(rung=1, coverage=["move", "machine"])
def mb_work_resume(job_id: str, options: dict | None = None) -> Any:
    """Resume a durable blocked/interrupted mining or build job, or wait again on a suspended one.

    A job whose step ended returns state "suspended" with a suspendedJobId. A suspended job of any kind (mine, build, goto, process, fight) is held where it stopped,
    break progress and path kept, and runs on whenever the world runs; this call waits on it
    as it is, and returns at once with its outcome if it already finished. resume=N steps it.

    Options may supply a fresh timeoutTicks (the job's own budget when not given) and explicit per-attempt permissions,
    including overrideProtection, a stallTicks for this session, and (mining) retry: true to
    try again the targets earlier sessions found unreachable (skipped otherwise).
    Native recovery re-observes world and inventory; already delivered placement/mining
    input is not blindly replayed.
    """
    # Without a fresh budget the job keeps its own, which is not known here: wait as long as the longest one.
    try: receipt = notes.tracked("nav.resume", _wait((options or {}).get("timeoutTicks", 72000) + CLOSING), jobId=job_id, **(options or {}))
    except BridgeError as error:
        failed = ((error.reply or {}).get("error") or {}).get("receipt")
        if isinstance(failed, dict): _left(failed)
        raise
    return _left(receipt) if isinstance(receipt, dict) else receipt
