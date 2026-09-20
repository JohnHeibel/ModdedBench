# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Durable world annotations. SQLite transactions and history are independent of the game JVM.

Only capture/resolve contact Minecraft. Stored observations are never silently refreshed.
Set MODBENCH_NOTES_DIR to a persistent volume when packaging or moving the harness.
"""
from __future__ import annotations

from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import uuid


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
    else:
        raise ValueError("attachment kind must be block, entity, location or region")
    if set(a) - keys:
        raise ValueError(f"unknown attachment fields: {sorted(set(a)-keys)}")
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
        _int(expected_revision, "expected_revision")
        if not isinstance(patch, dict) or not patch or set(patch) - {"title", "text", "tags", "status", "attachments", "data"}:
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
            if actual != expected_revision:
                raise ValueError(f"note revision conflict: expected {expected_revision}, current {actual}; read before editing")
            now = datetime.now(timezone.utc).isoformat()
            note = json.loads(row[1]) if row else dict(id=id, worldId=self.world_id, createdAt=now, tags=[], status="open", data={})
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

    def history(self, id, before_revision=None, limit=20):
        _int(limit, "limit", 1, 100)
        before = _int(before_revision, "before_revision", 1) if before_revision is not None else 2**63-1
        with closing(self.connect()) as db:
            rows = db.execute("SELECT snapshot FROM history WHERE id=? AND revision<? ORDER BY revision DESC LIMIT ?", (_text(id, "id", 96), before, limit+1)).fetchall()
        notes = [json.loads(row[0]) for row in rows[:limit]]
        return {"revisions": notes, "nextBeforeRevision": notes[-1]["revision"] if len(rows) > limit else None}

    def search(self, query="", tags=None, status=None, kind=None, dimension=None, near=None, radius=32, region=None, entity_uuid=None, cursor=None, limit=20, detail="summary"):
        _int(limit, "limit", 1, 100)
        if detail not in ("summary", "full"):
            raise ValueError("detail must be summary or full")
        terms = _text(query, "query", 512, empty=True).casefold().split()
        if tags is not None and (not isinstance(tags, list) or len(tags) > 32):
            raise ValueError("tags must be a list of at most 32 strings")
        tags = {_text(t, "tag", 96).strip().casefold() for t in tags or []}
        if status not in (None, "open", "done", "archived", "all") or kind not in (None, "block", "entity", "location", "region"):
            raise ValueError("invalid status or attachment kind")
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
        fingerprint = hashlib.sha256(_json([self.world_id, terms, sorted(tags), status, kind, dimension, near, radius, region, entity_uuid]).encode()).hexdigest()
        after, ceiling = "", None
        if cursor is not None:
            if not isinstance(cursor, dict) or cursor.get("query") != fingerprint:
                raise ValueError("cursor belongs to another query/world")
            after = _text(cursor.get("after"), "cursor.after", 96)
            ceiling = _int(cursor.get("sequence"), "cursor.sequence")
        def matches(a):
            if dimension is not None and a["dimension"] != dimension or kind is not None and a["kind"] != kind:
                return False
            if entity_uuid is not None and (a["kind"] != "entity" or a["uuid"] != entity_uuid):
                return False
            lo = a.get("min", a.get("pos", a.get("lastSeen")))
            hi = a.get("max", lo)
            if near is not None and sum(max(lo[i]-near[i], 0, near[i]-hi[i])**2 for i in range(3)) > radius**2:
                return False
            return region is None or all(lo[i] <= region["max"][i] and hi[i] >= region["min"][i] for i in range(3))
        with closing(self.connect()) as db:
            if ceiling is None:
                ceiling = db.execute("SELECT coalesce(max(seq),0) FROM history").fetchone()[0]
            rows = db.execute("SELECT h.snapshot FROM notes n JOIN history h ON h.seq=(SELECT max(seq) FROM history WHERE id=n.id AND seq<=?) WHERE n.id>? ORDER BY n.id", (ceiling, after))
            found = []
            for row in rows:
                note = json.loads(row[0])
                if status not in (None, "all") and note["status"] != status or status is None and note["status"] == "archived":
                    continue
                haystack = " ".join([note["id"], note["title"], note["text"], *note["tags"]]).casefold()
                if not tags.issubset(note["tags"]) or not all(term in haystack for term in terms) or not any(matches(a) for a in note["attachments"]):
                    continue
                found.append(note)
                if len(found) > limit:
                    break
        more = len(found) > limit
        results=found[:limit]
        if detail=="summary":
            results=[dict({k:v for k,v in n.items() if k not in {"text","data"}},excerpt=n["text"][:280],bodyCharacters=len(n["text"])) for n in results]
        return {"worldId": self.world_id, "notes": results, "detail": detail, "sequence": ceiling,
                "nextCursor": {"after": found[limit-1]["id"], "sequence": ceiling, "query": fingerprint} if more else None,
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


def store_for(world_id):
    world_id = str(uuid.UUID(world_id))
    directory = Path(os.environ.get("MODBENCH_NOTES_DIR", Path(__file__).resolve().parents[2]/"gtnh/.state/notes"))
    return NotesStore(directory / f"{world_id}.sqlite3", world_id)


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
    else:
        raise ValueError("kind must be block, entity, location or region")
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
        return store.get(**params)
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
        from kernel import BridgeError
        for a in note["attachments"]:
            result = dict(attachment=a, status="annotation")
            if a["dimension"] != context["dimension"]:
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


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Back up durable world notes using SQLite's consistent backup API")
    parser.add_argument("world_id")
    parser.add_argument("output")
    args = parser.parse_args()
    print(store_for(args.world_id).backup(args.output))
