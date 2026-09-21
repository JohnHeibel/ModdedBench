# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Bridge meta, observation, input, time and memory tools: thin wrappers over one RPC each."""

from __future__ import annotations

import base64
import json
import os
from typing import Any

from mcp.server.fastmcp import Image
from mcp.types import CallToolResult, ImageContent, TextContent

from mbtool import kernel, state, tool
from mbtools_gtnh import notes

CONTROL_VERBS = {"stop", "cancel", "pause", "resume", "ack", "fire", "input_clear", "step"}
READ_SUFFIXES = (".status", ".list", ".get", ".context", ".hit_test", ".methods", ".capabilities")


def method_name(namespace: str, method: str) -> str:
    name = method if "." in method else f"{namespace}.{method}"
    if not name.startswith(namespace + "."):
        raise ValueError(f"method must belong to {namespace}")
    return name


def lane_for(method: str, namespace: str = "") -> str:
    """control for stop/pause/cancel verbs; read for advertised reads (mb_methods caches sys.methods) or obs.*; else act."""
    name = method if "." in method or not namespace else f"{namespace}.{method}"
    if name.rsplit(".", 1)[-1] in CONTROL_VERBS:
        return "control"
    effect = state.get("methods", {}).get(name, {}).get("effect")
    if effect == "read" or name.startswith("obs.") or name.endswith(READ_SUFFIXES):
        return "read"
    return "act"


def lane_by_method(namespace: str = ""):
    return lambda kw: lane_for(kw.get("method", "") or "", namespace)


def methods_map(raw: Any) -> dict:
    """sys.methods as {name: {desc, effect, thread, watchable}} whether Java sent a map or a list."""
    raw = raw.get("methods", raw) if isinstance(raw, dict) else raw
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, list):
        return {x["name"]: x for x in raw if isinstance(x, dict) and isinstance(x.get("name"), str)}
    raise ValueError("sys.methods must be a method map or array")


@tool(lane="read", coverage=["meta"])
def mb_methods() -> Any:
    """List bridge methods advertised by the GTNH profile; also caches their effects for lane routing of mb_call."""
    raw = kernel().call("sys.methods")
    state["methods"] = methods_map(raw)
    return raw


@tool(lane="read", coverage=["meta"])
def mb_status() -> Any:
    """Bridge status, the clock (paused, why, operator hold), your goal stack (mb_goal) with its stall signal, and world notes near you.

    Call it at the start of every session and after every compaction: it is the heartbeat.
    """
    k, brief = kernel(), os.environ.get("MB_BRIEF", "")
    out = k.call("sys.capabilities")
    if isinstance(out.get("methods"), list): out["methods"] = f"{len(out['methods'])} raw methods; list them with mb_methods"  # the heartbeat must stay small
    out = dict(out, brief=f"Your standing brief is {brief if os.path.isfile(brief) else 'PROMPT.md at the repository root'}. If you cannot recall its mission and rules, re-read it now.")
    try:
        clock = k.call("time.status", timeout=5).get("state", {})
        out["clock"] = {key: clock.get(key) for key in ("mode", "paused", "reason", "held", "simulationTicks", "threats")}
        out["goal"] = notes.goal(k)
        free = k.call("obs.inventory", detail="counts", timeout=5).get("emptySlots")
        out["inventory"] = f"{free} of 36 slots free" + ("" if free is None or free > 6 else ": store or discard (mb_move_items) before you gather, craft in bulk or claim rewards")
    except Exception as e:  # not in a world yet, or the clock is unreachable: status must still answer
        out["goal"] = {"unavailable": str(e)}
    return notes.attach(out, notes.surface(k, reason="session", radius=32))


@tool(lane=lane_by_method(), effect="privileged", coverage=["meta"])
def mb_call(method: str, params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Call any advertised bridge method with JSON parameters.

    Long nav.goto routes need both timeout_s and params.timeoutTicks raised;
    for example timeout_s=900 and timeoutTicks=16000 for a sustained journey.
    """
    return notes.tracked(method, timeout_s, **(params or {}))


@tool(lane="read", coverage=["meta"])
def mb_obs(method: str, params: dict | None = None) -> Any:
    """Call an obs.* capability by short or full method name.

    First-class machine reads include tile {pos|x,y,z,detail:'full',hwyla?},
    nbt {handle,path,offset?,limit?}, waila {pos}, and mixed batch
    {queries:{alias:{method,params}}}. Tile data and NBT provenance come from the
    authoritative server; server aliases in a batch share serverTick. Missing
    tanks do not prove no fluid, and reported side views may overlap.
    light {radius:8,height:4,limit:32} lists where mobs can spawn near you (block light 7 or
    less on a solid top), nearest first: what to torch before you work, sleep or build there.
    Observing a block or entity that carries a world note returns it under "notes".
    """
    return notes.tracked(method_name("obs", method), None, **(params or {}))


@tool(lane=lane_by_method("act"), coverage=["move"])
def mb_act(method: str, params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Native act.* input/look/stop, use_block, use_entity, attack_entity, use_item,
    eat, select_hotbar, combat and status. Target blocks with x/y/z; face 0..5 names the side
    to click (placing against it), omit it to click whichever side you can see; optional
    block-local hit [x,y,z] (default native face center); use_item
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
    return kernel().call(method_name("act", method), timeout=timeout_s, **(params or {}))


KEYS = {"list": "obs.keys", "press": "act.press_key"}


@tool(lane=lambda kw: lane_for(KEYS.get(kw.get("method", ""), kw.get("method", "") or "")), coverage=["meta"])
def mb_keys(method: str, params: dict | None = None) -> Any:
    """Key bindings: list (obs.keys) or press {name,ticks:1..200,overrideProtection?} (act.press_key)."""
    name = KEYS.get(method, method)
    if name not in KEYS.values():
        raise ValueError("method must be list or press")
    return kernel().call(name, **(params or {}))


@tool(lane="read", coverage=["meta"])
def mb_screenshot() -> Image:
    """Capture sys.screenshot, which returns PNG base64 plus width and height."""
    shot = kernel().call("sys.screenshot")
    try:
        return Image(data=base64.b64decode(shot["png"], validate=True), format="png")
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("sys.screenshot must return a base64 PNG in 'png'") from exc


@tool(lane="read", coverage=["meta"])
def mb_map(center: list[int] | None = None, radius: int = 128, layer: str = "day") -> CallToolResult:
    """JourneyMap's overhead map as a picture: what this client has seen, one pixel per block before scaling.

    center [x,z] defaults to you; radius 16..2048 blocks. The 768 px image has labelled
    x/z grid lines (north up, x right, z down), you as the yellow dot with a facing
    line, red squares for JourneyMap death points (your dropped items) and cyan for
    mb_memory waypoints. Orange dots (grey when depleted) are the ore veins you have
    prospected, from VisualProspecting's own log; veins lists each with its ores, centre
    [x,z] and y range. It holds only what this player has found. layer: day, night, topo, cave (the 16-block slice you stand
    in) or a slice number y//16. Black is unexplored: mappedFraction says how much of
    the window is known, and only chunks that were loaded near you are ever mapped.
    JourneyMap starts a world's map only once time has run after you joined, so a
    view taken while paused straight after a join or deploy is black: resume first.
    Use it to survey terrain, water, forests, structures and unexplored directions and
    to plan routes; confirm block-level facts with mb_obs before acting on them.
    """
    view = kernel().call("map.view", **({"center": center} if center else {}), radius=radius, layer=layer)
    png = view.pop("png")
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(view)), ImageContent(type="image", data=png, mimeType="image/png")])


@tool(lane="control", coverage=["move"])
def mb_stop() -> Any:
    """Stop the active GTNH bridge action via act.stop."""
    return kernel().call("act.stop")


@tool(lane=lambda kw: "read" if (kw.get("method") or "status").removeprefix("time.") == "status" else "control", coverage=["time"])
def mb_time(method: str = "status", params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Control GTNH world time: status, pause, resume, configure, report_failure.

    Real time is the default: finishing an action does not pause machines or mobs.
    Pause explicitly for deliberation; resume to keep production running while thinking.
    Pause waits for coordinated client/server quiescence. Inspect status for pausing
    or pause_error; a timed-out pause leaves simulation gated. Resume explicitly
    before executing game actions. Screenshots, observations and cancellation remain
    available during pause. Exact stepping is not supported yet.
    configure params: healthDrop, burning, actionFailed, pauseOnDisconnect are booleans;
    healthBelow (health points), airBelow (air ticks, 300 is full) and foodBelow (food
    points) are numeric thresholds, and -1 disables one. threatWithin N (blocks, at most 32)
    pauses with reason threat the moment a mob takes you as its target within N blocks, or
    2N with a clear line of sight, or a creeper starts to swell: once per mob, before it has
    hurt you. status.threats lists every mob after you now: entityId, type, distance, pos,
    lineOfSight, ranged, swelling, health. A usual set: {healthDrop:true,healthBelow:8,
    airBelow:60,foodBelow:6,burning:true,threatWithin:12,pauseOnDisconnect:true}. Conditions pause globally
    and report a reason. Baritone terminal failures signal actionFailed automatically;
    agent-written tools can call report_failure to signal their own failures.
    pauseOnDisconnect defaults true; set false for a planned
    client restart with continued server production, then restore it after reconnect.
    A controlling session must stay connected; observation-only sessions do not own time.
    The reply keeps the three latest pause events; params {events:true} on status returns all 32.
    """
    name = method.removeprefix("time.")
    if name not in {"status", "pause", "resume", "configure", "report_failure"}:
        raise ValueError("unknown time method")
    params = dict(params or {}); every = params.pop("events", False)
    out = kernel().call(f"time.{name}", timeout=timeout_s, **params)
    state = out.get("state") if isinstance(out, dict) else None
    if not every and isinstance(state, dict) and len(state.get("events") or []) > 3: state["events"] = state["events"][-3:]
    return out


@tool(lane=lane_by_method("memory"), coverage=["move"])
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
    return notes.tracked(method_name("memory", method), None, **(params or {}))
