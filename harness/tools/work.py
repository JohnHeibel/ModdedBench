# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Navigation, mining, building, schematics/copy, scans and the source engine's settings/cache.

Every tool forwards one Java method; Java validates ranges and shapes. Work outcomes and arrivals
pass through ``notes.tracked`` so nearby world notes surface and important outcomes are journaled.
"""

from __future__ import annotations

from typing import Any

from mbtool import kernel, tool
from mbtools_gtnh import notes

STAGE = 4096
REPORT_KEYS = ("size", "count", "skipped", "tileEntities")


def _build_call(method: str, params: dict, timeout_s: float | None = None) -> Any:
    """Direct request for small plans; bounded staging (baritone.build_stage) for large cell lists."""
    cells = params.get("cells")
    if not isinstance(cells, list) or len(cells) <= STAGE:
        return notes.tracked(method, timeout_s, **params)
    spec = {key: value for key, value in params.items() if key != "cells"}
    begun = kernel().call("baritone.build_stage", operation="begin", spec=spec)
    stage_id = begun.get("stageId") if isinstance(begun, dict) else None
    if not isinstance(stage_id, str) or not stage_id:
        raise ValueError("build_stage begin did not return stageId")
    for start in range(0, len(cells), STAGE):
        appended = kernel().call("baritone.build_stage", operation="append", stageId=stage_id,
                                 offset=start, cells=cells[start:start + STAGE])
        if not isinstance(appended, dict) or appended.get("stageId") != stage_id or appended.get("count") != min(start + STAGE, len(cells)):
            raise ValueError("build_stage append returned an invalid count")
    finished = kernel().call("baritone.build_stage", operation="finish", stageId=stage_id)
    plan_id = finished.get("planId") if isinstance(finished, dict) else None
    if not isinstance(plan_id, str) or not plan_id or finished.get("stageId") != stage_id or finished.get("count") != len(cells):
        raise ValueError("build_stage finish did not return the complete plan")
    forwarded = {"planId": plan_id}
    for key in ("timeoutTicks", "allowBreak", "allowPlace", "overrideProtection"):
        if key in params:
            forwarded[key] = params[key]
    return notes.tracked(method, timeout_s, **forwarded)


def _spec(plan: dict, **overrides) -> dict:
    """The build spec inside an import/copy result (report fields dropped), with explicit overrides applied."""
    if not isinstance(plan, dict) or not isinstance(plan.get("cells"), list):
        raise ValueError("Java did not return a plan with cells")
    spec = {k: v for k, v in plan.items() if k not in REPORT_KEYS}
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
    return notes.tracked("baritone.route", timeout_s, name=name, reverse=reverse, startIndex=start_index,
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
    return notes.tracked("baritone.follow", timeout_s, target=target, durationTicks=duration_ticks,
                         radius=radius, offsetDistance=offset_distance, offsetDirection=offset_direction,
                         allowBreak=allow_break, allowPlace=allow_place, overrideProtection=override_protection)


@tool(rung=1, coverage=["move"])
def mb_process(process: str, duration_ticks: int = 1200, goal: dict | None = None,
               center: list[int] | None = None, radius: int = 24, block: dict | None = None,
               allow_break: bool = False, allow_place: bool = False,
               explore_for_blocks: bool = True, open_on_arrival: bool = False,
               enter_portal: bool = False, override_protection: bool = False,
               timeout_s: float = 90.0) -> Any:
    """Run one bounded source process: goal, explore, get_to_block, or farm.

    goal needs a structured source goal for process='goal': block, near, adjacent,
    two_blocks, xz, y, axis, inverted, composite, or run_away. get_to_block needs
    block {id,meta?}; explore uses center (defaults to player feet) with no
    radius bound. Farm uses center and radius 1..64. duration_ticks is simulation time, whereas timeout_s is the real RPC
    wait. Goal/get_to_block report success only when source completion reaches their
    native condition. Explore and farm report success when their bounded duration
    ends; this does not claim all terrain was explored or all mod crops handled.
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
    return notes.tracked("baritone.process", timeout_s, **params)


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
    """
    if operation not in {"get", "set", "reset"}:
        raise ValueError("operation must be get, set or reset")
    if operation in {"set", "reset"} and values is None:
        raise ValueError("set/reset requires values")
    params: dict[str, Any] = {"operation": operation, "query": query, "save": save}
    if values is not None:
        params["values"] = values
    return kernel().call("baritone.settings", **params)


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
    return kernel().call("baritone.cache", **params)


@tool(rung=1, coverage=["move"])
def mb_mine(blocks: list[dict], items: list[dict], quantity: int = 1,
            bounds: dict | None = None, radius: int = 24,
            allow_break: bool = False, allow_place: bool = False,
            override_protection: bool = False, timeout_ticks: int = 12000,
            timeout_s: float = 600.0) -> Any:
    """Run bounded native quantity mining and return its terminal receipt.

    blocks and items are explicit block/item selectors; quantity means net matching
    inventory gain from the job baseline. Supply inclusive world bounds, or radius
    1..64 around the player. The job scans, approaches safe faces, mines with native
    tools, loiters/paths for matching drops, counts actual inventory gain and records
    unreachable targets. It stops on full inventory and never equates a vanished
    block with collection. A returned jobId is durable; inspect with mb_work_status
    and use mb_work_resume after correcting a blocked job. Protection override and
    terrain permissions apply only to this attempt.
    """
    params = dict(blocks=blocks, items=items, quantity=quantity, radius=radius,
                  allowBreak=allow_break, allowPlace=allow_place,
                  overrideProtection=override_protection, timeoutTicks=timeout_ticks)
    if bounds is not None: params["bounds"] = bounds
    return notes.tracked("baritone.mine", timeout_s, **params)


@tool(lane="read", coverage=["machine"])
def mb_build_preview(cells: list[dict] | None = None, selection: dict | None = None,
                     origin: list[int] | None = None, replace_existing: bool = False,
                     override_protection: bool = False, allow_break: bool = False,
                     allow_place: bool = False, mode: str = "blueprint",
                     settings: dict | None = None, size: list[int] | None = None) -> Any:
    """Read-only fresh build diff and shared-inventory material allocation.

    Provide exactly one of cells or selection. Cells use {pos,id,meta?,item?,
    placement?,verify?:{pickedItem:itemSelector},clear?,replace?}. Selection uses inclusive bounds plus shape
    fill|replace|walls|shell|clear|sphere|hsphere|cylinder|hcylinder (with axis), block and optional
    replace selector. Explicit registry IDs are required. Tile NBT is rejected rather than ignored.
    Preview does not load chunks, reserve inventory, prove reachability or mutate the world.
    """
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode,
              "cells" if cells is not None else "selection": cells if cells is not None else selection}
    if origin is not None: params["origin"] = origin
    if settings is not None: params["settings"] = settings
    if size is not None: params["size"] = size
    return _build_call("baritone.build_preview", params)


@tool(rung=1, coverage=["machine"])
def mb_build(cells: list[dict] | None = None, selection: dict | None = None,
             origin: list[int] | None = None, replace_existing: bool = False,
             override_protection: bool = False, timeout_ticks: int = 12000,
             timeout_s: float = 600.0, allow_break: bool = False,
             allow_place: bool = False, mode: str = "blueprint",
             settings: dict | None = None, size: list[int] | None = None) -> Any:
    """Execute a bounded, explicit-cell or selection build and return its receipt.

    Preview first. Native preflight checks loaded cells, conflicts, protection,
    supported placement items and a shared inventory allocation. Placement and
    clearing use ordinary player input; no outside scaffolding/access excavation is
    created. Completion means a fresh ID/metadata comparison of every selected cell.
    Tile configuration, multiblock formation and machine state require separate
    normal-interaction adapters. Retain jobId for status or resume. A finished or
    failed build is journaled as an auto world note at its location.
    """
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode, "timeoutTicks": timeout_ticks,
              "cells" if cells is not None else "selection": cells if cells is not None else selection}
    if origin is not None: params["origin"] = origin
    if settings is not None: params["settings"] = settings
    if size is not None: params["size"] = size
    return _build_call("baritone.build", params, timeout_s)


@tool(lane="read", coverage=["machine"])
def mb_schematic_import(path: str, origin: list[int] | None = None, include_air: bool = False) -> Any:
    """Import a schematic file natively (MCEdit .schematic, Sponge .schem, Litematica, canonical JSON) without building.

    Returns the plan in the shape mb_build_preview/mb_build take as their spec (origin,
    cells...) plus size [w,h,l], count and skipped {air, unknown}. Unknown legacy IDs are
    skipped and counted, never guessed. Tile entity NBT is never attached to placement.
    """
    params: dict[str, Any] = {"path": path, "includeAir": include_air}
    if origin is not None: params["origin"] = origin
    return kernel().call("baritone.schematic_import", **params)


@tool(rung=1, lane=lambda kw: "read" if kw.get("preview", True) else "act", coverage=["machine"])
def mb_schematic_build(path: str, origin: list[int] | None = None, include_air: bool = False,
                       preview: bool = True, timeout_ticks: int = 12000, timeout_s: float = 600.0,
                       allow_break: bool | None = None, allow_place: bool | None = None,
                       replace_existing: bool | None = None, override_protection: bool | None = None,
                       settings: dict | None = None) -> Any:
    """Import a schematic, then preview (default) or build it with the strict build contract.

    Set preview=false only after reviewing the material/conflict preview. Omitted
    options keep whatever the import returned; explicit ones override it.
    """
    imported = mb_schematic_import(path, origin, include_air)
    spec = _spec(imported, allowBreak=allow_break, allowPlace=allow_place, replaceExisting=replace_existing,
                 overrideProtection=override_protection, settings=settings, timeoutTicks=None if preview else timeout_ticks)
    result = _build_call("baritone.build_preview" if preview else "baritone.build", spec, None if preview else timeout_s)
    return {"imported": {k: imported.get(k) for k in REPORT_KEYS if k in imported}, "request": _echo(spec),
            "preview" if preview else "result": result}


@tool(rung=1, lane=lambda kw: "act" if kw.get("build") else "read", coverage=["machine"])
def mb_copy(bounds: dict, origin: list[int] | None = None, include_air: bool = False,
            at: list[int] | None = None, preview: bool = False, build: bool = False,
            allow_break: bool = False, allow_place: bool = False, replace_existing: bool = False,
            override_protection: bool = False, settings: dict | None = None,
            timeout_ticks: int = 12000, timeout_s: float = 600.0) -> Any:
    """Copy loaded blocks inside inclusive bounds {min,max} into a build plan; optionally rebuild it elsewhere.

    Java scans the region natively and returns the plan in the mb_build spec shape with
    positions relative to origin (default bounds.min) plus size, count and tileEntities
    (tile state is reported, never copied). With preview=true or build=true the plan is
    forwarded to baritone.build_preview/baritone.build at `at` (default: the copy origin),
    so one call copies a region and rebuilds it at another position. Unloaded cells fail.
    """
    if not isinstance(bounds, dict) or not isinstance(bounds.get("min"), list) or not isinstance(bounds.get("max"), list):
        raise ValueError("bounds must contain min and max [x,y,z]")
    params: dict[str, Any] = {"bounds": bounds, "includeAir": include_air}
    if origin is not None: params["origin"] = origin
    plan = kernel().call("baritone.copy", **params)
    if not (preview or build):
        return plan
    spec = _spec(plan, origin=at, allowBreak=allow_break, allowPlace=allow_place, replaceExisting=replace_existing,
                 overrideProtection=override_protection, settings=settings, timeoutTicks=timeout_ticks if build else None)
    result = _build_call("baritone.build" if build else "baritone.build_preview", spec, timeout_s if build else None)
    return {"copied": {k: plan.get(k) for k in REPORT_KEYS if k in plan}, "request": _echo(spec),
            "result" if build else "preview": result}


@tool(lane="read", coverage=["move", "machine"])
def mb_scan(blocks: list[dict] | None = None, bounds: dict | None = None, cursor: int = 0,
            limit: int = 256, budget: int = 4096) -> Any:
    """Paged native scan of loaded blocks using block/meta/ore/item selectors.

    Continue with returned cursor. The bounded scan reports unloaded cells and does
    not generate or load terrain. Scan results are observations, not mining success.
    """
    if bounds is None: raise ValueError("bounds are required")
    params = dict(bounds=bounds, cursor=cursor, limit=limit, budget=budget)
    if blocks is not None: params["blocks"] = blocks
    return kernel().call("baritone.scan", **params)


@tool(rung=1, lane="control", coverage=["machine"])
def mb_builder_pause() -> Any:
    """Pause active builder work and return its terminal receipt for this request."""
    return kernel().call("baritone.build_pause")


@tool(lane="read", coverage=["machine"])
def mb_builder_materials() -> Any:
    """Read approximate placeable states in current inventory without changing work."""
    return kernel().call("baritone.build_materials")


@tool(lane="read", coverage=["move", "machine"])
def mb_work_status(job_id: str) -> Any:
    """Read a bounded durable mining/build summary, progress and last receipt.

    Active execution is also visible in mb_status/baritone.status. Job identity and
    checkpoints survive a client restart; active execution does not. Resume performs
    fresh observation before continuing and never assumes an in-flight effect failed.
    The complete plan and per-click ledger stay on disk; large collections are
    reported as {omitted: true, count: N} to keep million-cell jobs inspectable.
    """
    return kernel().call("baritone.work_status", jobId=job_id)


@tool(rung=1, coverage=["move", "machine"])
def mb_work_resume(job_id: str, options: dict | None = None,
                   timeout_s: float = 600.0) -> Any:
    """Resume a durable blocked/interrupted mining or build job after correction.

    Options may supply a fresh timeoutTicks and explicit per-attempt permissions,
    including overrideProtection. Native recovery re-observes world and inventory;
    already delivered placement/mining input is not blindly replayed.
    """
    return notes.tracked("baritone.resume", timeout_s, jobId=job_id, **(options or {}))
