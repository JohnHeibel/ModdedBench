# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Bridge meta, observation, input, time and memory tools."""

from __future__ import annotations

import base64
import json
import os
import time
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import Image
from mcp.types import CallToolResult, ImageContent, TextContent

import mbtool
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


def _line(desc: str, width: int = 100) -> str:
    """A description's first sentence, clipped: enough to choose a method by."""
    first = desc.split(". ")[0]
    return first if len(first) <= width else first[:width - 3] + "..."


@tool(lane="read", coverage=["meta"])
def mb_methods(name: str = "") -> Any:
    """List the bridge's raw methods with one line each, or with name the matching ones whole; also caches their effects for lane routing of mb_call.

    name is any part of a method name ("gui.", "nav.mine", "status"): each match comes back
    with its full description (the parameter schema), effect, thread and watchable.
    """
    methods = state["methods"] = methods_map(kernel().call("sys.methods"))
    if name: return {"methods": {n: m for n, m in methods.items() if name in n}}
    return {"methods": {n: _line(m.get("desc") or "") for n, m in methods.items()}}


@tool(lane="read", coverage=["meta"])
def mb_status() -> Any:
    """Bridge status, the clock (paused, why, a hold and whose), your goal stack (mb_goal) with its stall signal, the folder of your world notes (notesFolder) and the notes near you, how long the run has been going, and your last two hours' cost (mb_cost).

    Call it at the start of every session and after every compaction: it is the heartbeat.
    Also, when there are any: body (your running background task), finished (tasks whose result
    you have not been handed yet) and pausedMining (mining jobs paused with targets still known:
    job, vein or at, left, gained, minutesAgo; mb_work_resume continues one).
    """
    k, brief = kernel(), os.environ.get("MB_BRIEF", "")
    out = k.call("sys.capabilities")
    if isinstance(out.get("methods"), list): out["methods"] = f"{len(out['methods'])} raw methods; list them with mb_methods"  # the heartbeat must stay small
    out = dict(out, brief=f"Your standing brief is {brief if os.path.isfile(brief) else 'PROMPT.md at the repository root'}. If you cannot recall its mission and rules, re-read it now.")
    try:
        clock = k.call("time.status", timeout=5).get("state", {})
        out["clock"] = {key: clock.get(key) for key in ("mode", "paused", "reason", "held", "heldBy", "simulationTicks", "threats") if key in clock}
        out["goal"] = notes.goal(k)
        out.update(notes.where(k))
        free = k.call("obs.inventory", detail="counts", timeout=5).get("emptySlots")
        out["inventory"] = f"{free} of 36 slots free" + ("" if free is None or free > 6 else ": store or discard (mb_move_items) before you gather, craft in bulk or claim rewards")
    except Exception as e:  # not in a world yet, or the clock is unreachable: status must still answer
        out["goal"] = {"unavailable": str(e)}
    try:
        run = json.loads((Path(__file__).resolve().parents[2] / ".state" / "run.json").read_text(encoding="utf-8"))
        minutes = int((time.time() - run["startedAt"]) // 60)
        out["run"] = f"{minutes // 60}h{minutes % 60:02d}m since the run started"
    except (OSError, ValueError, KeyError, TypeError):
        pass  # not started by the run loop
    try:
        out["cost"] = mb_cost(hours=2, top=6)
    except Exception as e:
        out["cost"] = {"unavailable": str(e)}
    from mbtools_gtnh import tasks, work
    extra = {"body": tasks.body(tasks.live()), "finished": tasks.deliver(), "pausedMining": work.paused_mining()}
    out.update({key: value for key, value in extra.items() if value})
    return notes.attach(out, notes.surface(k, reason="session", radius=32))


@tool(lane="read", coverage=["meta"])
def mb_cost(hours: float = 2.0, top: int = 15) -> Any:
    """What your own tool calls cost over the last `hours`: calls, failures and minutes per tool, and the time between calls.

    Dispatchers are split by method (mb_notes(find)); time between calls is your thinking and compaction. The
    busiest tools come first. A tool you call over and over is work you are doing by hand; see section 4 of
    your brief on costs that never fail. resultChars is the text your results put into your context, and
    topChars the tools that put most of it there, each with its largest single result. With background tasks in the window: bodyBusyShare (of the window,
    the body working in a task), bodyWhileThinkingShare (the body working while you were not in a tool call)
    and unseenFailureSeconds (for each failed task, how long until your next call saw it; a crashed one from its
    last sign of life).
    """
    now = time.time()
    since, calls = now - hours * 3600, []
    try:
        with open(mbtool.CALL_LOG or os.devnull, "rb") as f:
            f.seek(max(0, f.seek(0, 2) - (8 << 20)))  # the tail is enough for any window a run needs
            lines = f.read().decode("utf-8", "replace").splitlines()
    except OSError:
        return {"hours": hours, "calls": 0}
    tasks = []  # a background task's own calls are not yours; its whole run is one "task" line
    for line in lines:
        try:
            c = json.loads(line)
        except ValueError:
            continue
        if isinstance(c, dict) and c.get("t", 0) + c.get("s", 0) >= since and c.get("caller"):
            tasks += [c] if c.get("tool") == "task" else []
        elif isinstance(c, dict) and c.get("t", 0) >= since:
            calls.append(c)
    by: dict[str, list] = {}
    for c in calls:
        row = by.setdefault(f"{c['tool']}({c['method']})" if c.get("method") else c["tool"], [0, 0, 0.0, 0, 0])
        row[0] += 1; row[1] += c.get("error") is not None; row[2] += c.get("s", 0)
        row[3] += c.get("chars", 0); row[4] = max(row[4], c.get("chars", 0))
    spans = _union((c["t"], c["t"] + c.get("s", 0)) for c in calls)  # parallel calls count once
    busy = sum(e - s for s, e in spans)
    window = now - (calls[0]["t"] if calls else now)
    rows = sorted(by.items(), key=lambda kv: (-kv[1][0], -kv[1][2]))[:max(0, top)]
    out = {"hours": hours, "calls": len(calls), "failed": sum(r[1] for r in by.values()),
           "toolMinutes": round(busy / 60, 1), "betweenCallsMinutes": round(max(0.0, window - busy) / 60, 1),
           "top": {k: f"{n} calls, {f} failed, {s / 60:.1f} min" for k, (n, f, s, _, _) in rows}}
    if any(r[3] for r in by.values()):
        heavy = sorted(by.items(), key=lambda kv: -kv[1][3])[:5]
        out.update(resultChars=sum(r[3] for r in by.values()),
                   topChars={k: f"{r[3]} chars in {r[0]} calls, largest {r[4]}" for k, r in heavy if r[3]})
    from mbtools_gtnh.tasks import every
    known = every()  # a running task, and crashed ones (they never wrote their line): from their last sign of life
    crashed = [t for t in known if t["state"] == "crashed" and t.get("lastSeen", 0) >= since]
    ended = [(t["t"], t["t"] + t.get("s", 0)) for t in tasks] + [(t["started"], t["lastSeen"]) for t in crashed]
    worked = _union([(max(s, now - window), e) for s, e in ended] + [(max(t["started"], now - window), now) for t in known if t["state"] == "running"])
    if window > 0 and worked:
        body = sum(e - s for s, e in worked)
        alongside = sum(max(0.0, min(e1, e2) - max(s1, s2)) for s1, e1 in worked for s2, e2 in spans)
        out.update(bodyBusyShare=round(body / window, 2), bodyWhileThinkingShare=round((body - alongside) / window, 2))
        failed = [t["t"] + t.get("s", 0) for t in tasks if t.get("error") in ("failed", "crashed", "interrupted")] + [t["lastSeen"] for t in crashed]
        unseen = [round(min((c["t"] for c in calls if c["t"] >= end), default=now) - end) for end in failed]
        if unseen: out["unseenFailureSeconds"] = unseen
    return out


def _union(pairs) -> list[list[float]]:
    """Intervals merged where they overlap, in order."""
    spans: list[list[float]] = []
    for s, e in sorted(pairs):
        if spans and s <= spans[-1][1]: spans[-1][1] = max(spans[-1][1], e)
        elif e > s: spans.append([s, e])
    return spans


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
    Observing a block or entity that carries a world note names it under "notes".
    player includes your potion effects and the blocks at your feet, head and under you.
    """
    return notes.tracked(method_name("obs", method), None, **(params or {}))


@tool(lane=lane_by_method("act"), coverage=["move"])
def mb_act(method: str, params: dict | None = None, timeout_s: float = 60.0) -> Any:
    """Native act.* input/look/stop, use_block, use_entity, attack_entity, use_item,
    eat, select_hotbar, combat and status. Target blocks with x/y/z; face 0..5 names the side
    to click (placing against it), omit it to click whichever side you can see; optional
    block-local hit [x,y,z] (default visible native surface); use_item
    fluid=true includes collidable fluids in the targeting ray.
    Raw input attack locks to the initial block and stops when it changes;
    allowRetarget=true explicitly enables continuous block attacking.
    input attackTarget=[x,y,z] is refused, with no attack sent, unless the block
    the attack locks onto (first under the crosshair) is that one; checked in the
    same tick as the attack. It requires attack and forbids allowRetarget.
    Sneak+attack/use input first holds sneak alone for poseTicks (default 15,
    0 disables, maximum 200), allowing the pose packet to precede the click.
    posePrelude reports that bounded native hold; an incomplete hold sends no
    click. This is not acknowledgment of the interaction: observe its effect.
    Eat defaults to a 400-tick budget; nativeUseTicks exposes pack food penalties.
    Consumption acknowledgment releases use before another block interaction.
    Guard with expectedHeld (full observed stack), expected {id,meta} for blocks,
    expectedHandle for transient entityId. Combat: ticks, range<=3, intervalTicks,
    hostile=true, types=[exact entity type], entityId, stopWhenClear. Combat stays
    in place; compose navigation separately. Native acceptance isn't proof that a
    machine changed; inspect before/after receipts and your own postconditions.
    eat is refused while the clock lists a threat (you cannot fight or run with food in
    your hand; the error's procedureReceipts list the threats); params {despiteThreat:true} eats anyway. use_block answers with `labels` when
    the spot lies in or beside a region note of yours.
    """
    params = dict(params or {})
    attack_target = params.get("attackTarget")
    if attack_target is not None and (method_name("act", method) != "act.input" or "attack" not in params.get("keys", [])
                                      or params.get("allowRetarget") or not isinstance(attack_target, list)
                                      or len(attack_target) != 3 or any(type(v) is not int for v in attack_target)):
        raise ValueError("attackTarget requires three integer coordinates and input attack without allowRetarget")
    if method_name("act", method) == "act.eat": no_threat("eat", despite=params.pop("despiteThreat", False))
    k, prelude = kernel(), None
    if method_name("act", method) == "act.input":
        pose_ticks = params.pop("poseTicks", 15)
        if type(pose_ticks) is not int or not 0 <= pose_ticks <= 200:
            raise ValueError("poseTicks must be an integer from 0 to 200")
        keys = params.get("keys", [])
        if isinstance(keys, list) and "sneak" in keys and any(key in keys for key in ("attack", "use")) and pose_ticks:
            prelude = k.call("act.input", timeout=timeout_s, keys=["sneak"], ticks=pose_ticks)
            if prelude.get("completed") is not True:
                return {"posePrelude": prelude, "actionSent": False}
    result = k.call(method_name("act", method), timeout=timeout_s, **params)
    if prelude is not None: result = {**result, "posePrelude": prelude}
    if method_name("act", method) == "act.use_block" and isinstance(result, dict) and all(isinstance(params.get(a), int) for a in "xyz"):
        from mbtools_gtnh import plan  # the block clicked and the cells around it, where a placed block lands
        spot = [params[a] for a in "xyz"]; labels = plan.labels_at(kernel(), [v - 1 for v in spot], [v + 1 for v in spot])
        if labels: result = {**result, "labels": labels}
    return result


class Refused(ValueError):
    """A harness default said no. receipts carries the fact (what, why, the override), so the error is structured, not prose."""
    def __init__(self, msg: str, **fact):
        super().__init__(msg); self.receipts = [{"refused": fact}]


def no_threat(what: str, k=None, despite: bool = False) -> None:
    """Refuse something that ties the player's hands while the threat guard lists a mob that is after them; despite skips it."""
    if despite: return
    try: threats = (k or kernel()).call("time.status", timeout=5).get("state", {}).get("threats") or []
    except Exception: return  # no clock, no opinion
    if threats:
        near = ", ".join(f"{t.get('type')} {t.get('distance')} blocks" for t in threats[:4])
        raise Refused(f"refusing to {what}: {len(threats)} mob(s) after you ({near}). Deal with them first (mb_fight, leave, or a block between you), "
                      "or pass despiteThreat/despite_threat to do it anyway.", action=what, reason="no_threat", threats=threats, override="despiteThreat")


KEYS = {"list": "obs.keys", "press": "act.press_key"}


@tool(lane=lambda kw: lane_for(KEYS.get(kw.get("method", ""), kw.get("method", "") or "")), coverage=["meta"])
def mb_keys(method: str, params: dict | None = None) -> Any:
    """Key bindings: list (obs.keys) or press {name,ticks:1..200} (act.press_key)."""
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
    """Control GTNH world time: status, pause, resume, step, configure, report_failure.

    Real time is the default: finishing an action does not pause machines or mobs.
    Pause explicitly for deliberation; resume to keep production running while thinking.
    Pause waits for coordinated client/server quiescence. Inspect status for pausing
    or pause_error; a timed-out pause leaves simulation gated. Resume explicitly
    before executing game actions. Screenshots, observations and cancellation remain
    available during pause.
    step {ticks:N} (1..72000) runs exactly N server ticks and pauses again with reason
    step; the reply comes once that pause has settled and its lastStep says how many ticks
    ran and what ended the step (a guard can end it early). A job still running at the step's
    end is suspended and reports it; step or resume to go on. A single action (a click, a
    selection, a held input) is never cut short by a step: the world steps on until it answers,
    and resumedWorld.extendedTicks says by how much.
    Any acting tool takes resume=N to step N ticks with its action starting on the first,
    and resume=True to resume with it: one call, no tick lost between resume and action.
    The resume belongs to the call's first action only: a pause later in the same call (a
    guard) stands, and a call whose resume lifted no pause says so (resumeUnused).
    configure params: healthDrop, burning, actionFailed, pauseOnDisconnect are booleans;
    healthBelow (health points), airBelow (air ticks, 300 is full) and foodBelow (food
    points) are numeric thresholds, and -1 disables one. threatWithin N (blocks, at most 32)
    pauses with reason threat the moment a mob takes you as its target within N blocks, or
    2N with a clear line of sight, or a creeper starts to swell: once per mob, before it has
    hurt you. status.threats lists every mob after you now: entityId, type, distance, pos,
    lineOfSight, ranged, swelling, health. A usual set: {healthDrop:true,healthBelow:8,
    foodBelow:6,burning:true,threatWithin:12,pauseOnDisconnect:true}; air falls 1 a tick under water
    from 300; walks and mine jobs plan their own breath and surface by themselves, so an air threshold
    is for work you do by hand under water. Conditions pause globally and report a reason. A threshold
    pauses once as the value crosses it, and again only after it has recovered above it, so you can
    act out of the danger without disabling the guard: for air, mb_process goal {type:"breathable"}. Baritone terminal failures signal actionFailed automatically;
    agent-written tools can call report_failure to signal their own failures.
    pauseOnDisconnect defaults true; set false for a planned
    client restart with continued server production, then restore it after reconnect.
    A controlling session must stay connected; observation-only sessions do not own time.
    The reply keeps the three latest pause events; params {events:true} on status returns all 32.
    """
    name = method.removeprefix("time.")
    if name not in {"status", "pause", "resume", "step", "configure", "report_failure"}:
        raise ValueError("unknown time method")
    params = dict(params or {}); every = params.pop("events", False)
    if name == "step":  # 20 ticks a second when the server keeps up; the reply waits for the pause at the end
        timeout_s = max(timeout_s, int(params.get("ticks") or 0) / 10 + 30)
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
    protect: {name,min:[x,y,z],max:[x,y,z]} marks an inclusive cuboid that your jobs
    will not dig through or build in on their way. It binds what a job's path may do
    (the blocks a walk, a mine or a build breaks to get through or places to climb and
    bridge) and which targets a mine takes, and nothing else: walking through it, every
    click, door and machine, each single block you break or place, and the cells an
    mb_build names all work there as anywhere. A job that should edit inside takes
    override_protection=True; that lifts it for that one call and is never saved or
    inherited. A walk the region refused fails naming it; a mine lists the targets it
    left as protected_region:<names>. replace:true changes a region; remove:
    {kind:waypoint|route|region,name}. Changing or removing a region stops the running
    job. It guards against your own jobs' accidents, not explosions, fluids or mobs.
    """
    return notes.tracked(method_name("memory", method), None, **(params or {}))
