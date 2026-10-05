# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Durable world notes (plain files, one folder per server world) and their surfacing as a side effect of play.

A note is ``<notes dir>/<world id>/<id>.md``: header lines (title, tags, status, created, and one ``anchor:``
line of JSON per thing it is about), a blank line, the text. ``<id>.json`` beside it is its data; ``auto/``
holds the harness's own journal. The model reads, searches and edits the files itself. The harness reads only
headers (cached until a file changes) to say where notes are, and writes a file only to create a note, add a
dated entry, journal a work outcome or keep the goal stack. A header that does not parse costs that note its
anchors and is reported; it never raises. Harness writes take a per-world file lock and commit the folder to
git when git is there, so the model's own edits are in the history too. Only capture contacts Minecraft.
Notes live under MODBENCH_NOTES_DIR (default <repo>/.state/notes).

Surfacing: ``surface()`` names at most SURFACE_LIMIT notes (id, title, updated) for a transition (arrival,
observing a block/entity, entering a region, session start), most relevant first, and suppresses a
note already shown in the last SHOWN_TTL_S seconds unless the player has moved MOVE_RESET blocks.
``tracked()`` wraps ``kernel().call`` for the methods that mark such transitions and attaches the
notes under a ``"notes"`` key only when non-empty. Terminal work outcomes are journaled as ``auto``
notes keyed by location (a repeat at the same place rewrites the note); they surface like any other.
"""
from __future__ import annotations

from contextlib import closing, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from typing import Any
import uuid

from mbtool import BridgeError, kernel, state, tool

SURFACE_LIMIT = 5
SUBJECT_KINDS = ("item", "topic")
SHOWN_TTL_S = 600.0      # a note shown less than this ago is not repeated...
MOVE_RESET = 48.0        # ...unless the player has moved this far since it was shown
GATE_S, GATE_BLOCKS = 3.0, 4.0  # skip the folder entirely when polled again from the same spot
STATUSES = ("open", "done", "archived")
DEFAULT_DIR = Path(__file__).resolve().parents[2] / ".state" / "notes"
WORK = {"nav.goto": "goto", "nav.route": "route", "nav.process": "process", "nav.follow": "follow", "nav.fight": "fight",
        "nav.mine": "mine", "nav.build": "build", "nav.resume": "resume"}
JOURNALED = {"mine", "build", "process", "resume"}   # route/goto/follow are journaled on failure only


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _int(value, name, minimum=0, maximum=2**63-1):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an integer in {minimum}..{maximum}")
    return value


def _text(value, name, maximum, empty=False):
    if not isinstance(value, str) or len(value) > maximum or (not empty and not value.strip()) or "\x00" in value:
        raise ValueError(f"{name} must be {'0' if empty else '1'}..{maximum} characters without NUL")
    return value


def _now():
    return datetime.now(timezone.utc)


def _pos(value):
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise ValueError("position must be [x,y,z]")
    return [_int(v, "coordinate", -30000000 if i != 1 else 0, 30000000 if i != 1 else 255) for i, v in enumerate(value)]


def anchor(value):
    """Validate one anchor, retaining only explicit provenance; entity IDs alone are never durable."""
    if not isinstance(value, dict):
        raise ValueError("anchor must be an object")
    a = json.loads(_json(value))
    kind = a.get("kind")
    keys = {"kind", "dimension", "label", "observedAt"}
    if kind in {"block", "location"}:
        keys |= {"pos", "observed"} if kind == "block" else {"pos"}
        a["pos"] = _pos(a.get("pos"))
    elif kind == "region":
        keys |= {"min", "max"}
        a["min"], a["max"] = _pos(a.get("min")), _pos(a.get("max"))
        if any(lo > hi for lo, hi in zip(a["min"], a["max"])):
            raise ValueError("region min must not exceed max")
    elif kind == "entity":
        keys |= {"uuid", "uuidScope", "lastSeen", "observed"}
        a["uuid"] = str(uuid.UUID(a.get("uuid", "")))
        if a.get("uuidScope") != "server":
            raise ValueError("entity anchor requires a server UUID from obs.entity, not a client/session UUID")
        a["lastSeen"] = _pos(a.get("lastSeen"))
    elif kind in SUBJECT_KINDS:  # not a place: an item type ("modid:name" or "modid:name:meta") or a free topic ("machine:boiler")
        keys = (keys - {"dimension"}) | {kind}
        a[kind] = _text(a.get(kind), kind, 128).strip().casefold()
    else:
        raise ValueError("anchor kind must be block, entity, location, region, item or topic")
    if set(a) - keys:
        raise ValueError(f"unknown anchor fields: {sorted(set(a)-keys)}")
    if kind not in SUBJECT_KINDS:
        _int(a.get("dimension"), "dimension", -2**31, 2**31-1)
    for key in ("label", "observedAt"):
        if key in a:
            _text(a[key], key, 256)
    if "observed" in a:
        allowed = {"id", "meta", "tileClass"} if kind == "block" else {"type", "name"}
        observed = a["observed"]
        if not isinstance(observed, dict) or set(observed) - allowed or len(_json(observed)) > 2048:
            raise ValueError("invalid anchor observation")
        if "meta" in observed:
            _int(observed["meta"], "block metadata", 0, 15)
        for key, val in observed.items():
            if key != "meta" and val is not None:
                _text(val, key, 512, empty=True)
    return a


# ---- the files ----

def notes_dir() -> Path:
    return Path(os.environ.get("MODBENCH_NOTES_DIR", DEFAULT_DIR))


@contextmanager
def _locked(world_id):
    """One harness writer per world at a time, across threads and processes (a background task writes too). Closing the file lets go."""
    notes_dir().mkdir(parents=True, exist_ok=True)
    with open(notes_dir() / f"{world_id}.lock", "a+b") as f:
        if os.name == "nt":
            import msvcrt
            f.seek(0)
            while True:
                try: msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1); break
                except OSError: time.sleep(0.01)
        else:
            import fcntl
            fcntl.flock(f, fcntl.LOCK_EX)
        yield


def _commit(root, message):
    """History: the folder is its own git repository when git is there. The model's edits ride in with the next harness write."""
    try:
        git = lambda *a: subprocess.run(["git", "-C", str(root), "-c", "user.name=notes", "-c", "user.email=notes@modbench", *a], capture_output=True, timeout=20)
        if not (root / ".git").exists(): git("init", "-q")
        if (root / ".git").exists(): git("add", "-A"); git("commit", "-qm", message)  # never the repository this folder may sit inside
    except (OSError, subprocess.SubprocessError):
        pass


def _replace(path, content):
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(content, encoding="utf-8", newline="\n")
    os.replace(tmp, path)


def _put(root, id, title, anchors, text="", tags=(), status="open", data=None, created=None, auto=False, updated=None):
    """Write one note whole: <id>.md (header, blank line, text) and, when it has data, <id>.json beside it."""
    if not isinstance(id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", id):
        raise ValueError("id must be 1..96 letters, digits, '.', '_' or '-': it is the file name")
    title = " ".join(_text(title, "title", 256).splitlines())
    _text(text, "text", 2**24, empty=True)
    tags = sorted({" ".join(_text(t, "tag", 96).replace(",", " ").split()).casefold() for t in tags or ()})
    if status not in STATUSES:
        raise ValueError("status must be open, done or archived")
    if not isinstance(anchors, list) or not 1 <= len(anchors) <= 32:
        raise ValueError("a note needs 1..32 anchors")
    if data is not None and not isinstance(data, dict):
        raise ValueError("data must be a JSON object")
    head = {"title": title, "tags": ", ".join(tags), "status": status, "created": created or _now().isoformat(timespec="seconds")}
    content = "".join(f"{k}: {v}\n" for k, v in head.items()) + "".join(f"anchor: {_json(anchor(a))}\n" for a in anchors) + "\n" + text + "\n"
    path = (root / "auto" if auto else root) / f"{id}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    if data: _replace(path.with_suffix(".json"), json.dumps(data, ensure_ascii=False, indent=1, allow_nan=False) + "\n")
    _replace(path, content)
    if updated: os.utime(path, (updated, updated))
    return path


def export(database, root):
    """Once, for a world whose notes were a SQLite database: every note becomes a file. The database stays as it was (it has the old revisions)."""
    tmp = root.with_name(root.name + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    with closing(sqlite3.connect(f"{Path(database).resolve().as_uri()}?mode=ro", uri=True)) as db:
        for (snapshot,) in db.execute("SELECT snapshot FROM notes"):
            n = json.loads(snapshot)
            _put(tmp, re.sub(r"[^A-Za-z0-9._-]", "_", n["id"]), n["title"], n["attachments"], n["text"], n["tags"], n["status"], n["data"], n["createdAt"],
                 "auto" in n["tags"], datetime.fromisoformat(n["updatedAt"]).timestamp())
    os.replace(tmp, root)
    _commit(root, f"exported from {Path(database).name}")


def folder(world_id, make=False) -> Path:
    """<notes dir>/<world id>, which may not exist yet. A database from before notes were files is exported the first time."""
    world_id = str(uuid.UUID(world_id))
    root, old = notes_dir() / world_id, notes_dir() / f"{world_id}.sqlite3"
    if not root.exists() and old.exists():
        with _locked(world_id):
            if not root.exists(): export(old, root)
    if make: root.mkdir(parents=True, exist_ok=True)
    return root


def _read(path, auto=False):
    """One note's header, cached until the file changes. A header that does not parse leaves the note without anchors and says why."""
    stat = path.stat()
    cache = state.setdefault("notes", {}).setdefault("headers", {})
    hit = cache.get(str(path))
    if hit and hit[0] == (stat.st_mtime_ns, stat.st_size):
        return hit[1]
    note = dict(id=path.stem, title=path.stem, tags=[], status="open", anchors=[], auto=auto, file=str(path), mtime=stat.st_mtime_ns,
                updated=datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()[:16])
    try:
        keys = set()
        for line in path.read_text(encoding="utf-8").split("\n\n", 1)[0].splitlines():
            key, colon, value = line.partition(":")
            key, value = key.strip(), value.strip()
            if not colon: raise ValueError(f"header line is not 'key: value': {line[:60]!r}")
            keys.add(key)
            if key == "anchor": note["anchors"].append(anchor(json.loads(value)))
            elif key == "tags": note["tags"] = sorted({t.strip().casefold() for t in value.split(",") if t.strip()})
            elif key in ("title", "status", "created") and value: note[key] = value
        if "title" not in keys: raise ValueError("the file does not start with a 'title:' line")
        if note["status"] not in STATUSES: raise ValueError("status must be open, done or archived")
    except (ValueError, OSError) as error:
        note.update(anchors=[], status="open", unreadable=str(error)[:200])
    if len(cache) > 4096: cache.clear()
    cache[str(path)] = ((stat.st_mtime_ns, stat.st_size), note)
    return note


def scan(world_id) -> list[dict]:
    """Every note of a world, by header."""
    root, out = folder(world_id), []
    for where, auto in ((root, False), (root / "auto", True)):
        for path in sorted(where.glob("*.md")):
            try: out.append(_read(path, auto))
            except OSError: pass  # removed as it was listed
    return out


def get(world_id, id) -> dict:
    root = folder(world_id)
    for path, auto in ((root / f"{id}.md", False), (root / "auto" / f"{id}.md", True)):
        if isinstance(id, str) and re.fullmatch(r"[A-Za-z0-9._-]+", id) and path.is_file():
            return _read(path, auto)
    raise ValueError("note not found in this world")


def data(note) -> dict:
    """A note's <id>.json; {} when it has none."""
    path = Path(note["file"]).with_suffix(".json")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (ValueError, OSError) as error:
        raise ValueError(f"{path} is not readable JSON: {error}") from None
    if not isinstance(value, dict):
        raise ValueError(f"{path} must hold a JSON object")
    return value


def save(world_id, id, title, anchors, text="", new=False, **more) -> dict:
    """A harness write of a whole note; with new, only when there is none by that id."""
    with _locked(world_id):
        root = folder(world_id, make=True)
        if new and any((d / f"{id}.md").exists() for d in (root, root / "auto")):
            raise ValueError(f"note {id} exists: edit {root / f'{id}.md'}")
        path = _put(root, id, title, anchors, text, **more)
        _commit(root, f"{id}: {title}"[:120])
    return _read(path, more.get("auto", False))


def append(world_id, id, text) -> dict:
    """Add a dated entry ("[2026-10-04T12:00] text", UTC) as a new last line. A retry of the entry that is already last adds nothing."""
    entry = _text(text, "text", 2**24).strip()
    with _locked(world_id):
        note = get(world_id, id)
        path = Path(note["file"])
        body = path.read_text(encoding="utf-8")
        replayed = re.search(r"(?:^|\n)\[[0-9T:-]{16}\] " + re.escape(entry) + r"\s*$", body) is not None
        if not replayed:
            gap = "" if body.endswith("\n") or not body else "\n"
            if "\n\n" not in body + gap: gap += "\n"  # a header with no text yet: the entry must not land in it
            with path.open("a", encoding="utf-8", newline="\n") as f: f.write(f"{gap}[{_now().isoformat()[:16]}] {entry}\n")
            _commit(folder(world_id), f"{id}: entry")
        return {"file": str(path), "bytes": path.stat().st_size, **({"replayed": True} if replayed else {})}


def _box_distance(a, point):
    """Euclidean distance from a point to an anchor's box (0 inside a region / at the anchor)."""
    lo = a.get("min", a.get("pos", a.get("lastSeen")))
    hi = a.get("max", lo)
    return math.sqrt(sum(max(lo[i]-point[i], 0, point[i]-hi[i])**2 for i in range(3)))


def find(world_id, dimension=None, near=None, radius=32, region=None, entity_uuid=None, subject=None, kind=None, status=None, auto=False) -> list[dict]:
    """The notes with an anchor that passes every filter given, newest-changed first. auto: False leaves the journal out, None takes both."""
    if status not in (None, *STATUSES, "all") or kind not in (None, "block", "entity", "location", "region", *SUBJECT_KINDS):
        raise ValueError("invalid status or anchor kind")
    if dimension is not None:
        _int(dimension, "dimension", -2**31, 2**31-1)
    if near is not None:
        near = _pos(near)
        if isinstance(radius, bool) or not isinstance(radius, (int, float)) or not math.isfinite(radius) or not 0 <= radius <= 30000000:
            raise ValueError("radius must be 0..30000000")
    if region is not None:
        region = anchor(dict(kind="region", dimension=0, **region))
    if (near is not None or region is not None) and dimension is None:
        raise ValueError("a search by place requires a dimension")
    if entity_uuid is not None:
        entity_uuid = str(uuid.UUID(entity_uuid))
    subjects = None if subject is None else {str(v).strip().casefold() for v in ([subject] if isinstance(subject, str) else subject)}
    def matches(a):
        if a["kind"] in SUBJECT_KINDS:  # no place: matched by subject, never by a spatial filter
            return near is None and region is None and entity_uuid is None and kind in (None, a["kind"]) and (subjects is None or a[a["kind"]] in subjects)
        if subjects is not None:
            return False
        if dimension is not None and a["dimension"] != dimension or kind is not None and a["kind"] != kind:
            return False
        if entity_uuid is not None and (a["kind"] != "entity" or a["uuid"] != entity_uuid):
            return False
        if near is not None and _box_distance(a, near) > radius:
            return False
        lo = a.get("min", a.get("pos", a.get("lastSeen")))
        hi = a.get("max", lo)
        return region is None or all(lo[i] <= region["max"][i] and hi[i] >= region["min"][i] for i in range(3))
    found = [n for n in scan(world_id) if (auto is None or n["auto"] == auto) and any(matches(a) for a in n["anchors"])
             and (n["status"] == status or status == "all" or status is None and n["status"] != "archived")]
    return sorted(found, key=lambda n: -n["mtime"])


def capture(kernel, context, kind=None, **params):
    dimension = context["dimension"]
    a = dict(kind=kind, dimension=dimension, observedAt=datetime.now(timezone.utc).isoformat())
    if kind == "entity":
        observed = kernel.call("obs.entity", **params)
        if not observed.get("found"):
            raise ValueError("entity not observed; retain existing notes")
        if observed["worldId"] != context["worldId"] or observed["dimension"] != dimension:
            raise ValueError("world changed during entity observation")
        a.update(uuid=observed["uuid"], uuidScope=observed["uuidScope"], lastSeen=[math.floor(v) for v in observed["pos"]],
                 observed={key: observed.get(key) for key in ("type", "name")})
    elif kind == "block":
        pos = _pos(params["pos"])
        observed = kernel.call("obs.block", **dict(zip("xyz", pos)))
        a.update(pos=pos, observed={key: observed[key] for key in ("id", "meta", "tileClass") if key in observed})
    elif kind == "location":
        a["pos"] = params.get("pos", context["pos"])
    elif kind == "region":
        a.update(min=params["min"], max=params["max"])
    elif kind in SUBJECT_KINDS:
        del a["dimension"]
        a[kind] = params[kind]
    else:
        raise ValueError("kind must be block, entity, location, region, item or topic")
    after = kernel.call("memory.context")
    if any(after[key] != context[key] for key in ("worldId", "dimension")):
        raise ValueError("world/dimension changed during capture; observe again")
    return anchor(a)


def lookup(kernel, **filters) -> list[dict]:
    """find() in the player's world and, unless a dimension is named, the dimension the player is in."""
    context = kernel.call("memory.context")
    if filters.get("near") == "player":
        filters["near"] = _floor(context["pos"])
    filters.setdefault("dimension", context["dimension"])
    return find(context["worldId"], **filters)


def where(kernel) -> dict:
    """The folder of this world's notes, and the files in it whose header cannot be read."""
    world = kernel.call("memory.context", timeout=5)["worldId"]
    bad = [{"file": n["file"], "why": n["unreadable"]} for n in scan(world) if "unreadable" in n]
    return {"notesFolder": str(folder(world, make=True)), **({"notesUnreadable": bad} if bad else {})}


# ---- surfacing as a side effect ----

def _floor(pos):
    return [int(math.floor(v)) for v in pos]


def _dist(a, b):
    return math.sqrt(sum((a[i]-b[i])**2 for i in range(3)))


def _log(msg):
    print(f"[notes] {msg}", file=sys.stderr, flush=True)


def surface(kernel, *, position=None, dimension=None, block=None, entity=None, subjects=None, reason="", radius=16, context=None) -> list[dict]:
    """At most SURFACE_LIMIT notes relevant to a transition, as id, title and updated, most relevant first; [] when nothing new.

    Exactly one focus: ``entity`` (server UUID), ``block`` ([x,y,z]: notes anchored there or regions
    containing it), or a position (``position`` or the player's feet) with ``radius``. Never raises.
    """
    try:
        cache = state.setdefault("notes", {})
        now = time.monotonic()
        if position is not None and block is None and entity is None:
            last = cache.get("last")
            if last and last[0] == dimension and now - last[1] < GATE_S and _dist(last[2], position) < GATE_BLOCKS:
                return []
        context = context or kernel.call("memory.context", timeout=5)
        world = context["worldId"]
        dimension = context["dimension"] if dimension is None else dimension
        here = _floor(position or context["pos"])
        cache["last"] = (dimension, now, here)
        if subjects is not None:
            spot, found = here, find(world, subject=subjects, auto=None) if subjects else []
        elif entity is not None:
            spot, found = here, find(world, entity_uuid=entity, auto=None)
        elif block is not None:
            spot = _floor(block)
            found = find(world, dimension, near=spot, radius=0, auto=None)
        else:
            spot, found = here, find(world, dimension, near=here, radius=radius, auto=None)
        shown = cache.setdefault("shown", {})
        out = []
        for note in sorted(found, key=lambda n: (_nearest(n, spot, dimension), n["status"] != "open")):
            key = (note["id"], note["mtime"])
            prior = shown.get(key)
            if prior and now - prior[0] < SHOWN_TTL_S and _dist(prior[1], here) < MOVE_RESET:
                continue
            shown[key] = (now, here)
            out.append({"id": note["id"], "title": note["title"], "updated": note["updated"]})  # that the note exists and how old it is: its file has the rest
            if len(out) == SURFACE_LIMIT:
                break
        if len(shown) > 512:
            for key in sorted(shown, key=lambda k: shown[k][0])[:256]:
                del shown[key]
        return out
    except Exception as e:  # surfacing is a side effect; it must never break the tool that triggered it
        _log(f"surface({reason}) skipped: {e}")
        return []


def _nearest(note, spot, dimension):
    return min((_box_distance(a, spot) for a in note["anchors"] if a.get("dimension") == dimension and a["kind"] not in SUBJECT_KINDS), default=math.inf)


def item_subjects(result, limit=64):
    """The item types named anywhere in a result, as "id:meta" and "id", for surfacing item notes."""
    out, stack = [], [result]
    while stack and len(out) < limit * 2:
        value = stack.pop()
        if isinstance(value, dict):
            if isinstance(value.get("id"), str) and ":" in value["id"]:
                out += [f'{value["id"]}:{value.get("meta", 0)}'.casefold(), value["id"].casefold()]
            stack.extend(value.values())
        elif isinstance(value, list):
            stack.extend(value)
    return sorted(set(out))


def with_item_notes(result, reason="item"):
    """Attach notes about the item types a result mentions; the usual cap and show-once rules apply."""
    return attach(result, surface(kernel(), subjects=item_subjects(result), reason=reason))


def attach(result, found):
    """Adds the notes under "notes" when there are any and the result is a JSON object."""
    return {**result, "notes": found} if found and isinstance(result, dict) else result


def _work_pos(result):
    if not isinstance(result, dict):
        return None
    for value in ((result.get("arrival") or {}).get("pos"), result.get("origin"), result.get("goal"), result.get("target")):
        if isinstance(value, list) and len(value) == 3 and all(isinstance(v, (int, float)) for v in value):
            return _floor(value)
    return None


def journal(kernel, method, result, error=None, context=None):
    """Auto-journal a terminal work outcome as a note in auto/ at its location; a repeat there rewrites the note."""
    kind = WORK.get(method)
    if kind is None:
        return None
    receipt = result if error is None else ((error.reply or {}).get("error") or {}).get("receipt")
    receipt = receipt if isinstance(receipt, dict) else {}
    if error is None and (kind not in JOURNALED or receipt.get("state") != "succeeded"):
        return None
    if error is not None and getattr(error, "code", "") == "cancelled":
        return None
    context = context or kernel.call("memory.context", timeout=5)
    pos = _work_pos(receipt) or _floor(context["pos"])
    dim = context["dimension"]
    cell = [v // 4 * 4 for v in pos]
    outcome = "failed" if error is not None else "done"
    facts = {k: receipt.get(k) for k in ("jobId", "action", "state", "reason", "blocksPlaced", "blocksMined", "placed", "removed", "ticks") if receipt.get(k) is not None}
    if error is not None:
        facts.update(code=error.code, msg=error.msg)
    reason = facts.get("reason") or facts.get("msg") or ""
    title = f"{kind} {outcome} at {pos[0]},{pos[1]},{pos[2]}" + (f": {reason}"[:200] if reason else "")
    return save(context["worldId"], f"auto-{kind}-{dim}-{cell[0]}-{cell[1]}-{cell[2]}", title[:256], [{"kind": "location", "dimension": dim, "pos": pos, "label": f"{kind} {outcome}"}],
                _json(facts), tags=["auto", kind, outcome], status="open" if error else "done", auto=True)


def after(method, params, result):
    """Post-call hook: surfaces notes for the transitions a method marks; returns the (possibly annotated) result."""
    try:
        k = kernel()
        found = []
        if method == "obs.block" and isinstance(result, dict):
            pos = params.get("pos") or [params.get("x"), params.get("y"), params.get("z")]
            found = surface(k, block=result.get("pos") or pos, reason="block")
        elif method in ("obs.tile", "obs.waila") and isinstance(result, dict) and isinstance(result.get("pos"), list):
            found = surface(k, block=result["pos"], reason="block")
        elif method == "obs.entity" and isinstance(result, dict) and result.get("found") and result.get("uuidScope") == "server":
            found = surface(k, entity=result["uuid"], reason="entity")
        elif method in ("obs.player", "memory.context") and isinstance(result, dict) and isinstance(result.get("pos"), list):
            found = surface(k, position=result["pos"], dimension=result.get("dimension"), reason="position",
                            context=result if method == "memory.context" else None)
        elif method in WORK:
            context = k.call("memory.context", timeout=5)
            try:
                journal(k, method, result, context=context)
            except Exception as e:
                _log(f"journal({method}) skipped: {e}")
            found = surface(k, position=_work_pos(result) or context["pos"], reason="arrival", context=context)
            if isinstance(result, dict): result = {**result, "endedAt": _ended(context)}
        return attach(result, found)
    except Exception as e:
        _log(f"after({method}) skipped: {e}")
        return result


def _ended(context) -> list:
    return [round(v, 1) for v in context["pos"]]


def tracked(method, timeout=None, **params):
    """kernel().call plus note side effects: surfacing for reads/arrivals, auto-journal for work outcomes and failures."""
    try:
        result = kernel().call(method, **({"timeout": timeout} if timeout is not None else {}), **params)
    except BridgeError as error:
        if method in WORK:
            try:
                journal(kernel(), method, None, error=error)
            except Exception as e:
                _log(f"journal({method}) skipped: {e}")
            try:  # where the job left you is part of what it did, most of all when it stopped short
                receipt = ((error.reply or {}).get("error") or {}).get("receipt")
                if isinstance(receipt, dict): receipt["endedAt"] = _ended(kernel().call("memory.context", timeout=5))
            except Exception as e:
                _log(f"endedAt({method}) skipped: {e}")
        raise
    return after(method, params, result)


# ---- tools ----

@tool(lane="read", coverage=["memory"])
def mb_notes(method: str = "find", params: dict | None = None) -> Any:
    """World notes are files, one <id>.md each, in the folder mb_status names (notesFolder): read, search and edit them with your shell. find and capture are what a file cannot do.

    A note is header lines, a blank line, then the text. The header: title, tags
    (comma separated), status (open, done or archived), created, and one "anchor:"
    line for each thing the note is about. Every line of it is yours to edit, and the
    file's modification time is when the note last changed. <id>.json beside a note is
    its data. auto/ holds the notes the harness journals for work outcomes. A header
    that cannot be read costs the note its anchors until it is mended: find and
    mb_status name such files under "notesUnreadable".
    Notes surface on their own (under "notes", as id, title and updated) when you arrive
    somewhere, observe a block/entity that has one, enter an annotated region, or call
    mb_status; a note on an item when that item shows up in mb_inventory, mb_item_info
    or mb_recipes.
    find: {near:[x,y,z]|player,radius:32,region:{min,max},entity_uuid,subject,kind,
    dimension,status:open|done|archived|all,auto:false}. The notes with an anchor that
    passes every filter given, newest-changed first, each with its file and anchors.
    subject is an item ("modid:name" or "modid:name:meta") or a topic, or a list of them.
    Defaults to the current dimension and leaves out archived notes and auto/
    (auto:true lists auto/ instead); dimension:null takes every dimension (near and
    region need one). Entity proximity uses lastSeen.
    capture: one anchor as mb_note_new takes it, observed now and not saved. Returns
    the "anchor:" line to add to the header of a note you already have.
    Notes are annotations, not protection rules or verified facts.
    """
    k, params = kernel(), dict(params or {})
    if method == "capture":
        a = capture(k, k.call("memory.context"), **params)
        return {"anchor": a, "line": f"anchor: {_json(a)}"}
    if method == "find":
        found = lookup(k, **params)
        return {**where(k), "notes": [{"id": n["id"], "title": n["title"], "updated": n["updated"], "file": ("auto/" if n["auto"] else "") + n["id"] + ".md",
                                       **{key: n[key] for key in ("tags", "status") if n[key] not in ([], "open")}, "anchors": n["anchors"]} for n in found]}
    raise ValueError("notes method must be find or capture")


@tool(coverage=["memory"])
def mb_note_new(id: str, title: str, anchors: list, text: str = "", tags: list | None = None, data: dict | None = None) -> Any:
    """Create a note: writes <id>.md with its header and text, and returns the file. After that the file is the note.

    anchors, one or more, say what the note is about: {kind:block,pos:[x,y,z]},
    {kind:entity,entityId:observedId} or uuid, {kind:location,pos?:[x,y,z]},
    {kind:region,min:[x,y,z],max:[x,y,z]}, {kind:item,item:"modid:name" or
    "modid:name:meta"} for an item TYPE (there is no per-stack identity),
    {kind:topic,topic:"machine:boiler"} for anything that is not a place: a machine
    kind, a mod, a quest, a technique, a wiki lesson. Places are in the dimension you
    are in; a block or entity is observed now and what was seen is kept in the anchor.
    Entity UUIDs must come from the server; use obs.entities to discover transient IDs.
    id is the file name: letters, digits, '.', '_' and '-'. data is a JSON object
    written to <id>.json. Fails when a note by that id exists.
    """
    k = kernel(); context = k.call("memory.context")
    note = save(context["worldId"], id, title, [capture(k, context, **a) for a in anchors], text, new=True, tags=tags, data=data)
    return {"file": note["file"], "anchors": note["anchors"]}


@tool(coverage=["memory"])
def mb_note_append(id: str, text: str) -> Any:
    """Add a dated entry to the end of a note: a new last line, "[2026-10-04T12:00] " (now, UTC) and then your text.

    No entry is lost when something else is appending to the same note. A retry whose
    text is already the note's last entry adds nothing. Fails when there is no note
    by that id (mb_note_new creates one). Returns the file and its size in bytes.
    """
    return append(kernel().call("memory.context")["worldId"], id, text)


# ---- goal stack ----

GOAL_ID, GOAL_FIELDS, STALE_TICKS = "goal-stack", ("chapter", "quest", "subgoal", "serves"), 24000


def goal(kernel, changes=None):
    """The pinned goal note plus a stall signal: game ticks since the sub-goal or the inventory last changed."""
    context = kernel.call("memory.context", timeout=5)
    try:
        note = get(context["worldId"], GOAL_ID)
        stack = {} if note["status"] == "archived" else data(note)  # archiving the note clears the stack
    except ValueError:
        note, stack = {}, {}
    changes = {k: _int(v, k, 0, 100) if k == "progress" else _text(v, k, 512, empty=True).strip() for k, v in (changes or {}).items() if v is not None}
    if changes:
        if "progress" not in changes and changes.get("quest", stack.get("quest")) != stack.get("quest"): stack.pop("progress", None)  # the estimate was for the quest before
        stack.update(changes, setAt=datetime.now(timezone.utc).isoformat())
        text = " / ".join(f"{k}: {stack[k]}" for k in GOAL_FIELDS if stack.get(k))
        save(context["worldId"], GOAL_ID, "Goal stack", [{"kind": "topic", "topic": "goal"}], text, tags=["goal"], data=stack, created=note.get("created"))
    if not stack:
        return {"unset": "no goal stack yet: call mb_goal(chapter=..., quest=..., subgoal=..., serves=...)"}
    watch = state.setdefault("goal", {})
    ticks = kernel.call("time.status", timeout=5).get("state", {}).get("simulationTicks", 0)
    mark = hashlib.sha256(_json([stack.get("subgoal"), kernel.call("obs.inventory", detail="counts", timeout=5)]).encode()).hexdigest()
    if mark != watch.get("mark"):
        watch.update(mark=mark, quiet=0)
    else:
        watch["quiet"] = watch.get("quiet", 0) + max(0, ticks - watch.get("ticks", ticks))  # the counter restarts with the server
    watch["ticks"] = ticks
    out = {**{k: stack.get(k, "") for k in GOAL_FIELDS}, "setAt": stack.get("setAt"), "quietGameMinutes": round(watch["quiet"] / 1200, 1)}
    if "progress" in stack: out["progress"] = stack["progress"]
    if watch["quiet"] >= STALE_TICKS:
        out["stale"] = "same sub-goal and same inventory for a game day of running time: say in one sentence why, then change something or re-scope. A running job, an armed wait, or work on the base that does not pass through your hands (wiring, configuring, reading before a build) is a fine reason; put it in the sub-goal."
    return out


@tool(coverage=["memory"])
def mb_goal(chapter: str | None = None, quest: str | None = None, subgoal: str | None = None, serves: str | None = None, progress: int | None = None) -> Any:
    """Read or update your goal stack: chapter > current quest > working sub-goal. One cheap call; only the fields you pass change.

    chapter changes rarely; quest changes when one is claimed and verified or parked
    with a note; subgoal is the immediate step ("mine copper for the bronze quest") and
    should be rewritten whenever you switch. serves names what the sub-goal is for when
    it is not the current quest: a named investment ("furnace bank: ingots for
    everything this chapter builds"). If you cannot say what a sub-goal serves, you have drifted.
    progress is a number, 0 to 100: how far through the current quest you think you
    are right now. Pass it whenever you rewrite the sub-goal. It is a quick guess for
    the people watching: do not work it out or look anything up for it, and it may go
    down as well as up. A new quest clears it.
    mb_status returns this stack every time, with quietGameMinutes (running game time
    since the sub-goal or your inventory last changed) and a stale flag after a game day.
    It lives in the note "goal-stack", so it survives compaction, restarts and crashes.
    """
    return goal(kernel(), {"chapter": chapter, "quest": quest, "subgoal": subgoal, "serves": serves, "progress": progress})
