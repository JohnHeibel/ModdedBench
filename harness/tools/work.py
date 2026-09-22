# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Navigation, mining, building, schematics/copy, scans and the source engine's settings/cache.

Every tool forwards one Java method; Java validates ranges and shapes. Work outcomes and arrivals
pass through ``notes.tracked`` so nearby world notes surface and important outcomes are journaled.
"""

from __future__ import annotations

import json
import time
from typing import Any

from kernel import BridgeError
from mbtool import kernel, tool
from mbtools_gtnh import notes
from mbtools_gtnh import plan

STAGE = 4096
REPORT_KEYS = ("size", "count", "skipped", "tileEntities")


def _any_facing(params: dict) -> dict:
    """A hand-written cell without meta means "this block, any facing". Meta 0 is a facing no furnace, chest or machine can be
    placed with, so taken literally the builder would stand beside the cell for ever, unable to make what was asked."""
    cells = [c for c in params.get("cells") or [] if isinstance(c, dict) and "id" in c]
    masks = {**dict.fromkeys({c["id"] for c in cells} - {c["id"] for c in cells if "meta" in c}, 0), **(params.get("settings") or {}).get("metadataMasks", {})}
    return {**params, "settings": {**(params.get("settings") or {}), "metadataMasks": masks}} if masks else params


def _build_call(method: str, params: dict, timeout_s: float | None = None) -> Any:
    """Direct request for small plans; bounded staging (nav.build_stage) for large cell lists."""
    cells = params.get("cells")
    if not isinstance(cells, list) or len(cells) <= STAGE:
        return notes.tracked(method, timeout_s, **params)
    spec = {key: value for key, value in params.items() if key != "cells"}
    begun = kernel().call("nav.build_stage", operation="begin", spec=spec)
    stage_id = begun.get("stageId") if isinstance(begun, dict) else None
    if not isinstance(stage_id, str) or not stage_id:
        raise ValueError("build_stage begin did not return stageId")
    for start in range(0, len(cells), STAGE):
        appended = kernel().call("nav.build_stage", operation="append", stageId=stage_id,
                                 offset=start, cells=cells[start:start + STAGE])
        if not isinstance(appended, dict) or appended.get("stageId") != stage_id or appended.get("count") != min(start + STAGE, len(cells)):
            raise ValueError("build_stage append returned an invalid count")
    finished = kernel().call("nav.build_stage", operation="finish", stageId=stage_id)
    plan_id = finished.get("planId") if isinstance(finished, dict) else None
    if not isinstance(plan_id, str) or not plan_id or finished.get("stageId") != stage_id or finished.get("count") != len(cells):
        raise ValueError("build_stage finish did not return the complete plan")
    forwarded = {"planId": plan_id}
    for key in ("timeoutTicks", "allowBreak", "allowPlace", "overrideProtection"):
        if key in params:
            forwarded[key] = params[key]
    return notes.tracked(method, timeout_s, **forwarded)


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
             override_protection: bool = False, timeout_ticks: int = 1200,
             timeout_s: float = 90.0) -> Any:
    """Follow a saved route, approaching its first anchor then following bounded corridors.

    Each leg replans against current terrain; blocked corridors fail instead of
    silently taking a distant shortcut. reverse reverses the anchor order; start_index
    indexes that resulting order. A cancelled route can be restarted at the reported
    nextIndex. Raise both timeouts for long journeys (ticks <=72000); deadlines count
    simulation ticks, caller timeout counts real seconds. Protection still applies
    with allow_break/allow_place unless override_protection is explicitly true.
    Notes near the arrival position are returned under "notes".
    """
    return notes.tracked("nav.route", timeout_s, name=name, reverse=reverse, startIndex=start_index,
                         allowBreak=allow_break, allowPlace=allow_place,
                         overrideProtection=override_protection, timeoutTicks=timeout_ticks)


@tool(rung=1, coverage=["move"])
def mb_follow(target: dict, duration_ticks: int = 1200, radius: int = 2,
              offset_distance: float = 0, offset_direction: float = 0,
              allow_break: bool = False, allow_place: bool = False,
              override_protection: bool = False, timeout_s: float = 90.0) -> Any:
    """Follow loaded native entities through source FollowProcess for a bounded duration.

    target requires one or more exact selectors from entityId, uuid, type and name;
    every supplied selector must match. duration_ticks is simulated client time
    (1..72000), while timeout_s is only the real-time RPC wait and must cover that
    duration when the world is not paused. The action fails if no matching loaded
    entity remains. Radius is 0..64; offset direction/distance define the source
    GoalXZ follow offset. Completion after duration means the bounded follow window
    elapsed, not that the entity was reached or remains present afterwards.
    """
    if not isinstance(target, dict) or not target or not set(target) <= {"entityId", "uuid", "type", "name"}:
        raise ValueError("target needs entityId, uuid, type or name selectors")
    return notes.tracked("nav.follow", timeout_s, target=target, durationTicks=duration_ticks,
                         radius=radius, offsetDistance=offset_distance, offsetDirection=offset_direction,
                         allowBreak=allow_break, allowPlace=allow_place, overrideProtection=override_protection)


@tool(rung=1, coverage=["combat"])
def mb_fight(entity_id: int | None = None, hold: bool = False, leash: float = 16, bail_health: float = 8,
             max_attackers: int = 2, weapon_slot: int | None = None, duration_ticks: int = 600,
             crit: bool = True, block: bool = True, ranged: dict | bool | None = None,
             timeout_s: float = 60.0) -> Any:
    """Fight one mob as a job, the way mb_mine mines: you choose the mob and the limits, it does the footwork.

    entity_id comes from mb_obs entities or the clock's threats. It paths to the mob (never breaking
    or placing), and in reach blocks with a sword between swings and times each swing to land while
    falling, a critical hit at the fastest rate the game counts. It backs away from a creeper it is
    fighting while that creeper swells. hold=True never moves: it hits the chosen mob, or with no
    entity_id the nearest hostile in sight, whenever one comes into reach, and succeeds once none is
    in sight. Against more than one melee mob, first stand where only one can reach you (a doorway,
    a one-wide tunnel, a pillar two blocks up) and use hold; pursuing one mob of a group walks you
    into the others. It never chooses to chase a different mob.
    weapon_slot is the hotbar slot to fight with (default: whatever is in hand). leash is how far
    from where you started the mob may be before the job stops chasing; bail_health is the health
    at which it stops; max_attackers is how many hostile mobs may be within 4 blocks.
    It stops, as a failure that the actionFailed guard turns into a pause, the moment the fight
    gets worse than the one you chose: health_at_bail_line, outnumbered, creeper_swelling (a
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
    """
    if entity_id is None and not hold:
        raise ValueError("entity_id is required unless hold=True")
    params = {"hold": hold, "leash": leash, "bailHealth": bail_health, "maxAttackers": max_attackers,
              "durationTicks": duration_ticks, "crit": crit, "block": block}
    if entity_id is not None: params["entityId"] = entity_id
    if weapon_slot is not None: params["weaponSlot"] = weapon_slot
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
        drag = min(1.0, max(.9, sum(b[0] / a[0] for a, b in pairs) / len(pairs)))
        gravity = min(.3, max(0.0, sum(a[1] * drag - b[1] for a, b in pairs) / len(pairs)))
        new = {"speed": (shot[0][0] ** 2 + shot[0][1] ** 2) ** .5 / drag, "gravity": gravity, "drag": drag}
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
               timeout_s: float = 90.0) -> Any:
    """Run one bounded source process: goal, explore, get_to_block, or farm.

    goal needs a structured source goal for process='goal': block, near, adjacent,
    two_blocks, xz, y, axis, inverted, composite, or run_away. Positions are arrays:
    {type:"block",pos:[x,y,z]}, {type:"near",pos:[x,y,z],radius:2}, {type:"xz",x:10,z:-20},
    {type:"y",y:64}. An xz goal ends wherever that column is reachable, which can be in
    water or a hole: prefer near/block with a y you have seen on the map or in a scan. get_to_block needs
    block {id,meta?}; explore uses center (defaults to player feet) with no
    radius bound. Farm uses center and radius 1..64. duration_ticks is simulation time, whereas timeout_s is the real RPC
    wait. Goal/get_to_block report success only when source completion reaches their
    native condition. Explore and farm report success when their bounded duration
    ends; this does not claim all terrain was explored or all mod crops handled.
    pathRules: which of your block rules decided about which block, as in mb_mine.
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


@tool(rung=1, coverage=["move"])
def mb_mine(blocks: list[dict] | None = None, items: list[dict] | None = None, quantity: int = 1,
            bounds: dict | None = None, radius: int = 24,
            allow_break: bool = False, allow_place: bool = False,
            override_protection: bool = False, timeout_ticks: int = 12000,
            timeout_s: float = 600.0, beside_fluid: bool | None = None,
            vein: list[int] | None = None) -> Any:
    """Run bounded native quantity mining and return its terminal receipt.

    blocks and items are explicit block/item selectors; quantity means matching
    inventory gain, summed over the job's sessions (each measured from its own start,
    so smelting or storing ore between sessions costs nothing). Supply inclusive world
    bounds, or radius 1..64 around the player. The job scans, approaches safe faces,
    mines, loiters/paths for matching drops, counts actual inventory gain and records
    unreachable targets. The tool for each block is whichever stack anywhere in your
    inventory the game itself says harvests it fastest; nothing is judged by its kind,
    and broken or near-empty tools are skipped. What a swing really did is measured:
    blocksBroken, and extraBroken/extraBrokenAt for blocks around the target that went
    too (a hammer, a vein miner, a laser); an extra break of a protected block stops
    the job. dropsLeftInBounds counts matching items still lying in the bounds when it
    ends: drops it never picked up. It stops on full inventory and never equates a vanished
    block with collection. A returned jobId is durable; inspect with mb_work_status
    and use mb_work_resume after correcting a blocked job. Protection override and
    terrain permissions apply only to this attempt.
    timeout_ticks is a budget, not a verdict: mining runs at a few blocks a minute
    (walking, digging down, tool swaps), and the receipt's blocksPerMinute is your
    measure of it. A job that runs out of budget with something gained stops as
    paused (reason timeout_with_progress), which is not a failure: mb_work_resume
    continues it. Paused or failed is judged on this session's gain alone, and so is
    blocksPerMinute. Forty seconds standing in one spot with nothing gained ends it
    as stalled_no_progress_near_x,y,z: that target is not reachable the way it is
    being tried.
    vein=[x,y,z], one ore block you have seen, mines the vein it belongs to: bounds become
    the ore chunk that block's vein is centred on plus the chunks around it, 8 blocks up
    and down; blocks defaults to that block's id and items to GregTech raw ore. Ask for the
    quantity the next chapter needs, with allow_break and allow_place.
    beside_fluid (default: on whenever allow_place is) breaks blocks that have water or oil
    beside or above them and, on the next tick, before the fluid moves, puts a throwaway
    block (cobblestone, dirt: keep a stack in the hotbar) where the broken one was; plugged
    lists them. Lava is never mined beside. Without it such targets just look unreachable:
    the receipt's refused lists each with the fluid cell beside it.
    symptoms: what happened to you during the job (damage and its type, effects gained or
    lost, air lost, burning, webbed, slowed), each first seen with the feet/head/under blocks
    there and a count. pathRules: which of your block rules (hazards, standOn, neverStandOn,
    blocksToDisallowBreaking; see mb_settings) decided about which block, as search checks.
    """
    params = dict(blocks=blocks, items=items, quantity=quantity, radius=radius,
                  allowBreak=allow_break, allowPlace=allow_place,
                  overrideProtection=override_protection, timeoutTicks=timeout_ticks)
    if vein is not None:
        chunk = lambda v: min((c for c in range((v >> 4) - 2, (v >> 4) + 3) if abs(c) % 3 == 1), key=lambda c: abs(c * 16 + 8 - v))  # veins centre on chunks where |c| % 3 == 1
        cx, cz = chunk(vein[0]), chunk(vein[2])
        bounds = {"min": [(cx - 1) * 16, max(1, vein[1] - 8), (cz - 1) * 16], "max": [(cx + 2) * 16 - 1, min(254, vein[1] + 8), (cz + 2) * 16 - 1]}
        params["blocks"] = blocks or [{"id": kernel().call("obs.block", x=vein[0], y=vein[1], z=vein[2])["id"]}]
        params["items"] = items or [{"id": "gregtech:gt.metaitem.03"}]
    elif not blocks or not items: raise ValueError("blocks and items are required unless vein is given")
    if allow_place if beside_fluid is None else beside_fluid: params["besideFluid"] = True
    if bounds is not None: params["bounds"] = bounds
    return notes.tracked("nav.mine", timeout_s, **params)


@tool(lane="read", coverage=["machine"])
def mb_build_preview(cells: list[dict] | None = None, selection: dict | None = None,
                     origin: list[int] | None = None, replace_existing: bool = False,
                     override_protection: bool = False, allow_break: bool = False,
                     allow_place: bool = False, mode: str = "blueprint",
                     settings: dict | None = None, size: list[int] | None = None,
                     drawing: dict | None = None) -> Any:
    """Read-only fresh build diff and shared-inventory material allocation.

    Provide exactly one of cells or selection. Cells use {pos,id,meta?,item?,
    placement?,verify?:{pickedItem:itemSelector},clear?,replace?}. Selection uses inclusive bounds plus shape
    fill|replace|walls|shell|clear|sphere|hsphere|cylinder|hcylinder (with axis), block and optional
    replace selector. A cell without meta accepts any meta, which is what you want for blocks that face
    the way they are placed (furnace, chest, machines); give meta to demand a variant or a facing.
    Explicit registry IDs are required. Tile NBT is rejected rather than ignored.
    drawing={origin:[x,y,z], layers, legend} is the third way to say what to build, in the format
    mb_view returns: layers bottom first, rows north to south, one character per block west to
    east, legend {char: {id, meta?}}; '.', ' ' and '+' are left alone. It is for bulk: floors, walls,
    roofs, rows of plain blocks. Place what faces, connects or is configured with the precise tools.
    Preview does not load chunks, reserve inventory, prove reachability or mutate the world.
    """
    if drawing is not None:
        if cells is not None or selection is not None: raise ValueError("provide exactly one of cells, selection or drawing")
        cells, origin = plan.from_drawing(drawing)
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode,
              "cells" if cells is not None else "selection": cells if cells is not None else selection}
    if origin is not None: params["origin"] = origin
    if settings is not None: params["settings"] = settings
    if size is not None: params["size"] = size
    return _build_call("nav.build_preview", _any_facing(params))


@tool(rung=1, coverage=["machine"])
def mb_build(cells: list[dict] | None = None, selection: dict | None = None,
             origin: list[int] | None = None, replace_existing: bool = False,
             override_protection: bool = False, timeout_ticks: int = 12000,
             timeout_s: float = 600.0, allow_break: bool = False,
             allow_place: bool = False, mode: str = "blueprint",
             settings: dict | None = None, size: list[int] | None = None,
             drawing: dict | None = None) -> Any:
    """Execute a bounded, explicit-cell or selection build and return its receipt.

    Preview first. Native preflight checks loaded cells, conflicts, protection,
    supported placement items and a shared inventory allocation. Placement and
    clearing use ordinary player input; no outside scaffolding/access excavation is
    created. Completion means a fresh ID/metadata comparison of every selected cell.
    Tile configuration, multiblock formation and machine state require separate
    normal-interaction adapters. Retain jobId for status or resume. A finished or
    failed build is journaled as an auto world note at its location. drawing: see mb_build_preview.
    The receipt's `labels` names the region notes of yours that the build touches.
    symptoms: what happened to you during the job, as in mb_mine.
    """
    if drawing is not None:
        if cells is not None or selection is not None: raise ValueError("provide exactly one of cells, selection or drawing")
        cells, origin = plan.from_drawing(drawing)
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode, "timeoutTicks": timeout_ticks,
              "cells" if cells is not None else "selection": cells if cells is not None else selection}
    if origin is not None: params["origin"] = origin
    if settings is not None: params["settings"] = settings
    if size is not None: params["size"] = size
    return _labelled(_build_call("nav.build", _any_facing(params), timeout_s), params)


def _labelled(receipt: Any, params: dict) -> Any:
    """Name the region notes of the model's own that a build touched: its plan, said back to it, never a refusal."""
    if not isinstance(receipt, dict): return receipt
    at = params.get("origin") or [0, 0, 0]
    if "cells" in params: spots = [[at[i] + c["pos"][i] for i in range(3)] for c in params["cells"]]
    else: spots = [params["selection"].get("min"), params["selection"].get("max")]
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
                       preview: bool = True, timeout_ticks: int = 12000, timeout_s: float = 600.0,
                       allow_break: bool | None = None, allow_place: bool | None = None,
                       replace_existing: bool | None = None, override_protection: bool | None = None,
                       settings: dict | None = None) -> Any:
    """Import a schematic, then preview (default) or build it with the strict build contract.

    Set preview=false only after reviewing the material/conflict preview. The nested plan of the
    import result is forwarded; omitted options keep Java's defaults, explicit ones override them.
    """
    imported = mb_schematic_import(path, origin, include_air)
    spec = _spec(imported, allowBreak=allow_break, allowPlace=allow_place, replaceExisting=replace_existing,
                 overrideProtection=override_protection, settings=settings, timeoutTicks=None if preview else timeout_ticks)
    result = _build_call("nav.build_preview" if preview else "nav.build", spec, None if preview else timeout_s)
    return {"imported": {k: imported.get(k) for k in REPORT_KEYS if k in imported}, "request": _echo(spec),
            "preview" if preview else "result": result}


@tool(rung=1, lane=lambda kw: "act" if kw.get("build") else "read", coverage=["machine"])
def mb_copy(bounds: dict, origin: list[int] | None = None, include_air: bool = False,
            at: list[int] | None = None, preview: bool = False, build: bool = False,
            allow_break: bool = False, allow_place: bool = False, replace_existing: bool = False,
            override_protection: bool = False, settings: dict | None = None,
            timeout_ticks: int = 12000, timeout_s: float = 600.0) -> Any:
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
                 overrideProtection=override_protection, settings=settings, timeoutTicks=timeout_ticks if build else None)
    result = _build_call("nav.build" if build else "nav.build_preview", spec, timeout_s if build else None)
    return {"copied": {k: plan.get(k) for k in REPORT_KEYS if k in plan}, "request": _echo(spec),
            "result" if build else "preview": result}


@tool(lane="read", coverage=["move", "machine"])
def mb_scan(blocks: list[dict] | None = None, bounds: dict | None = None, cursor: list[int] | None = None,
            limit: int = 256, max_s: float = 20.0, detail: str = "summary") -> Any:
    """Scan loaded blocks in bounds {min:[x,y,z],max:[x,y,z]} for selectors {id, meta?} / {ore:"oreIron"} / {item:{...}}.

    One call covers the whole volume: it is split into layers and paged for you, and stops at
    limit matches (1..256), at the end (done:true), or after max_s seconds. If done is false,
    call again with the returned cursor and the same bounds. The footprint may be at most
    512x512 blocks. Selectors are exact: {ore:...} takes a full ore-dictionary name, not a
    prefix. GregTech ore that is still buried reports meta 0 and a placeholder name: the
    pack does not tell the client what an ore is until it is exposed, so scan for the block
    id "gregtech:gt.blockores" to learn THAT ore is there and prospect to learn WHAT it is.
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
    limit, deadline = max(1, min(limit, 256)), time.monotonic() + max(1.0, min(max_s, 120.0))
    out = {"matches": [], "scanned": 0, "unloaded": 0, "volume": area * (hi[1] - lo[1] + 1), "done": False}
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
    out["found"] = len(rows)
    if detail == "rows": out["matches"] = rows; return out
    me = kernel().call("obs.player").get("pos") or [0, 0, 0]; kinds = {}
    for row in rows: kinds.setdefault((row["id"], row["meta"], row["name"]), []).append(row["pos"])
    del out["matches"]
    out["kinds"] = [{"id": k[0], "meta": k[1], "name": k[2], "count": len(at),
                     "box": {"min": [min(p[i] for p in at) for i in range(3)], "max": [max(p[i] for p in at) for i in range(3)]},
                     "nearest": sorted(at, key=lambda p: sum((p[i] - me[i]) ** 2 for i in range(3)))[:8]}
                    for k, at in sorted(kinds.items(), key=lambda kv: -len(kv[1]))]
    return out


@tool(rung=1, lane="control", coverage=["machine"])
def mb_build_pause() -> Any:
    """Pause active build work and return its terminal receipt for this request."""
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
    The complete plan and per-click ledger stay on disk; large collections are
    reported as {omitted: true, count: N} to keep million-cell jobs inspectable.
    """
    return kernel().call("nav.work_status", jobId=job_id)


@tool(rung=1, coverage=["move", "machine"])
def mb_work_resume(job_id: str, options: dict | None = None,
                   timeout_s: float = 600.0) -> Any:
    """Resume a durable blocked/interrupted mining or build job after correction.

    Options may supply a fresh timeoutTicks and explicit per-attempt permissions,
    including overrideProtection. Native recovery re-observes world and inventory;
    already delivered placement/mining input is not blindly replayed.
    """
    return notes.tracked("nav.resume", timeout_s, jobId=job_id, **(options or {}))
