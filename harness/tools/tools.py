# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Capability-driven controls and native NEI inspection for the GTNH bridge profile."""

from __future__ import annotations

import base64
import json
import time
from typing import Any

from mcp.server.fastmcp import Image
from mcp.types import CallToolResult, TextContent, ImageContent

from mbtool import kernel, tool


def _call(namespace: str, method: str, params: dict | None) -> Any:
    name = method if "." in method else f"{namespace}.{method}"
    if not name.startswith(namespace + "."):
        raise ValueError(f"method must belong to {namespace}")
    return kernel().call(name, **(params or {}))


def _build_call(method: str, params: dict, timeout_s: float | None = None) -> Any:
    """Use direct requests for small plans and bounded staging for large cells."""
    cells = params.get("cells")
    if not isinstance(cells, list) or len(cells) <= 4096:
        return kernel().call(method, **({"timeout": timeout_s} if timeout_s is not None else {}), **params)
    spec = {key: value for key, value in params.items() if key != "cells"}
    begun = kernel().call("baritone.build_stage", operation="begin", spec=spec)
    stage_id = begun.get("stageId") if isinstance(begun, dict) else None
    if not isinstance(stage_id, str) or not stage_id: raise ValueError("build_stage begin did not return stageId")
    for start in range(0, len(cells), 4096):
        appended = kernel().call("baritone.build_stage", operation="append", stageId=stage_id,
                                 offset=start, cells=cells[start:start + 4096])
        if not isinstance(appended, dict) or appended.get("stageId") != stage_id or appended.get("count") != min(start + 4096, len(cells)):
            raise ValueError("build_stage append returned an invalid count")
    finished = kernel().call("baritone.build_stage", operation="finish", stageId=stage_id)
    plan_id = finished.get("planId") if isinstance(finished, dict) else None
    if not isinstance(plan_id, str) or not plan_id or finished.get("stageId") != stage_id or finished.get("count") != len(cells):
        raise ValueError("build_stage finish did not return the complete plan")
    forwarded = {"planId": plan_id}
    for key in ("timeoutTicks", "allowBreak", "allowPlace", "overrideProtection"):
        if key in params: forwarded[key] = params[key]
    return kernel().call(method, **({"timeout": timeout_s} if timeout_s is not None else {}), **forwarded)


def _scan_chunks(bounds: dict):
    """Split an inclusive cuboid into scan-safe 64x64x64 pieces."""
    low, high = bounds.get("min"), bounds.get("max")
    if not isinstance(low, list) or not isinstance(high, list) or len(low) != 3 or len(high) != 3:
        raise ValueError("selection bounds must contain min/max [x,y,z]")
    for y in range(low[1], high[1] + 1, 64):
        for z in range(low[2], high[2] + 1, 64):
            for x in range(low[0], high[0] + 1, 64):
                yield {"min": [x, y, z], "max": [min(x + 63, high[0]), min(y + 63, high[1]), min(z + 63, high[2])]}


@tool(rung=0, coverage=["meta"])
def mb_methods() -> Any:
    """List bridge methods advertised by the GTNH profile."""
    return kernel().call("sys.methods")


@tool(rung=0, coverage=["meta"])
def mb_status() -> Any:
    """Return bridge capabilities and connection status advertised by the GTNH profile."""
    return kernel().call("sys.capabilities")


@tool(rung=3, effect="privileged", coverage=["meta"])
def mb_call(method: str, params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Call any advertised bridge method with JSON parameters.

    Long baritone.goto routes need both timeout_s and params.timeoutTicks raised;
    for example timeout_s=900 and timeoutTicks=16000 for a sustained journey.
    """
    return kernel().call(method, timeout=timeout_s, **(params or {}))


@tool(rung=0, coverage=["meta"])
def mb_obs(method: str, params: dict | None = None) -> Any:
    """Call an obs.* capability by short or full method name.

    First-class machine reads include tile {pos|x,y,z,detail:'full',hwyla?},
    nbt {handle,path,offset?,limit?}, waila/hwyla {pos}, and mixed batch
    {queries:{alias:{method,params}}}. Tile data and NBT provenance come from the
    authoritative server; server aliases in a batch share serverTick. Missing
    tanks do not prove no fluid, and reported side views may overlap.
    """
    return _call("obs", method, params)


@tool(rung=3, coverage=["move"])
def mb_act(method: str, params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Native act.* input/look/stop, use_block, use_entity, attack_entity, use_item,
    eat, select_hotbar, combat and status. Target blocks with x/y/z, face 0..5 and
    optional block-local hit [x,y,z] (default native face center); use_item
    fluid=true includes collidable fluids in the targeting ray.
    Raw input attack locks to the initial block and stops when it changes;
    allowRetarget=true explicitly enables continuous block attacking.
    Eat defaults to a 400-tick budget; nativeUseTicks exposes pack food penalties.
    Consumption acknowledgment releases use before another block interaction.
    Guard with expectedHeld (full observed stack), expected {id,meta} for blocks,
    expectedHandle for transient entityId. Combat: ticks, range<=3, intervalTicks,
    hostile=true, types=[exact entity type], entityId, stopWhenClear. Combat stays
    in place; compose navigation separately. Native acceptance isn't proof that a
    machine changed; inspect before/after receipts and your own postconditions.
    """
    name = method if "." in method else f"act.{method}"
    if not name.startswith("act."): raise ValueError("method must belong to act")
    return kernel().call(name, timeout=timeout_s, **(params or {}))


@tool(rung=3, coverage=["meta"])
def mb_interrupt(operation: str, name: str = "", spec: dict | None = None, replace: bool = False, event_id: str = "") -> Any:
    """Manage autonomous interrupts: add, remove, reload, status, ack. Add spec:
    queries={alias:{method,params}}, condition={'lt':['player.health',8]},
    effects=['notify','cancel','pause'] (independently selectable). Compose all/any/not,
    comparisons, changed/increased/decreased and collections. Or file='/absolute/path.py'
    defining evaluate(context), using context.values, context.read(method, **params)
    and context.state to compose arbitrary read primitives. Default oneShot=true;
    A spec prompt='...' or conditional return context.prompt('...', **observations)
    requests a new model decision through the interrupt journal. The external runner
    exposes modelPrompts; MCP hosts must consume events themselves. No inline inference.
    supports edge, consecutive, cooldown, timeout_s, operationScope and latch.
    Runs while the model thinks. Read events to learn triggers/faults. Cancel/pause
    latch new actions until ack(event_id); ack does not resume time. Faults disarm.
    Python edits reload without client restart; failed reload retains previous code.
    """
    from gtnh_interrupts import get_supervisor
    k = kernel()
    if operation == "ack":
        return k.call("interrupt.ack", eventId=event_id)
    supervisor = get_supervisor(k)
    if operation == "add": return supervisor.add(name, spec or {}, replace=replace)
    if operation == "remove": return {"removed": supervisor.remove(name)}
    if operation == "reload": return supervisor.reload(name)
    if operation == "status": return {"supervisor": supervisor.status(name or None), "client": k.call("interrupt.status")}
    raise ValueError("operation must be add, remove, reload, status or ack")


@tool(rung=0, coverage=["meta"])
def mb_interrupt_events(after: int = 0, limit: int = 100, wait_s: float = 0) -> Any:
    """Replay durable interrupt events after a cursor; non-consuming, gap reported.
    Optional bounded long-poll (0..30 seconds) waits without advancing world time.
    The runner can use gtnh_interrupts.race_interrupt to cancel an inference awaitable.
    """
    if after < 0 or not 1 <= limit <= 1000 or not 0 <= wait_s <= 30:
        raise ValueError("after>=0, limit 1..1000, wait_s 0..30 required")
    from gtnh_interrupts import get_supervisor
    return get_supervisor(kernel()).events(after, limit, wait_s)


@tool(rung=3, coverage=["meta"])
def mb_keys(method: str, params: dict | None = None) -> Any:
    """Call a keys.* capability by short or full method name."""
    return _call("keys", method, params)


@tool(rung=3, coverage=["meta"])
def mb_gui(method: str, params: dict | None = None) -> Any:
    """General UI primitives: click_slot, transfer, return_cursor, click_at, drag,
    scroll, key, type, text_field, button, container_button, hover, hit_test, status,
    open_inventory, close. Use mb_methods for parameter schemas. Native event clicks
    reach modded/ghost slots; structured clicks and exact transfers use ordinary slots.
    Observe windowId/epoch and pass expected stacks/cursor to guard stale state.
    Inspect receipts after partial effects; never retry a click merely because its
    visible slot did not change. Custom machine packets need their own postconditions.
    Resume world time before mutating. Build reusable routines in reloadable profile
    Python modules using these primitives; gtnh_ui.ContainerSession helps composition.
    """
    return _call("gui", method, params)


@tool(rung=0, coverage=["inventory"])
def mb_inventory(detail: str = "full", container: bool = False) -> Any:
    """Observe item identities with metadata/NBT, cursor and slot ownership.
    Player detail: full, compact, counts. Container detail: summary, full, compact;
    full includes bounded custom widget inspection. Container indices differ from
    player inventory indices. Use observed clickAt coordinates for native events.
    """
    return kernel().call("obs.container" if container else "obs.inventory", detail=detail)


@tool(rung=0, coverage=["inventory"])
def mb_find(selector: dict, scope: str = "player") -> Any:
    """Find actual held/container items by exact {id, meta?, nbt_hash?, nbt?}.
    Scope player returns player inventory indices; container returns current slot
    indices. Metadata and NBT variants remain separate. Use mb_search for NEI catalogue.
    """
    return kernel().call("inv.find", selector=selector, scope=scope)


@tool(rung=3, coverage=["inventory"])
def mb_transfer(window_id: int, epoch: int, source: int, expected: dict,
                destinations: list[int], count: int, destination_policy: str = "passive") -> Any:
    """Move up to count (1..64) items through native clicks to explicit ordinary slots.
    Pass observed source stack, windowId and epoch. Requires empty cursor; source
    must accept its remainder (output/ghost slots require event clicks instead).
    Capacity may produce a partial move: inspect transfer.moved. Passive destinations
    must retain items; consuming allows fuel/machine processing without assuming
    disappearance proves consumption. Receipt includes server transaction acceptance,
    immediate/settled deltas and cursor. Errors may contain partial effects. No retry.
    """
    return kernel().call("gui.transfer", windowId=window_id, epoch=epoch, source=source,
                         expected=expected, destinations=destinations, count=count,
                         destinationPolicy=destination_policy, expectedCursor=None)


@tool(rung=3, coverage=["inventory"])
def mb_click_slot(window_id: int, epoch: int, slot: int, expected: dict | None,
                  expected_cursor: dict | None, click_type: str = "pickup", button: int = 0,
                  path: str | None = None) -> Any:
    """Click an observed slot with explicit stale-stack/cursor guards.
    Pickup/quick_move/clone default to native events for custom and virtual slots.
    Swap (button=hotbar 0..8), throw and pickup_all default to structured ordinary
    slot clicks. Does not retry or infer crafting completion. Use gui.drag for native
    distribution and gui.return_cursor for recovery to explicit safe destinations.
    """
    params = dict(windowId=window_id, epoch=epoch, slot=slot, expected=expected,
                  expectedCursor=expected_cursor, type=click_type, button=button)
    if path is not None:
        params["path"] = path
    return kernel().call("gui.click_slot", **params)


@tool(rung=0, coverage=["meta"])
def mb_screenshot() -> Image:
    """Capture sys.screenshot, which returns PNG base64 plus width and height."""
    shot = kernel().call("sys.screenshot")
    try:
        return Image(data=base64.b64decode(shot["png"], validate=True), format="png")
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("sys.screenshot must return a base64 PNG in 'png'") from exc


@tool(rung=3, coverage=["move"])
def mb_stop() -> Any:
    """Stop the active GTNH bridge action via act.stop."""
    return kernel().call("act.stop")


@tool(rung=3, coverage=["time"])
def mb_time(method: str = "status", params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Control GTNH world time: status, pause, resume, configure, report_failure.

    Real time is the default: finishing an action does not pause machines or mobs.
    Pause explicitly for deliberation; resume to keep production running while thinking.
    Pause waits for coordinated client/server quiescence. Inspect status for pausing
    or pause_error; a timed-out pause leaves simulation gated. Resume explicitly
    before executing game actions. Screenshots, observations and cancellation remain
    available during pause. Exact stepping is not supported yet.
    Configure optional healthDrop, healthBelow, airBelow, foodBelow, burning, actionFailed and
    pauseOnDisconnect conditions. Threshold -1 disables it. Conditions pause globally
    and report a reason. Baritone terminal failures signal actionFailed automatically;
    agent-written procedures can call report_failure to signal their own failures.
    pauseOnDisconnect defaults true; set false for a planned
    client restart with continued server production, then restore it after reconnect.
    A controlling session must stay connected; observation-only sessions do not own time.
    """
    name = method.removeprefix("time.")
    if name not in {"status", "pause", "resume", "configure", "report_failure"}:
        raise ValueError("unknown time method")
    return kernel().call(f"time.{name}", timeout=timeout_s, **(params or {}))


@tool(rung=3, coverage=["move"])
def mb_memory(method: str = "status", params: dict | None = None) -> Any:
    """Persistent server-world/dimension memory: status, get, waypoint, route, protect, remove, record.

    waypoint: {name, pos:[x,y,z]} (omit pos for current feet). route: {name,
    points:[[x,y,z], "waypoint name", ...], radius:2}. Use replace:true to replace
    a waypoint/route. Route anchors are copied when saved. get: {kind,name} returns
    the full record; status lists route summaries. record: {action:start,name,radius}
    records actual travel; action:stop saves it; cancel discards it. Recordings
    invalidated by disconnect/dimension changes cannot be saved.
    protect: {name,min:[x,y,z],max:[x,y,z]} protects an inclusive cuboid from
    incidental navigation/bulk edits (mode:automation, default). Machine use, direct
    keyboard input and deliberate single-block mining/placement stay normal. Use
    mode:all_edits to also lock targeted block edits and raw attack/use; empty-hand
    GUI activation still works. Walking is allowed in either mode. remove: {kind:waypoint|route|region,name}.
    Changing/removing existing protection requires overrideProtection:true and stops
    active controls. This flag on a terrain action authorizes only that operation;
    it is never saved on a route or inherited by the next operation. Protection is
    an accidental-edit guard, not a sandbox for arbitrary mod effects or edited code.
    """
    return _call("memory", method, params)


@tool(rung=0, coverage=["memory"])
def mb_notes(method: str = "search", params: dict | None = None) -> Any:
    """Durable world notes: context, status, capture, search, get, history, resolve.

    capture: {kind:block,pos:[x,y,z]}, {kind:entity,entityId:observedId} or uuid,
    {kind:location,pos?:[x,y,z]}, {kind:region,min:[x,y,z],max:[x,y,z]}. Returns
    worldId and attachment for mb_note_write. Entity UUIDs must come from the server;
    use obs.entities to discover transient IDs. Captures do not save notes.
    search: {query,tags:[all-required-tags],status:open|done|archived|all,kind,
    near:[x,y,z]|player,radius:32,region:{min,max},entity_uuid,dimension,limit:20,cursor,detail:summary|full}.
    Search returns anchors and short excerpts by default; get reads the full note.
    Defaults to current dimension and excludes archived notes; dimension:null searches
    all dimensions (spatial searches require one). Follow nextCursor unchanged with
    the same filters; pages retain a consistent snapshot. Entity proximity uses lastSeen.
    get: {id}; history: {id,before_revision?,limit:20}. resolve: {id} inspects currently
    loaded attachments without overwriting notes; absence never proves destruction.
    Block identity checks cannot detect replacement by an identical block.
    Notes are annotations, not protection rules or verified facts. Keep useful plans,
    machine quirks, adapter source references and construction reservations here.
    """
    from world_notes import read_notes
    return read_notes(kernel(), method, params)


@tool(rung=3, coverage=["memory"])
def mb_note_write(world_id: str, id: str, expected_revision: int,
                  operation_id: str, patch: dict) -> Any:
    """Create/update a durable note with history and a retry-safe receipt.

    Use worldId from mb_notes context/capture. Create with expected_revision:0 and
    patch:{title,text,attachments:[capturedAttachment,...],tags?:[],status?:open,data?:{}}.
    Update with the observed revision and only changed fields. Text/arrays replace
    those fields; read before appending. data holds model-defined JSON, e.g. adapter
    source paths. Use a distinct operation_id for each edit; after a timeout retry
    exactly the same arguments and operation_id. A stale revision fails without edits.
    Archive with patch:{status:archived}; restore with status:open. No destructive delete;
    history preserves prior content, which can be copied into a new guarded revision.
    Region annotations do not prevent normal progression or automatically protect blocks.
    Stored under MODBENCH_NOTES_DIR (default gtnh/.state/notes), across JVM/MCP restarts.
    """
    from world_notes import write_note
    return write_note(kernel(), world_id, id, expected_revision, operation_id, patch)


@tool(rung=3, coverage=["move"])
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
    """
    return kernel().call("baritone.route", name=name, reverse=reverse, startIndex=start_index,
                         allowBreak=allow_break, allowPlace=allow_place,
                         overrideProtection=override_protection,
                         timeoutTicks=timeout_ticks, timeout=timeout_s)


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
    if isinstance(duration_ticks, bool) or not isinstance(duration_ticks, int) or not 1 <= duration_ticks <= 72000:
        raise ValueError("duration_ticks must be 1..72000")
    if isinstance(radius, bool) or not isinstance(radius, int) or not 0 <= radius <= 64:
        raise ValueError("radius must be 0..64")
    if isinstance(offset_distance, bool) or not isinstance(offset_distance, (int, float)) or not 0 <= offset_distance <= 64:
        raise ValueError("offset_distance must be 0..64")
    if isinstance(offset_direction, bool) or not isinstance(offset_direction, (int, float)) or not -360000 <= offset_direction <= 360000:
        raise ValueError("offset_direction is outside supported source range")
    return kernel().call("baritone.follow", timeout=timeout_s, target=target, durationTicks=duration_ticks,
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
    if isinstance(duration_ticks, bool) or not isinstance(duration_ticks, int) or not 1 <= duration_ticks <= 72000:
        raise ValueError("duration_ticks must be 1..72000")
    if process == "farm" and (isinstance(radius, bool) or not isinstance(radius, int) or not 1 <= radius <= 64):
        raise ValueError("farm radius must be 1..64")
    if process == "goal" and not isinstance(goal, dict): raise ValueError("goal process requires a goal object")
    if process == "get_to_block" and not isinstance(block, dict): raise ValueError("get_to_block requires block {id,meta?}")
    params = {"process": process, "durationTicks": duration_ticks,
              "allowBreak": allow_break, "allowPlace": allow_place,
              "exploreForBlocks": explore_for_blocks, "openOnArrival": open_on_arrival,
              "enterPortal": enter_portal, "overrideProtection": override_protection}
    if goal is not None: params["goal"] = goal
    if center is not None: params["center"] = center
    if block is not None: params["block"] = block
    if process == "farm": params["radius"] = radius
    return kernel().call("baritone.process", timeout=timeout_s, **params)


@tool(rung=1, coverage=["machine"])
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
    if operation not in {"get", "set", "reset"}: raise ValueError("operation must be get, set or reset")
    if not isinstance(query, str): raise ValueError("query must be a string")
    if values is not None and not isinstance(values, dict): raise ValueError("values must be an object")
    if operation in {"set", "reset"} and values is None: raise ValueError("set/reset requires values")
    params: dict[str, Any] = {"operation": operation, "query": query, "save": save}
    if values is not None: params["values"] = values
    return kernel().call("baritone.settings", **params)


@tool(rung=1, coverage=["machine"])
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
    return kernel().call("baritone.mine", timeout=timeout_s, **params)


@tool(rung=0, coverage=["machine"])
def mb_build_preview(cells: list[dict] | None = None, selection: dict | None = None,
                     origin: list[int] | None = None, replace_existing: bool = False,
                     override_protection: bool = False, allow_break: bool = False,
                     allow_place: bool = False, mode: str = "blueprint",
                     settings: dict | None = None, size: list[int] | None = None) -> Any:
    """Read-only fresh build diff and shared-inventory material allocation.

    Provide exactly one of cells or selection. Cells use {pos,id,meta?,item?,
    placement?,verify?:{pickedItem:itemSelector},clear?,replace?}. Selection uses inclusive bounds plus shape
    fill|replace|walls|shell|clear, block and optional replace selector. Explicit
    registry IDs are required. Tile NBT is rejected rather than ignored. Preview
    does not load chunks, reserve inventory, prove reachability or mutate the world.
    """
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode}
    params["cells" if cells is not None else "selection"] = cells if cells is not None else selection
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
    normal-interaction adapters. Retain jobId for status or resume.
    """
    if (cells is None) == (selection is None): raise ValueError("provide exactly one of cells or selection")
    params = {"replaceExisting": replace_existing, "overrideProtection": override_protection,
              "allowBreak": allow_break, "allowPlace": allow_place, "mode": mode, "timeoutTicks": timeout_ticks,
              "cells" if cells is not None else "selection": cells if cells is not None else selection}
    if origin is not None: params["origin"] = origin
    if settings is not None: params["settings"] = settings
    if size is not None: params["size"] = size
    return _build_call("baritone.build", params, timeout_s)


@tool(rung=1, coverage=["machine"])
def mb_selection_build(selection: dict, origin: list[int] | None = None,
                       replace_existing: bool = False, override_protection: bool = False,
                       timeout_ticks: int = 12000, timeout_s: float = 600.0,
                       preview: bool = False, allow_break: bool = False,
                       allow_place: bool = False, mode: str = "blueprint",
                       settings: dict | None = None, size: list[int] | None = None) -> Any:
    """Preview or execute fill/replace/walls/shell/clear over an inclusive selection.

    selection is {min:[x,y,z],max:[x,y,z],shape,block?,replace?}; at most 16384
    source cells. This convenience wrapper uses the same strict build contract and
    never treats a selection as permission to edit outside its generated cells.
    """
    if preview:
        return mb_build_preview(selection=selection, origin=origin,
                                replace_existing=replace_existing,
                                override_protection=override_protection, allow_break=allow_break,
                                allow_place=allow_place, mode=mode, settings=settings, size=size)
    return mb_build(selection=selection, origin=origin, replace_existing=replace_existing,
                    override_protection=override_protection, allow_break=allow_break,
                    allow_place=allow_place, mode=mode, settings=settings, size=size, timeout_ticks=timeout_ticks,
                    timeout_s=timeout_s)


@tool(rung=0, coverage=["machine"])
def mb_schematic_import(path: str, origin: list[int] | None = None,
                        registry: dict[str, str] | None = None,
                        include_air: bool = False, palette: dict[str, dict] | None = None,
                        schematic_directory: str | None = None, mode: str | None = None) -> Any:
    """Import canonical JSON, MCEdit, Sponge, or Litematica without building.

    Numeric legacy IDs require SchematicaMapping or registry {numeric:name}.
    Returns canonical build plus separate requirements. Tile entity NBT is preserved
    as unsupported adapter requirements and is never silently attached to placement.
    """
    from gtnh_schematics import import_schematic
    mapping = {int(key): value for key, value in (registry or {}).items()}
    return import_schematic(path, origin=origin, registry=mapping, palette=palette,
                            schematic_directory=schematic_directory, include_air=include_air, mode=mode)


@tool(rung=1, coverage=["machine"])
def mb_schematic_build(path: str, origin: list[int] | None = None,
                       registry: dict[str, str] | None = None,
                       include_air: bool = False, preview: bool = True,
                       timeout_s: float = 600.0, palette: dict[str, dict] | None = None,
                       schematic_directory: str | None = None, mode: str | None = None,
                       allow_break: bool | None = None, allow_place: bool | None = None,
                       replace_existing: bool | None = None, settings: dict | None = None) -> Any:
    """Import a schematic, refuse unsupported state, then preview or build it.

    Defaults to preview. If tile/entity adapter requirements exist, returns them
    with executable:false and performs no bridge mutation. Set preview=false only
    after reviewing the canonical cells and material/conflict preview.
    """
    imported = mb_schematic_import(path, origin, registry, include_air, palette, schematic_directory, mode)
    if imported["requirements"]:
        return {**imported, "executable": False,
                "reason": "unsupported schematic state requires explicit adapters"}
    spec = dict(imported["build"])
    for key, value in (("allowBreak", allow_break), ("allowPlace", allow_place),
                       ("replaceExisting", replace_existing), ("settings", settings)):
        if value is not None: spec[key] = value
    result = _build_call("baritone.build_preview" if preview else "baritone.build", spec,
                         None if preview else timeout_s)
    return {"executable": True, "requirements": [], "request": spec,
            "preview" if preview else "result": result}


@tool(rung=0, coverage=["move", "machine"])
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


@tool(rung=1, coverage=["machine"])
def mb_builder_pause() -> Any:
    """Pause active builder work and return its terminal receipt for this request."""
    return kernel().call("baritone.build_pause")


@tool(rung=0, coverage=["machine"])
def mb_builder_materials() -> Any:
    """Read approximate placeable states in current inventory without changing work."""
    return kernel().call("baritone.build_materials")


@tool(rung=1, coverage=["machine"])
def mb_selection(operation: str, params: dict | None = None) -> Any:
    """Manage world-scoped multi-region selections and exact copy/paste plans.

    Operations: view; pos1/pos2 {pos}; add {pos1,pos2}; remove {index}; clear;
    undo; expand/contract/shift {target:all|newest|oldest,direction,blocks}; copy
    {anchor?}; paste {anchor?,preview:true, build options}. Copy pages native scans
    for every selected region, including observed air, and rejects unloaded or
    incomplete snapshots. Paste defaults to preview and preserves union holes.
    """
    from gtnh_selections import get_selection_store
    if not isinstance(params, dict):
        if params is None: params = {}
        else: raise ValueError("selection params must be an object")
    k = kernel(); context = k.call("memory.context")
    try: world = f'{context["worldId"]}:{context["dimension"]}'
    except (KeyError, TypeError) as exc: raise ValueError("memory.context did not return worldId and dimension") from exc
    store = get_selection_store(); operation = operation.lower()
    if operation == "view": return store.view(world)
    if operation == "pos1": return store.pos1(world, params.get("pos", context.get("pos")))
    if operation == "pos2": return store.pos2(world, params.get("pos", context.get("pos")))
    if operation == "add": return store.add(world, params.get("pos1"), params.get("pos2"))
    if operation == "remove": return store.remove(world, params.get("index", -1))
    if operation == "clear": return store.clear(world)
    if operation == "undo": return store.undo(world)
    if operation in ("expand", "contract", "shift"):
        return store.transform(world, operation, params.get("target", "all"), params.get("direction"), params.get("blocks"))
    if operation == "copy":
        expected = store.selected_positions(world, 1_048_576)
        observations = {}
        for region in store.view(world)["selections"]:
            for bounds in _scan_chunks({"min": region["min"], "max": region["max"]}):
                cursor = 0
                while True:
                    page = k.call("baritone.scan", bounds=bounds, cursor=cursor, limit=256, budget=4096)
                    if not isinstance(page, dict) or not isinstance(page.get("matches"), list):
                        raise ValueError("baritone.scan returned an invalid page")
                    if page.get("unloaded", 0): raise ValueError("selection copy encountered unloaded cells")
                    for row in page["matches"]:
                        key = tuple(row.get("pos", ())) if isinstance(row, dict) else ()
                        if key in observations and observations[key] != row:
                            raise ValueError(f"inconsistent overlapping observation at {list(key)}")
                        observations[key] = row
                    if page.get("done") is True: break
                    next_cursor = page.get("cursor")
                    if isinstance(next_cursor, bool) or not isinstance(next_cursor, int) or next_cursor <= cursor:
                        raise ValueError("baritone.scan cursor did not advance")
                    cursor = next_cursor
        if set(observations) != expected: raise ValueError("selection scan did not return every selected cell including air")
        return store.copy(world, params.get("anchor", context.get("pos")), observations.values(),
                          params.get("block_states_only", False))
    if operation == "paste":
        reserved = {"anchor", "preview", "timeout_s"}
        options = {key: value for key, value in params.items() if key not in reserved}
        plan = store.paste(world, params.get("anchor", context.get("pos")), **options)
        preview = params.get("preview", True)
        if not isinstance(preview, bool): raise ValueError("paste preview must be boolean")
        timeout_s = params.get("timeout_s", 600.0)
        if isinstance(timeout_s, bool) or not isinstance(timeout_s, (int, float)) or not 1 <= timeout_s <= 3600:
            raise ValueError("paste timeout_s must be in 1..3600")
        result = _build_call("baritone.build_preview" if preview else "baritone.build", plan,
                             None if preview else timeout_s)
        return {"request": plan, "preview" if preview else "result": result}
    raise ValueError("operation must be view, pos1, pos2, add, remove, clear, undo, expand, contract, shift, copy, or paste")


@tool(rung=0, coverage=["move", "machine"])
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
    return kernel().call("baritone.resume", timeout=timeout_s, jobId=job_id,
                         **(options or {}))


@tool(rung=0, coverage=["progression"])
def mb_quest_status() -> Any:
    """Report native Better Questing availability and catalogue counts."""
    return kernel().call("quest.status")


@tool(rung=0, coverage=["progression"])
def mb_quest_sync() -> Any:
    """Queue Better Questing's native full quest/progress and chapter synchronization query.

    This does not open a GUI, load an item, detect tasks, claim rewards, or edit quest
    state. The response is pending until the running server processes the packets and
    the client database updates.
    """
    return kernel().call("quest.sync")


@tool(rung=0, coverage=["progression"])
def mb_quest_search(query: str = "", offset: int = 0, limit: int = 20) -> Any:
    """Search localized quest UUIDs, titles and descriptions with pagination."""
    return kernel().call("quest.search", query=query, offset=offset, limit=limit)


@tool(rung=0, coverage=["progression"])
def mb_quest_lines(query: str = "", offset: int = 0, limit: int = 10) -> Any:
    """Read native quest-line order, layout and per-player state totals."""
    return kernel().call("quest.lines", query=query, offset=offset, limit=limit)


@tool(rung=0, coverage=["progression"])
def mb_quest_observe(quest_id: str) -> Any:
    """Observe one quest UUID: prerequisites, task progress/config and rewards.

    Reward choices expose native indices. Task/reward NBT is descriptive SNBT and
    never authorizes direct state mutation.
    """
    return kernel().call("quest.observe", questId=quest_id)


@tool(rung=1, coverage=["progression"])
def mb_quest_detect(quest_id: str, task_ids: list[int]) -> Any:
    """Send Better Questing's normal quest-wide detection request.

    The receipt only means the packet was queued. Re-observe after synchronization;
    supplied task IDs validate intent but do not force per-task completion.
    """
    return kernel().call("quest.detect", questId=quest_id, taskIds=task_ids)


@tool(rung=1, coverage=["progression"])
def mb_quest_select_choice(quest_id: str, reward_id: int, choice_index: int) -> Any:
    """Select one observed native reward option through the normal BQ packet.

    Re-observe the selected index before claiming. Packet queueing is not a server
    acknowledgement.
    """
    return kernel().call("quest.select_choice", questId=quest_id, rewardId=reward_id,
                         choiceIndex=choice_index)


@tool(rung=1, coverage=["progression"])
def mb_quest_claim(quest_id: str, reward_ids: list[int],
                   choices: dict[str, int] | None = None) -> Any:
    """Request a normal quest-wide claim with explicit reward IDs and choices.

    choices maps rewardId strings to observed choice indices and must cover every
    choice reward. The result is pending synchronization: re-observe quest state and
    compare inventory deltas before reporting receipt. Never blindly retry a claim.
    """
    return kernel().call("quest.claim", questId=quest_id, rewardIds=reward_ids,
                         choices=choices or {})


@tool(rung=0, coverage=["recipes"])
def mb_nei_status() -> Any:
    """Check whether GTNH NEI's full item catalogue and recipe handlers are ready."""
    return kernel().call("nei.status")


@tool(rung=0, coverage=["recipes"])
def mb_search(query: str = "", mod: str = "", ore: str = "", id: str = "", meta: int = -1,
              offset: int = 0, limit: int = 30, timeout_s: float = 60.0) -> Any:
    """Search the full native NEI catalogue, with pagination and exact variant identities.

    query uses the installed NEI search syntax, including its mod/ore/tooltip search
    providers. Structured mod, ore and id filters are exact; meta=-1 includes all
    variants. Results include display names, id, metadata, NBT and oreNames. Preserve
    id/meta/nbt when requesting recipes or selecting items: GT uses metadata heavily.
    nextOffset continues a result set. Repeated pages use a cached native search.
    """
    return kernel().call("nei.search", query=query, mod=mod, ore=ore, id=id, meta=meta,
                         offset=offset, limit=limit, timeout=timeout_s)


@tool(rung=0, coverage=["recipes"])
def mb_item(id: str, meta: int, nbt: str | None = None) -> Any:
    """Inspect an exact item variant, including tooltips, ore/fluid data and ItemBlock placement metadata.

    placement.initialBlockMeta is the native initial world-block metadata, not
    the item variant meta; face, pose and callbacks can still alter final state.
    """
    return kernel().call("nei.item", id=id, meta=meta, nbt=nbt)


@tool(rung=0, coverage=["recipes"])
def mb_recipes(id: str = "", meta: int | None = None, nbt: str | None = None, mode: str = "recipes",
               handler: str = "", offset: int = 0, limit: int = 0,
               alternatives_offset: int = 0, alternatives_limit: int = 32,
               timeout_s: float = 60.0, fluid: str = "", amount: int = 1000,
               detail: str = "summary", index: int = -1) -> Any:
    """Browse every way to make an item, or mode='uses' for what consumes it.

    Default limit=0 returns a category overview with counts and known GT base EU/t
    ranges, without choosing a recipe. NEI order is NOT a progression recommendation.
    Pick an exact handlerKey as handler, then limit=5 (or up to 20) for compact
    comparable options. Follow nextOffset to see all variants. Use detail='full',
    handler=that exact key, index=the option's native index, limit=1 for complete
    ingredients/NBT/alternatives. Summary examples omit NBT and are not actionable
    item identities. Compare machines, power, ingredients and research against
    observations; no route is selected or declared craftable automatically.

    Pass exact id/meta/nbt for an item, or fluid="water" for a canonical fluid ID
    returned by mb_fluids. Uses all NEI handlers, including modded machines and fluid-container
    recipes. handler filters by handler id or machine/category name. Returned handler
    summaries show available categories and counts; nextOffset pages recipes.
    Ingredient positions contain alternative item identities and counts; use
    alternatives_offset/alternatives_limit if nextAlternativesOffset is present.
    GregTech recipes include exact item/fluid quantities, output chances, duration,
    base EU/t, special items, metadata requirements and fakeRecipe/nbtSensitive flags.
    An input count of zero denotes a retained catalyst/configuration item. Output
    chances are resolved out of 10000, including default guaranteed outputs.
    Base power/time is before overclocking. Fake recipes may describe information
    rather than an executable process; inspect their native page.
    Custom non-GT handlers may expose additional requirements only through their GUI.
    This observes recipes; it does not craft, spawn items or alter the current GUI.
    """
    return kernel().call("nei.recipes", id=id, meta=meta, nbt=nbt, mode=mode, handler=handler,
                         offset=offset, limit=limit, alternativesOffset=alternatives_offset,
                         alternativesLimit=alternatives_limit, timeout=timeout_s, fluid=fluid, amount=amount, detail=detail, index=index)


@tool(rung=0, coverage=["recipes"])
def mb_fluids(query: str = "", offset: int = 0, limit: int = 30) -> Any:
    """Search loaded fluid IDs/names and physical properties; pass a returned ID to mb_recipes(fluid=...)."""
    return kernel().call("nei.fluids", query=query, offset=offset, limit=limit)


@tool(rung=0, coverage=["recipes"])
def mb_recipe_handlers(query: str = "", offset: int = 0, limit: int = 30) -> Any:
    """Discover all native NEI categories, including custom diagrams, magic and bee handlers, and their machine catalysts."""
    return kernel().call("nei.handlers", query=query, offset=offset, limit=limit)


@tool(rung=3, coverage=["recipes"])
def mb_recipe_view(handler_key: str, index: int, id: str = "", meta: int | None = None,
                   nbt: str | None = None, mode: str = "recipes", fluid: str = "", amount: int = 1000) -> Any:
    """Open and capture an exact native NEI recipe page from mb_recipes.

    Reuse that query's id/meta/nbt or fluid, mode, and the result's handlerKey/index.
    This preserves custom machine diagrams, Thaumcraft aspects, bee/chance displays,
    and text drawn by handlers that has no structured API. It changes the GUI and
    cancels active movement; it does not craft or spawn items. Use mb_recipe_inspect(x,y) for native hover text and scrolling; coordinates use the
    returned GUI dimensions. Close with mb_gui('close') before movement.
    """
    k=kernel()
    view=k.call("nei.view", handlerKey=handler_key, index=index, id=id, meta=meta, nbt=nbt,
                mode=mode, fluid=fluid, amount=amount, timeout=60)
    time.sleep(.15)  # Allow the render loop to draw the newly opened page, including while paused.
    shot=k.call("sys.screenshot")
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(view)),
                                  ImageContent(type="image", data=shot["png"], mimeType="image/png")])


@tool(rung=3, coverage=["recipes"])
def mb_recipe_inspect(x: int, y: int, scroll: int = 0) -> Any:
    """Inspect the open native NEI page at logical GUI coordinates and capture it.

    Returns the hovered item, native item/handler tooltip text, hotkeys and a
    screenshot. scroll=-1 scrolls down, +1 up through native recipe/custom diagram
    handling; 0 only inspects. Use this for aspects, mutation conditions, chances,
    oversized diagrams and requirements missing from structured recipe data.
    Some handlers draw graphics without tooltip text: inspect the image too.
    Does not craft or spawn items. Requires mb_recipe_view first.
    """
    k=kernel()
    detail=k.call("nei.inspect", x=x, y=y, scroll=scroll)
    time.sleep(.15)
    shot=k.call("sys.screenshot")
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(detail)),
                                  ImageContent(type="image", data=shot["png"], mimeType="image/png")])
