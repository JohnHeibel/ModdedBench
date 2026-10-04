# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Durable world notes (SQLite, one file per server world) and their surfacing as a side effect of play.

Only capture/resolve contact Minecraft. Stored observations are never silently refreshed.
Notes live under MODBENCH_NOTES_DIR (default <repo>/.state/notes). Store objects are cached in
``mbtool.state["notes"]["stores"]``; the "already shown" cache is ``mbtool.state["notes"]["shown"]``.

Surfacing: ``surface()`` names at most SURFACE_LIMIT notes (id, title, updated) for a transition (arrival,
observing a block/entity, entering a region, session start), most relevant first, and suppresses a
note already shown in the last SHOWN_TTL_S seconds unless the player has moved MOVE_RESET blocks.
``tracked()`` wraps ``kernel().call`` for the methods that mark such transitions and attaches the
notes under a ``"notes"`` key only when non-empty. Terminal work outcomes are journaled as ``auto``
notes keyed by location (a repeat at the same place updates instead of duplicating); ``auto`` notes
never surface and are left out of a search unless it asks for them.

Search is grep: one literal piece of text or a regular expression, matched line by line. Nothing is
scored or ranked; results come newest-changed first (history sequence), a page at a time. The only
durable clock is the wall clock written on each revision (createdAt/updatedAt, UTC): memory.context
carries no game time and the simulation tick counter restarts with the server.
"""
from __future__ import annotations

from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import sys
import time
from typing import Any
import uuid

from mbtool import BridgeError, kernel, state, tool

SURFACE_LIMIT = 5
SUBJECT_KINDS = ("item", "topic")
SHOWN_TTL_S = 600.0      # a note shown less than this ago is not repeated...
MOVE_RESET = 48.0        # ...unless the player has moved this far since it was shown
GATE_S, GATE_BLOCKS = 3.0, 4.0  # skip the store entirely when polled again from the same spot
SEARCH_CHARS = 6000      # characters of "notes" one mb_notes search returns (max_chars) unless it asks for another size
HITS, LINE = 5, 160      # per note in a search: matching lines shown, and characters of each line
UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400}
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


def _age(then, now):
    """How long ago in real time, coarsely: 40m, 5h, 3d."""
    s = max(0, int((now - then).total_seconds()))
    return f"{s // 60}m" if s < 7200 else f"{s // 3600}h" if s < 172800 else f"{s // 86400}d"


def _when(value, name, now):
    """A time filter: an age ("90m", "5h", "3d": that long before now) or a UTC time ("2026-10-04T12:00")."""
    try:
        if isinstance(value, str) and value[-1:] in UNITS and value[:-1].isdigit():
            return now - timedelta(seconds=int(value[:-1]) * UNITS[value[-1]])
        at = datetime.fromisoformat(value)
        return at if at.tzinfo else at.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError, OverflowError):
        raise ValueError(f'{name} must be an age like "90m", "5h", "3d" or a UTC time like "2026-10-04T12:00"') from None


def _grep(lines, hits, pattern, context):
    """The matching lines and their neighbours as grep -n -C prints them ("12:" a match, "13-" context); a long line is cut around its match."""
    out = []
    for j in sorted({j for i in hits for j in range(max(0, i - context), min(len(lines), i + context + 1))}):
        m = pattern.search(lines[j])
        start = max(0, m.start() - LINE // 2) if m else 0
        out.append(f"{j + 1}{':' if m else '-'}{'...' if start else ''}{lines[j][start:start + LINE]}")
    return "\n".join(out)


def _pos(value):
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise ValueError("position must be [x,y,z]")
    return [_int(v, "coordinate", -30000000 if i != 1 else 0, 30000000 if i != 1 else 255) for i, v in enumerate(value)]


def attachment(value):
    """Validate anchors, retaining only explicit provenance; entity IDs alone are never durable."""
    if not isinstance(value, dict):
        raise ValueError("attachment must be an object")
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
            raise ValueError("entity attachment requires a server UUID from obs.entity, not a client/session UUID")
        a["lastSeen"] = _pos(a.get("lastSeen"))
    elif kind in SUBJECT_KINDS:  # not a place: an item type ("modid:name" or "modid:name:meta") or a free topic ("machine:boiler")
        keys = (keys - {"dimension"}) | {kind}
        a[kind] = _text(a.get(kind), kind, 128).strip().casefold()
    else:
        raise ValueError("attachment kind must be block, entity, location, region, item or topic")
    if set(a) - keys:
        raise ValueError(f"unknown attachment fields: {sorted(set(a)-keys)}")
    if kind not in SUBJECT_KINDS:
        _int(a.get("dimension"), "dimension", -2**31, 2**31-1)
    for key in ("label", "observedAt"):
        if key in a:
            _text(a[key], key, 256)
    if "observed" in a:
        allowed = {"id", "meta", "tileClass"} if kind == "block" else {"type", "name"}
        observed = a["observed"]
        if not isinstance(observed, dict) or set(observed) - allowed or len(_json(observed)) > 2048:
            raise ValueError("invalid attachment observation")
        if "meta" in observed:
            _int(observed["meta"], "block metadata", 0, 15)
        for key, val in observed.items():
            if key != "meta" and val is not None:
                _text(val, key, 512, empty=True)
    return a


class NotesStore:
    """One database per server world UUID, across dimensions. Each operation opens its own connection."""
    def __init__(self, path, world_id):
        self.path = Path(path)
        self.world_id = str(uuid.UUID(world_id))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1):
                raise ValueError(f"unsupported notes database version {version}; database preserved")
            db.execute("CREATE TABLE IF NOT EXISTS meta (world_id TEXT NOT NULL)")
            row = db.execute("SELECT world_id FROM meta").fetchone()
            if row and row[0] != self.world_id:
                raise ValueError("notes database world identity mismatch")
            if row is None:
                db.execute("INSERT INTO meta VALUES (?)", (self.world_id,))
            db.execute("CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, revision INTEGER NOT NULL, snapshot TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS history (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, revision INTEGER NOT NULL, operation TEXT UNIQUE NOT NULL, request_hash TEXT NOT NULL, snapshot TEXT NOT NULL, UNIQUE(id,revision))")
            db.execute("CREATE INDEX IF NOT EXISTS history_note_sequence ON history(id,seq)")
            db.execute("PRAGMA user_version=1")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            return db
        except BaseException:
            db.close()
            raise

    def status(self):
        with closing(self.connect()) as db:
            return {"worldId": self.world_id, "file": str(self.path.resolve()), "format": 1,
                    "notes": db.execute("SELECT count(*) FROM notes").fetchone()[0],
                    "sequence": db.execute("SELECT coalesce(max(seq),0) FROM history").fetchone()[0],
                    "integrity": db.execute("PRAGMA quick_check").fetchone()[0]}

    def get(self, id):
        with closing(self.connect()) as db:
            row = db.execute("SELECT snapshot FROM notes WHERE id=?", (_text(id, "id", 96),)).fetchone()
            if row is None:
                raise ValueError("note not found in this world")
            return json.loads(row[0])

    def write(self, id, expected_revision, operation_id, patch):
        _text(id, "id", 96)
        _text(operation_id, "operation_id", 128)
        entry = expected_revision is None  # append(): one dated entry, on whatever revision is current
        if not entry:
            _int(expected_revision, "expected_revision")
        if not isinstance(patch, dict) or not patch or set(patch) - ({"append"} if entry else {"title", "text", "tags", "status", "attachments", "data"}):
            raise ValueError("patch must contain title, text, tags, status, attachments and/or data")
        request_hash = hashlib.sha256(_json([id, expected_revision, patch]).encode()).hexdigest()
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            prior = db.execute("SELECT request_hash,snapshot,seq FROM history WHERE operation=?", (operation_id,)).fetchone()
            if prior:
                if prior[0] != request_hash:
                    raise ValueError("operation_id already used with different arguments")
                return {"saved": True, "replayed": True, "sequence": prior[2], "note": json.loads(prior[1])}
            row = db.execute("SELECT revision,snapshot FROM notes WHERE id=?", (id,)).fetchone()
            actual = row[0] if row else 0
            if entry and not row:
                raise ValueError("note not found in this world")
            if not entry and actual != expected_revision:
                raise ValueError(f"note revision conflict: expected {expected_revision}, current {actual}; read before editing")
            now = _now().isoformat()
            note = json.loads(row[1]) if row else dict(id=id, worldId=self.world_id, createdAt=now, tags=[], status="open", data={})
            if entry:
                note["text"] = "\n".join(filter(None, [note["text"].rstrip(), f"[{now[:16]}] {_text(patch['append'], 'text', 32768).strip()}"]))
            else:
                note.update(patch)
            _text(note.get("title"), "title", 256)
            _text(note.get("text"), "text", 32768, empty=True)
            if not isinstance(note["tags"], list) or len(note["tags"]) > 32:
                raise ValueError("tags must be a list of at most 32 strings")
            note["tags"] = sorted({_text(t, "tag", 96).strip().casefold() for t in note["tags"]})
            if note["status"] not in {"open", "done", "archived"}:
                raise ValueError("status must be open, done or archived")
            if not isinstance(note.get("attachments"), list) or not 1 <= len(note["attachments"]) <= 32:
                raise ValueError("note requires 1..32 attachments")
            note["attachments"] = [attachment(a) for a in note["attachments"]]
            if not isinstance(note["data"], dict) or len(_json(note["data"]).encode()) > 16384:
                raise ValueError("data must be a JSON object of at most 16 KiB")
            note.update(revision=actual+1, updatedAt=now)
            serialized = _json(note)
            if len(serialized.encode()) > 128*1024:
                raise ValueError("note exceeds 128 KiB")
            db.execute("INSERT INTO notes VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,snapshot=excluded.snapshot", (id, actual+1, serialized))
            seq = db.execute("INSERT INTO history(id,revision,operation,request_hash,snapshot) VALUES (?,?,?,?,?)", (id, actual+1, operation_id, request_hash, serialized)).lastrowid
            return {"saved": True, "replayed": False, "sequence": seq, "note": note}

    def append(self, id, operation_id, text):
        """Add a dated entry ("[2026-10-04T12:00] text", UTC) as a new last line of an existing note. No revision guard: entries only add."""
        return self.write(id, None, operation_id, {"append": text})

    def history(self, id, before_revision=None, limit=20):
        _int(limit, "limit", 1, 100)
        before = _int(before_revision, "before_revision", 1) if before_revision is not None else 2**63-1
        with closing(self.connect()) as db:
            rows = db.execute("SELECT snapshot FROM history WHERE id=? AND revision<? ORDER BY revision DESC LIMIT ?", (_text(id, "id", 96), before, limit+1)).fetchall()
        notes = [json.loads(row[0]) for row in rows[:limit]]
        return {"revisions": notes, "nextBeforeRevision": notes[-1]["revision"] if len(rows) > limit else None}

    def search(self, query="", tags=None, status=None, kind=None, dimension=None, near=None, radius=32, region=None, entity_uuid=None, subject=None, cursor=None, limit=20, detail="summary",
               regex=False, case=False, context=1, since=None, before=None, author=None, max_chars=None):
        """Grep over the notes that pass every filter, newest-changed first. A note matches or it does not: nothing is scored."""
        _int(limit, "limit", 1, 100)
        _int(context, "context", 0, 5)
        if max_chars is not None:
            _int(max_chars, "max_chars", 1000, 20000)
        if detail not in ("summary", "full"):
            raise ValueError("detail must be summary or full")
        _text(query, "query", 512, empty=True)
        regex, case = bool(regex), bool(case)
        try:
            pattern = re.compile(query if regex else re.escape(query), 0 if case else re.IGNORECASE) if query else None
        except re.error as error:
            raise ValueError(f"query is not a valid regular expression: {error}") from None
        if tags is not None and (not isinstance(tags, list) or len(tags) > 32):
            raise ValueError("tags must be a list of at most 32 strings")
        tags = {_text(t, "tag", 96).strip().casefold() for t in tags or []}
        if status not in (None, "open", "done", "archived", "all") or kind not in (None, "block", "entity", "location", "region", *SUBJECT_KINDS):
            raise ValueError("invalid status or attachment kind")
        if author not in (None, "me", "auto", "all"):
            raise ValueError("author must be me, auto or all")
        author = author or ("all" if "auto" in tags else "me")  # asking for the tag is asking for them
        if dimension is not None:
            _int(dimension, "dimension", -2**31, 2**31-1)
        if near is not None:
            near = _pos(near)
            if isinstance(radius, bool) or not isinstance(radius, (int, float)) or not math.isfinite(radius) or not 0 <= radius <= 30000000:
                raise ValueError("radius must be 0..30000000")
        if region is not None:
            region = attachment(dict(kind="region", dimension=0, **region))
        if (near is not None or region is not None) and dimension is None:
            raise ValueError("spatial search requires a dimension")
        if entity_uuid is not None:
            entity_uuid = str(uuid.UUID(entity_uuid))
        fingerprint = hashlib.sha256(_json([self.world_id, query, regex, case, since, before, author, sorted(tags), status, kind, dimension, near, radius, region, entity_uuid, subject]).encode()).hexdigest()
        now = _now()
        since, before = (v if v is None else _when(v, name, now) for name, v in (("since", since), ("before", before)))
        after, ceiling = 2**63-1, None
        if cursor is not None:
            if not isinstance(cursor, dict) or cursor.get("query") != fingerprint:
                raise ValueError("cursor belongs to another query/world")
            after = _int(cursor.get("after"), "cursor.after")
            ceiling = _int(cursor.get("sequence"), "cursor.sequence")
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
        with closing(self.connect()) as db:
            if ceiling is None:
                ceiling = db.execute("SELECT coalesce(max(seq),0) FROM history").fetchone()[0]
            rows = db.execute("SELECT h.seq,h.snapshot FROM notes n JOIN history h ON h.seq=(SELECT max(seq) FROM history WHERE id=n.id AND seq<=?) WHERE h.seq<? ORDER BY h.seq DESC", (ceiling, after))
            found, size, more = [], 1, False  # size: characters of the "notes" list as the server serialises it
            for seq, snapshot in rows:
                note = json.loads(snapshot)
                if status not in (None, "all") and note["status"] != status or status is None and note["status"] == "archived":
                    continue
                changed = datetime.fromisoformat(note["updatedAt"])
                if author != "all" and ("auto" in note["tags"]) != (author == "auto") or since and changed < since or before and changed >= before:
                    continue
                if not tags.issubset(note["tags"]) or not any(matches(a) for a in note["attachments"]):
                    continue
                lines = note["text"].split("\n")
                hits = [i for i, line in enumerate(lines) if pattern.search(line)] if pattern else []
                if pattern and not hits and not pattern.search(" ".join([note["id"], note["title"], *note["tags"]])):
                    continue
                result = dict(note, age=_age(changed, now))
                if detail == "summary":
                    result = dict({k: v for k, v in result.items() if k not in {"text", "data"}}, bodyCharacters=len(note["text"]),
                                  excerpt=_grep(lines, hits[:HITS], pattern, context) if hits else note["text"][:280], **({"matchingLines": len(hits)} if hits else {}))
                cost = len(_json(result)) + 1
                if len(found) == limit or max_chars and found and size + cost > max_chars:
                    more = True
                    break
                if max_chars and size + cost > max_chars:  # one note larger than the whole page: name it, do not send it
                    result = dict({k: result[k] for k in ("id", "title", "revision", "updatedAt", "age")}, bodyCharacters=len(note["text"]), omitted="larger than max_chars: get reads it")
                    cost = len(_json(result)) + 1
                found.append(result)
                size, after = size + cost, seq
        return {"worldId": self.world_id, "notes": found, "detail": detail, "sequence": ceiling, "now": now.isoformat()[:16],
                "nextCursor": {"after": after, "sequence": ceiling, "query": fingerprint} if more else None,
                "spatialBasis": "stored attachment coordinates; entity lastSeen is historical"}

    def backup(self, output):
        """Consistent standalone SQLite backup, including committed WAL; never copy only a live .sqlite3 file."""
        path = Path(output).resolve()
        if path.exists():
            raise ValueError("backup destination already exists")
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as source, closing(sqlite3.connect(path)) as target:
            source.backup(target)
        return str(path)


def _box_distance(a, point):
    """Euclidean distance from a point to an attachment's box (0 inside a region / at the anchor)."""
    lo = a.get("min", a.get("pos", a.get("lastSeen")))
    hi = a.get("max", lo)
    return math.sqrt(sum(max(lo[i]-point[i], 0, point[i]-hi[i])**2 for i in range(3)))


def notes_dir() -> Path:
    return Path(os.environ.get("MODBENCH_NOTES_DIR", DEFAULT_DIR))


def store_for(world_id) -> NotesStore:
    """Cached per world in mbtool.state (survives reloads); the schema check runs once per process."""
    world_id = str(uuid.UUID(world_id))
    path = notes_dir() / f"{world_id}.sqlite3"
    stores = state.setdefault("notes", {}).setdefault("stores", {})
    store = stores.get(world_id)
    if store is None or store.path != path:
        store = stores[world_id] = NotesStore(path, world_id)
    return store


def capture(kernel, context, kind, **params):
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
    return {"worldId": context["worldId"], "attachment": attachment(a)}


def read_notes(kernel, method, params):
    context = kernel.call("memory.context")
    params = dict(params or {})
    if method == "context":
        return context
    if method == "capture":
        return capture(kernel, context, **params)
    store = store_for(context["worldId"])
    if method == "status":
        return dict(store.status(), context=context)
    if method == "get":
        note, now = store.get(**params), _now()
        return dict(note, age=_age(datetime.fromisoformat(note["updatedAt"]), now), now=now.isoformat()[:16])
    if method == "history":
        return store.history(**params)
    if method == "search":
        if params.get("near") == "player":
            params["near"] = context["pos"]
        params.setdefault("dimension", context["dimension"])
        return store.search(**params)
    if method == "resolve":
        note = store.get(params["id"])
        resolved = []
        for a in note["attachments"]:
            result = dict(attachment=a, status="annotation")
            if a["kind"] in SUBJECT_KINDS:
                pass
            elif a["dimension"] != context["dimension"]:
                result["status"] = "different_dimension"
            elif a["kind"] in {"block", "entity"}:
                try:
                    args = dict(uuid=a["uuid"]) if a["kind"] == "entity" else dict(pos=a["pos"])
                    current = capture(kernel, context, a["kind"], **args)["attachment"]
                    result.update(current=current, status="observed")
                    if a["kind"] == "block":
                        result["status"] = "identity_matches" if a.get("observed") and a["observed"] == current.get("observed") else "identity_changed_or_unrecorded"
                except (BridgeError, ValueError) as error:
                    result.update(status="not_observed", reason=str(error))
            resolved.append(result)
        return {"note": note, "resolved": resolved, "saved": False,
                "identityLimit": "matching block ID/metadata cannot distinguish replacement by an identical block"}
    raise ValueError("notes method must be context, status, capture, get, search, history or resolve")


def write_note(kernel, world_id, id, expected_revision, operation_id, patch):
    context = kernel.call("memory.context")
    if str(uuid.UUID(world_id)) != context["worldId"]:
        raise ValueError("world changed; note write refused")
    return store_for(world_id).write(id, expected_revision, operation_id, patch)


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
        world = str(uuid.UUID(context["worldId"]))
        if not (notes_dir() / f"{world}.sqlite3").exists():
            return []
        dimension = context["dimension"] if dimension is None else dimension
        here = _floor(position or context["pos"])
        cache["last"] = (dimension, now, here)
        store = store_for(world)
        if subjects is not None:
            anchor, found = here, store.search(subject=subjects, limit=100)["notes"] if subjects else []
        elif entity is not None:
            anchor, found = here, store.search(entity_uuid=entity, limit=100)["notes"]
        elif block is not None:
            anchor = _floor(block)
            found = store.search(dimension=dimension, near=anchor, radius=0, limit=100)["notes"]
        else:
            anchor, found = here, store.search(dimension=dimension, near=here, radius=radius, limit=100)["notes"]
        shown = cache.setdefault("shown", {})
        out = []
        for note in sorted(found, key=lambda n: (_nearest(n, anchor, dimension)[0], n["status"] != "open")):
            key = (note["id"], note["revision"])
            prior = shown.get(key)
            if prior and now - prior[0] < SHOWN_TTL_S and _dist(prior[1], here) < MOVE_RESET:
                continue
            shown[key] = (now, here)
            out.append(_compact(note))
            if len(out) == SURFACE_LIMIT:
                break
        if len(shown) > 512:
            for key in sorted(shown, key=lambda k: shown[k][0])[:256]:
                del shown[key]
        return out
    except Exception as e:  # surfacing is a side effect; it must never break the tool that triggered it
        _log(f"surface({reason}) skipped: {e}")
        return []


def _nearest(note, anchor, dimension):
    best = (math.inf, None)
    for a in note["attachments"]:
        if a.get("dimension") == dimension and a["kind"] not in SUBJECT_KINDS:
            best = min(best, (_box_distance(a, anchor), a), key=lambda x: x[0])
    return best if best[1] is not None else (math.inf, note["attachments"][0])


def _compact(note):
    """What rides along on another tool's result: that the note exists and how old it is. mb_notes get reads it."""
    return {"id": note["id"], "title": note["title"], "updated": (note.get("updatedAt") or "")[:16]}


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
    """Auto-journal a terminal work outcome as an ``auto`` note at its location; repeats there update the note."""
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
    note_id = f"auto-{kind}-{dim}-{cell[0]}-{cell[1]}-{cell[2]}"
    facts = {k: receipt.get(k) for k in ("jobId", "action", "state", "reason", "blocksPlaced", "blocksMined", "placed", "removed", "ticks") if receipt.get(k) is not None}
    if error is not None:
        facts.update(code=error.code, msg=error.msg)
    reason = facts.get("reason") or facts.get("msg") or ""
    title = f"{kind} {outcome} at {pos[0]},{pos[1]},{pos[2]}" + (f": {reason}"[:200] if reason else "")
    store = store_for(context["worldId"])
    try:
        current = store.get(note_id)["revision"]
    except ValueError:
        current = 0
    patch = {"title": title[:256], "text": _json(facts), "tags": ["auto", kind, outcome], "status": "open" if error else "done",
             "attachments": [{"kind": "location", "dimension": dim, "pos": pos, "label": f"{kind} {outcome}"}]}
    return store.write(note_id, current, f"auto-{uuid.uuid4()}", patch)["note"]


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

def _receipt(done):
    """What a write answers with: the receipt, not the note again."""
    note = done["note"]
    return {**{k: v for k, v in done.items() if k != "note"}, "id": note["id"], "revision": note["revision"], "size": len(note["text"]), "attachments": note["attachments"]}


@tool(lane="read", coverage=["memory"])
def mb_notes(method: str = "search", params: dict | None = None) -> Any:
    """Durable world notes: context, status, capture, search, get, history, resolve.

    Notes also surface on their own (under "notes", as id, title and updated: get reads one)
    when you arrive somewhere, observe a block/entity that has one, enter an annotated
    region, or call mb_status.
    capture: {kind:block,pos:[x,y,z]}, {kind:entity,entityId:observedId} or uuid,
    {kind:location,pos?:[x,y,z]}, {kind:region,min:[x,y,z],max:[x,y,z]},
    {kind:item,item:"modid:name" or "modid:name:meta"} for an item TYPE (there is no
    per-stack identity), {kind:topic,topic:"machine:boiler"} for anything that is not a
    place: a machine kind, a mod, a quest, a technique, a wiki lesson. Item notes surface
    when that item shows up in mb_inventory, mb_item_info or mb_recipes; topic notes are
    found with search {subject:"machine:boiler"} (subject also takes a list). Returns
    worldId and attachment for mb_note_write. Entity UUIDs must come from the server;
    use obs.entities to discover transient IDs. Captures do not save notes.
    search: {query,regex:false,case:false,context:1,since,before,author:me|auto|all,
    tags:[all-required-tags],status:open|done|archived|all,kind,near:[x,y,z]|player,
    radius:32,region:{min,max},entity_uuid,subject,dimension,limit:20,max_chars:6000,
    cursor,detail:summary|full}. Every filter given must hold.
    query works like grep, not like a search engine: it is one piece of text taken
    literally, spaces included (a regular expression with regex:true), ignoring case
    unless case:true, looked for in each line of the text and in the id, title and
    tags. Words are not split and nothing is ranked: "copper vein" matches only where
    those two words stand together.
    since/before select by when a note last changed: an age ("90m", "5h", "3d": that
    long ago, in real time) or a UTC time ("2026-10-04T12:00").
    author: notes tagged "auto" are journaled by the harness (work outcomes) and never
    surface; yours are anything else. me (the default) leaves the auto notes out, auto
    returns only them, all returns both; tags:["auto"] also returns them.
    Results come newest-changed first, each with its anchors, its age (since it last
    changed: 40m, 5h, 3d) and an excerpt: the lines that matched (the first 5;
    matchingLines counts them all) with context lines around each (0..5), as grep -n
    prints them ("12:" a matching line, "13-" a neighbour, long lines cut around the
    match), or the first 280 characters when there is no query or it matched only the
    id, title or tags. get reads the full note.
    A page ends at limit notes (at most 100) or when the notes would pass max_chars
    characters of JSON (1000..20000), whichever comes first; a note that is larger
    alone is listed with "omitted" in place of its content. Follow nextCursor unchanged
    with the same filters for the next page; pages retain a consistent snapshot.
    Defaults to current dimension and excludes archived notes; dimension:null searches
    all dimensions (spatial searches require one). Entity proximity uses lastSeen.
    Changed 2026-10: results used to come in id order with auto notes among them
    (they surfaced too), a query was split into words that could match anywhere in a
    note, and a page had no character limit.
    get: {id} returns the note with createdAt, updatedAt, age and now (UTC); an entry
    added with mb_note_append starts with its own time.
    history: {id,before_revision?,limit:20}. resolve: {id} inspects currently loaded
    attachments without overwriting notes; absence never proves destruction.
    Block identity checks cannot detect replacement by an identical block.
    Notes are annotations, not protection rules or verified facts. Keep useful plans,
    machine quirks, adapter source references and construction reservations here.
    """
    if method == "search":
        params = dict(params or {}, max_chars=(params or {}).get("max_chars") or SEARCH_CHARS)
    return read_notes(kernel(), method, params)


@tool(coverage=["memory"])
def mb_note_write(world_id: str, id: str, expected_revision: int,
                  operation_id: str, patch: dict) -> Any:
    """Create/update a durable note with history and a retry-safe receipt.

    Use worldId from mb_notes context/capture. Create with expected_revision:0 and
    patch:{title,text,attachments:[capturedAttachment,...],tags?:[],status?:open,data?:{}}.
    Update with the observed revision and only changed fields. Text/arrays replace
    those fields; mb_note_append adds to the text without resending it. data holds
    model-defined JSON, e.g. adapter source paths.
    Use a distinct operation_id for each edit; after a timeout retry
    exactly the same arguments and operation_id. A stale revision fails without edits.
    Archive with patch:{status:archived}; restore with status:open. No destructive delete;
    history preserves prior content, which can be copied into a new guarded revision.
    Region annotations do not prevent normal progression or automatically protect blocks.
    Stored under MODBENCH_NOTES_DIR (default .state/notes), across JVM/MCP restarts.
    Returns the receipt, not the note again: saved, replayed, sequence, id, revision,
    size (characters of text) and the attachments as stored.
    """
    return _receipt(write_note(kernel(), world_id, id, expected_revision, operation_id, patch))


@tool(coverage=["memory"])
def mb_note_append(id: str, text: str, operation_id: str) -> Any:
    """Add a dated entry to the end of an existing note without reading or resending its text.

    The entry becomes a new last line of the note's text: "[2026-10-04T12:00] " (when it
    was written, UTC) and then your text, so a reader of the note sees how old each
    entry is (mb_notes get and search return now to compare with). It needs no world
    id and no revision: entries only add, and the note's revision goes up by one.
    Use a distinct operation_id for each entry; after a timeout retry exactly the same
    arguments and operation_id, and the entry is not added twice. Fails when the note
    does not exist in this world (create it with mb_note_write) or its text would pass
    32,768 characters. Returns the same receipt as mb_note_write.
    """
    return _receipt(store_for(kernel().call("memory.context")["worldId"]).append(id, operation_id, text))


# ---- goal stack ----

GOAL_ID, GOAL_FIELDS, STALE_TICKS = "goal-stack", ("chapter", "quest", "subgoal", "serves"), 24000


def goal(kernel, changes=None):
    """The pinned goal note plus a stall signal: game ticks since the sub-goal or the inventory last changed."""
    context = kernel.call("memory.context", timeout=5)
    store = store_for(context["worldId"])
    try:
        note = store.get(GOAL_ID)
    except ValueError:
        note = {"revision": 0, "data": {}}
    data = {} if note.get("status") == "archived" else dict(note["data"])  # archiving the note clears the stack
    changes = {k: _int(v, k, 0, 100) if k == "progress" else _text(v, k, 512, empty=True).strip() for k, v in (changes or {}).items() if v is not None}
    if changes:
        if "progress" not in changes and changes.get("quest", data.get("quest")) != data.get("quest"): data.pop("progress", None)  # the estimate was for the quest before
        data.update(changes, setAt=datetime.now(timezone.utc).isoformat())
        text = " / ".join(f"{k}: {data[k]}" for k in GOAL_FIELDS if data.get(k))
        store.write(GOAL_ID, note["revision"], f"goal-{uuid.uuid4()}", {"title": "Goal stack", "text": text, "data": data, "tags": ["goal"], "status": "open",
                    "attachments": [{"kind": "topic", "topic": "goal"}]})
    if not data:
        return {"unset": "no goal stack yet: call mb_goal(chapter=..., quest=..., subgoal=..., serves=...)"}
    watch = state.setdefault("goal", {})
    ticks = kernel.call("time.status", timeout=5).get("state", {}).get("simulationTicks", 0)
    mark = hashlib.sha256(_json([data.get("subgoal"), kernel.call("obs.inventory", detail="counts", timeout=5)]).encode()).hexdigest()
    if mark != watch.get("mark"):
        watch.update(mark=mark, quiet=0)
    else:
        watch["quiet"] = watch.get("quiet", 0) + max(0, ticks - watch.get("ticks", ticks))  # the counter restarts with the server
    watch["ticks"] = ticks
    out = {**{k: data.get(k, "") for k in GOAL_FIELDS}, "setAt": data.get("setAt"), "quietGameMinutes": round(watch["quiet"] / 1200, 1)}
    if "progress" in data: out["progress"] = data["progress"]
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


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Back up durable world notes using SQLite's consistent backup API")
    parser.add_argument("world_id")
    parser.add_argument("output")
    args = parser.parse_args()
    print(store_for(args.world_id).backup(args.output))
