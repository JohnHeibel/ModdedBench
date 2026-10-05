# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""World notes as files: what the harness writes, what it reads back from headers, and what it survives."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

MCP = Path(__file__).resolve().parents[1]/"mcp"
sys.path.insert(0, str(MCP))
import mbtool  # noqa: E402,F401
from mbtools_gtnh import notes
from kernel import BridgeError


def clock(hour, minute=0, day=4):
    """Pin the notes' wall clock (UTC) for a block of writes."""
    return patch.object(notes, "_now", lambda: datetime(2026, 10, day, hour, minute, tzinfo=timezone.utc))


def ids(found):
    return [n["id"] for n in found]


class Game:
    """Fake kernel: memory.context/obs.player/obs.block/work receipts with a movable player."""
    connected = True
    def __init__(self, world, pos=(10, 64, 20)):
        self.world, self.pos, self.calls, self.receipt = world, list(pos), [], {"state": "succeeded"}
    def call(self, method, **params):
        self.calls.append(method)
        if method == "memory.context": return dict(worldId=self.world, dimension=0, pos=list(self.pos))
        if method == "obs.player": return dict(pos=list(self.pos), dimension=0, health=20)
        if method == "obs.block": return dict(id="minecraft:stone", meta=0, pos=[params["x"], params["y"], params["z"]])
        if method.startswith("nav."): return dict(self.receipt)
        if method == "time.status": return dict(state=dict(simulationTicks=self.ticks))
        if method == "obs.inventory": return dict(self.inventory)
        raise AssertionError(method)
    ticks, inventory = 0, {"counts": {}}
    def close(self): pass


class Notes(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.env = patch.dict("os.environ", MODBENCH_NOTES_DIR=self.tmp.name); self.env.start(); self.addCleanup(self.env.stop)
        mbtool.state.pop("notes", None); self.addCleanup(mbtool.state.pop, "notes", None)
        self.world = str(uuid.uuid4()); self.game = Game(self.world); self.root = Path(self.tmp.name) / self.world
        self.addCleanup(mbtool.state.pop, "kernel", None)
        self.written = 0

    def put(self, id, anchor, **extra):
        """A note, each one a second newer than the one before."""
        note = notes.save(self.world, id, f"note {id}", [anchor], **{"text": "details " * 3 + id, "tags": ["plan"], **extra})
        self.written += 1; os.utime(note["file"], (1.76e9 + self.written,) * 2)
        return note

    def at(self, x, y, z, dimension=0): return dict(kind="location", dimension=dimension, pos=[x, y, z])

    def find(self, **filters): return ids(notes.find(self.world, **filters))


class NoteFilesTests(Notes):
    def test_a_note_is_one_file_with_a_header_a_blank_line_and_the_text_and_its_data_beside_it(self):
        drawing = {"origin": [0, 60, 0], "layers": [["##", "#."]], "legend": {"#": {"id": "pack:wall"}}}
        with clock(9):
            note = notes.save(self.world, "workshop", "LV workshop: roof first", [dict(kind="region", dimension=0, min=[0, 60, 0], max=[20, 80, 20]), dict(kind="topic", topic="Machine:Boiler")],
                              "line one\n\nline three", tags=["Plan", "base"], data={"drawing": drawing})
        self.assertEqual(Path(note["file"]), self.root / "workshop.md")
        self.assertEqual((self.root / "workshop.md").read_bytes().decode(),
                         "title: LV workshop: roof first\ntags: base, plan\nstatus: open\ncreated: 2026-10-04T09:00:00+00:00\n"
                         'anchor: {"dimension":0,"kind":"region","max":[20,80,20],"min":[0,60,0]}\nanchor: {"kind":"topic","topic":"machine:boiler"}\n\nline one\n\nline three\n')
        self.assertEqual((note["id"], note["title"], note["tags"], note["status"], note["auto"]), ("workshop", "LV workshop: roof first", ["base", "plan"], "open", False))
        self.assertEqual(notes.get(self.world, "workshop"), note)
        self.assertEqual(notes.data(notes.find(self.world, 0, region={"min": [5, 61, 5], "max": [6, 62, 6]})[0])["drawing"], drawing)  # as mb_view reads a plan back
        self.assertEqual(notes.data(self.put("bare", self.at(1, 64, 1))), {}); self.assertFalse((self.root / "bare.json").exists())
        (self.root / "workshop.json").write_text("{not json")
        with self.assertRaisesRegex(ValueError, "workshop.json is not readable JSON"): notes.data(note)
        with self.assertRaisesRegex(ValueError, "note workshop exists: edit"): notes.save(self.world, "workshop", "again", [self.at(1, 1, 1)], new=True)
        for bad in ("", "../up", "a/b", "a b", ".hidden", "x" * 97, 5):
            with self.assertRaisesRegex(ValueError, "id must be"): notes.save(self.world, bad, "t", [self.at(1, 1, 1)])
        for anchors in ([], [dict(kind="block", dimension=0, pos=[1.2, 64, 3])], [dict(kind="entity", dimension=0, uuid=str(uuid.uuid4()), uuidScope="client_session", lastSeen=[1, 64, 3])],
                        [dict(kind="item", item="a:b", pos=[0, 0, 0])]):
            with self.assertRaises(ValueError): notes.save(self.world, "refused", "t", anchors)
        self.assertEqual(sorted(p.name for p in self.root.glob("*.md")), ["bare.md", "workshop.md"])  # a refused write leaves nothing
        with self.assertRaisesRegex(ValueError, "not found"): notes.get(self.world, "../workshop")
        self.assertEqual(notes.save(self.world, "big", "t", [self.at(1, 1, 1)], "x" * 100000)["id"], "big")  # no size limit on the text

    def test_find_by_place_region_entity_subject_and_dimension(self):
        entity = str(uuid.uuid4())
        self.put("base", dict(kind="region", dimension=0, min=[0, 60, 0], max=[20, 80, 20]))
        self.put("pig", dict(kind="entity", dimension=0, uuid=entity, uuidScope="server", lastSeen=[30, 64, 20]))
        self.put("nether", self.at(10, 64, 20, dimension=-1))
        notes.save(self.world, "cross", "both", [self.at(500, 64, 500), self.at(10, 64, 20, dimension=-1)])
        self.put("ore", dict(kind="item", item="gregtech:gt.blockores:1823"))
        self.put("boiler", dict(kind="topic", topic="Machine:Boiler"))
        self.put("old", self.at(10, 64, 20), status="archived")
        self.assertEqual(self.find(dimension=0, near=[10, 64, 20], radius=0), ["base"])
        self.assertEqual(self.find(dimension=0, near=[28, 64, 20], radius=8), ["pig", "base"])  # newest-changed first; a region is near from its edge
        self.assertEqual(self.find(dimension=0, region=dict(min=[19, 79, 19], max=[50, 90, 50])), ["base"])
        self.assertEqual(self.find(dimension=0, region=dict(min=[19, 60, 19], max=[50, 90, 50])), ["pig", "base"])
        self.assertEqual(self.find(dimension=0, region=dict(min=[19, 60, 19], max=[50, 90, 50]), kind="region"), ["base"])
        self.assertEqual(sorted(self.find(dimension=-1, near=[10, 64, 20], radius=1)), ["cross", "nether"])
        self.assertEqual(self.find(entity_uuid=entity), ["pig"])
        self.assertEqual((self.find(subject="machine:boiler"), self.find(subject=["MACHINE:BOILER", "gregtech:gt.blockores:1823"])), (["boiler"], ["boiler", "ore"]))
        self.assertEqual(sorted(self.find(dimension=-1)), ["boiler", "cross", "nether", "ore"])  # a dimension never hides notes that have no place
        self.assertEqual((self.find(status="archived"), "old" in self.find(status="all"), "old" in self.find()), (["old"], True, False))
        with self.assertRaisesRegex(ValueError, "requires a dimension"): self.find(near=[10, 64, 20])
        for bad in (dict(kind="chest"), dict(status="gone"), dict(dimension=0, near=[1, 2]), dict(dimension=0, near=[1, 2, 3], radius=-1), dict(entity_uuid="pig")):
            with self.assertRaises(ValueError): self.find(**bad)
        self.assertEqual(notes.find(str(uuid.uuid4())), [])  # a world without notes
        self.assertEqual(sorted(p.name for p in Path(self.tmp.name).iterdir() if p.is_dir()), [self.world])  # and looking makes it no folder

    def test_the_header_is_read_again_when_the_file_changes_by_hand(self):
        path = Path(self.put("camp", self.at(10, 64, 20))["file"])
        edited = path.read_text().replace("title: note camp", "title: Lake camp").replace("status: open", "status: done").replace("tags: plan", "tags: Farm ,  water,")
        path.write_text(edited.replace("\n\n", '\nanchor: {"kind":"topic","topic":"farm:lake"}\nmine: a line of my own\n\n', 1) + "more text\n", newline="\r\n")  # an editor that writes CRLF
        note = notes.get(self.world, "camp")
        self.assertEqual((note["title"], note["status"], note["tags"], [a["kind"] for a in note["anchors"]], "unreadable" in note), ("Lake camp", "done", ["farm", "water"], ["location", "topic"], False))
        self.assertEqual((self.find(subject="farm:lake"), self.find(dimension=0, near=[10, 64, 20], radius=1)), (["camp"], ["camp"]))

    def test_a_mangled_header_costs_the_note_its_anchors_and_is_named_never_raised(self):
        good = Path(self.put("good", self.at(10, 64, 20))["file"])
        cases = {"nojson": lambda t: t.replace('anchor: {', 'anchor: '), "badpos": lambda t: t.replace("[12,64,20]", "[12,64]"), "notitle": lambda t: t.split("\n", 1)[1],
                 "prose": lambda t: "My boiler notes\nwritten by hand\n", "status": lambda t: t.replace("status: open", "status: finished"), "binary": None}
        for name, mangle in cases.items():
            path = Path(self.put(name, self.at(12, 64, 20))["file"])
            path.write_bytes(b"\xff\xfe\x00title") if mangle is None else path.write_text(mangle(path.read_text()))
        self.assertEqual(self.find(dimension=0, near=[10, 64, 20], radius=8), ["good"])
        self.assertEqual(ids(notes.surface(self.game, reason="session", radius=32)), ["good"])
        listed = notes.where(self.game)
        self.assertEqual(listed["notesFolder"], str(self.root))
        self.assertEqual(sorted(Path(n["file"]).name for n in listed["notesUnreadable"]), sorted(f"{name}.md" for name in cases))
        why = {Path(n["file"]).stem: n["why"] for n in listed["notesUnreadable"]}
        self.assertIn("title:", why["notitle"]); self.assertIn("status must be", why["status"]); self.assertIn("header line is not", why["prose"]); self.assertIn("position", why["badpos"])
        mbtool.state["kernel"] = self.game
        self.assertEqual(notes.mb_notes("find", {"near": "player"})["notesUnreadable"], listed["notesUnreadable"])
        self.assertTrue(notes.mb_note_append("nojson", "still writable")["file"].endswith("nojson.md"))
        bad = self.root / "badpos.md"; bad.write_text(bad.read_text().replace("[12,64]", "[12,64,20]"))  # mended by hand: the anchors are back
        self.assertEqual(sorted(self.find(dimension=0, near=[10, 64, 20], radius=8)), ["badpos", "good"])
        good.unlink(); self.assertEqual(self.find(dimension=0, near=[10, 64, 20], radius=8), ["badpos"])  # a note is deleted by deleting its file

    def test_append_adds_a_dated_last_line_and_a_retry_of_it_adds_nothing(self):
        with clock(9): path = Path(self.put("stock", self.at(10, 64, 20), text="Main chest wall.")["file"])
        head = path.read_text().split("\n\n")[0]
        with clock(12, 5): done = notes.append(self.world, "stock", " 40 copper ingots in chest 3 \n")
        self.assertEqual(done, {"file": str(path), "bytes": path.stat().st_size})
        self.assertEqual(path.read_text(), head + "\n\nMain chest wall.\n[2026-10-04T12:05] 40 copper ingots in chest 3\n")
        with clock(13): again = notes.append(self.world, "stock", "40 copper ingots in chest 3")   # the retry of a lost reply
        self.assertEqual((again.get("replayed"), path.read_text().count("40 copper")), (True, 1))
        with clock(13): notes.append(self.world, "stock", "smelted them"); notes.append(self.world, "stock", "40 copper ingots in chest 3")  # no longer the last entry: said again on purpose
        self.assertEqual(path.read_text().split("\n")[-4:], ["[2026-10-04T12:05] 40 copper ingots in chest 3", "[2026-10-04T13:00] smelted them", "[2026-10-04T13:00] 40 copper ingots in chest 3", ""])
        with self.assertRaisesRegex(ValueError, "not found"): notes.append(self.world, "missing", "x")
        for bad in ("", "  ", 5):
            with self.assertRaises(ValueError): notes.append(self.world, "stock", bad)
        path.write_text(head + "\n\nrewritten by hand, no newline at the end")
        with clock(14): notes.append(self.world, "stock", "next")
        self.assertEqual(path.read_text(), head + "\n\nrewritten by hand, no newline at the end\n[2026-10-04T14:00] next\n")
        path.write_text(head + "\n")                                               # a header and nothing after it
        with clock(15): notes.append(self.world, "stock", "first")
        self.assertEqual(path.read_text(), head + "\n\n[2026-10-04T15:00] first\n")
        self.assertEqual(notes.get(self.world, "stock")["anchors"], [self.at(10, 64, 20)])

    def test_two_appenders_at_once_lose_no_entry_in_one_process_or_across_two(self):
        path = Path(self.put("log", self.at(10, 64, 20), text="start")["file"])
        with clock(14), ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda n: notes.append(self.world, "log", f"thread entry {n}"), range(16)))
        code = "import sys,mbtool;from mbtools_gtnh import notes\nfor n in range(15): notes.append(sys.argv[1],'log',f'process {sys.argv[2]} entry {n}')"
        tasks = [subprocess.Popen([sys.executable, "-c", code, self.world, who], cwd=MCP, env={**os.environ, "PYTHONPATH": str(MCP)}) for who in "ab"]  # a background task and the main thread
        self.assertEqual([t.wait(120) for t in tasks], [0, 0])
        lines = path.read_text().split("\n\n", 1)[1].splitlines()
        self.assertEqual(sorted(line[19:] for line in lines[1:]), sorted([f"thread entry {n}" for n in range(16)] + [f"process {w} entry {n}" for w in "ab" for n in range(15)]))
        self.assertTrue(all(line[0] == "[" and line[17:19] == "] " for line in lines[1:]), lines)  # whole lines, none inside another

    def test_an_old_database_is_exported_once_and_left_as_it_was(self):
        old = Path(self.tmp.name) / f"{self.world}.sqlite3"
        rows = [dict(id="chapter-steam", title="Steam chapter", text="Firstfullquota256 miningjob\n\n  indented, and a trailing space \n[2026-10-04T12:00] an entry", tags=["chapter", "plan"], status="open", data={},
                     attachments=[dict(kind="topic", topic="chapter:steam"), dict(kind="block", dimension=0, pos=[1, 64, 2], observed=dict(id="a:b", meta=3), label="the \"boiler\"\nwall", observedAt="2026-10-04T10:00:00+00:00")]),
                dict(id="room", title="Smelting room", text="", tags=[], status="done", data={"drawing": {"origin": [0, 60, 0], "layers": [["#"]]}}, attachments=[dict(kind="region", dimension=0, min=[0, 60, 0], max=[4, 64, 4])]),
                dict(id="auto-mine-0-0-64-0", title="mine done at 1,64,2: gained", text='{"state":"succeeded"}', tags=["auto", "done", "mine"], status="done", data={}, attachments=[dict(kind="location", dimension=0, pos=[1, 64, 2], label="mine done")])]
        with closing(sqlite3.connect(old)) as db, db:
            db.execute("CREATE TABLE notes (id TEXT PRIMARY KEY, revision INTEGER NOT NULL, snapshot TEXT NOT NULL)")
            for n, row in enumerate(rows):
                db.execute("INSERT INTO notes VALUES (?,?,?)", (row["id"], 3, json.dumps(dict(row, worldId=self.world, revision=3, createdAt=f"2026-10-0{n + 1}T08:00:00.123456+00:00", updatedAt=f"2026-10-0{n + 2}T09:30:00.500000+00:00"))))
        before = old.read_bytes()
        found = {n["id"]: n for n in notes.find(self.world, auto=None, status="all", dimension=0)}   # the first look at the world exports it
        self.assertEqual(sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*") if p.is_file() and ".git" not in p.parts),
                         ["auto/auto-mine-0-0-64-0.md", "chapter-steam.md", "room.json", "room.md"])
        for n, row in enumerate(rows):
            note = found[row["id"]]; body = Path(note["file"]).read_bytes().split(b"\n\n", 1)[1]
            self.assertEqual(body, (row["text"] + "\n").encode())                              # the text byte for byte, then one newline
            self.assertEqual((note["title"], note["tags"], note["status"], note["anchors"], notes.data(note), note["auto"]), (row["title"], row["tags"], row["status"], row["attachments"], row["data"], n == 2))
            self.assertEqual((note["created"], note["updated"]), (f"2026-10-0{n + 1}T08:00:00.123456+00:00", f"2026-10-0{n + 2}T09:30"))  # when it last changed is the file's time
        self.assertEqual(old.read_bytes(), before)
        (self.root / "room.md").unlink()
        self.assertNotIn("room", ids(notes.scan(self.world)))                                  # the folder is the notes now: nothing comes back from the database

    @unittest.skipUnless(shutil.which("git"), "the history is git's")
    def test_harness_writes_commit_the_folder_and_the_models_own_edits_ride_along(self):
        path = Path(self.put("camp", self.at(10, 64, 20))["file"])
        path.write_text(path.read_text() + "a line added by hand\n")
        notes.append(self.world, "camp", "an entry")
        git = lambda *a: subprocess.run(["git", "-C", str(self.root), *a], capture_output=True, text=True, check=True).stdout
        self.assertEqual(git("log", "--format=%s").splitlines(), ["camp: entry", "camp: note camp"])
        self.assertIn("a line added by hand", git("show", "HEAD:camp.md")); self.assertEqual(git("status", "--porcelain"), "")
        with patch.object(notes.subprocess, "run", side_effect=FileNotFoundError("git")):     # no git: the write is the write
            self.assertEqual(self.put("other", self.at(1, 64, 1))["id"], "other")


class NotesSurfacingTests(Notes):
    def test_surface_is_bounded_nearest_first_deduplicated_and_reset_by_movement(self):
        for i in range(7): self.put(f"n{i}", self.at(10 + 2 * i, 64, 20))
        self.put("far", self.at(500, 64, 500)); self.put("nether", self.at(10, 64, 20, dimension=-1)); self.put("gone", self.at(10, 64, 20), status="archived")
        found = notes.surface(self.game, reason="session", radius=32)
        self.assertEqual(ids(found), ["n0", "n1", "n2", "n3", "n4"])
        self.assertRegex(found[0].pop("updated"), r"^20\d\d-\d\d-\d\dT\d\d:\d\d$")  # surfaced notes carry their age, to the minute
        self.assertEqual(found[0], {"id": "n0", "title": "note n0"})  # a note rides along by name: its file has the rest
        self.assertEqual(ids(notes.surface(self.game, reason="session", radius=32)), ["n5", "n6"])   # the rest, once
        self.assertEqual(notes.surface(self.game, reason="session", radius=32), [])                  # nothing new
        with open(notes.get(self.world, "n3")["file"], "a") as f: f.write("edited by hand\n")
        self.assertEqual(ids(notes.surface(self.game, reason="session", radius=32)), ["n3"])         # a note that changed is new again
        self.game.pos = [12, 64, 20]
        self.assertEqual(notes.surface(self.game, position=[12, 64, 20], dimension=0, reason="position"), [])       # small move: still shown
        self.game.pos = [120, 64, 20]
        self.assertEqual(notes.surface(self.game, position=[120, 64, 20], dimension=0, radius=200, reason="position")[0]["id"], "n6")
        self.assertEqual(notes.surface(self.game, reason="session", radius=32), [])                                 # far from every note now
        self.assertEqual(notes.surface(Game(str(uuid.uuid4())), reason="session"), [])                              # world without notes: no folder made
        self.assertEqual(sorted(p.name for p in Path(self.tmp.name).iterdir() if p.is_dir()), [self.world])

    def test_position_gate_skips_repeated_lookups_and_never_raises(self):
        self.put("here", self.at(10, 64, 20))
        self.assertEqual(len(notes.surface(self.game, position=[10, 64, 20], dimension=0, reason="position")), 1)
        before = len(self.game.calls)
        self.assertEqual(notes.surface(self.game, position=[11, 64, 20], dimension=0, reason="position"), [])
        self.assertEqual(len(self.game.calls), before)                                   # gated: no bridge call at all
        with patch.object(notes, "GATE_S", 0):
            self.assertEqual(notes.surface(self.game, position=[11, 64, 20], dimension=0, reason="position"), [])
            self.assertGreater(len(self.game.calls), before)                             # looked up, already shown
        class Broken:
            def call(self, method, **params): raise ConnectionError("no bridge")
        self.assertEqual(notes.surface(Broken(), reason="session"), [])

    def test_block_entity_and_arrival_transitions_attach_notes_only_when_present(self):
        entity = str(uuid.uuid4())
        self.put("anchor", dict(kind="block", dimension=0, pos=[3, 65, 3], observed=dict(id="minecraft:stone", meta=0)))
        self.put("room", dict(kind="region", dimension=0, min=[0, 60, 0], max=[5, 70, 5]))
        self.put("pig", dict(kind="entity", dimension=0, uuid=entity, uuidScope="server", lastSeen=[30, 64, 20]))
        mbtool.state["kernel"] = self.game
        seen = notes.after("obs.block", {"x": 3, "y": 65, "z": 3}, {"id": "minecraft:stone", "meta": 0, "pos": [3, 65, 3]})
        self.assertEqual(ids(seen["notes"]), ["room", "anchor"])  # equally near: the newer note first
        self.assertNotIn("notes", notes.after("obs.block", {"x": 40, "y": 65, "z": 40}, {"id": "minecraft:stone", "pos": [40, 65, 40]}))
        found = notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "server", "pos": [30, 64, 20]})
        self.assertEqual(ids(found["notes"]), ["pig"])
        self.assertNotIn("notes", notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "client_session"}))
        self.assertEqual(notes.after("obs.player", {}, "not an object"), "not an object")
        self.put("camp", self.at(150, 64, 150)); self.game.pos = [200, 64, 200]
        arrived = notes.after("nav.goto", {}, {"state": "succeeded", "arrival": {"pos": [152, 64, 150]}})
        self.assertEqual(ids(arrived["notes"]), ["camp"])
        self.assertNotIn("notes", notes.after("nav.goto", {}, {"state": "succeeded", "arrival": {"pos": [152, 64, 150]}}))  # shown already

    def test_auto_journal_goes_to_its_own_folder_keyed_by_location_and_records_failures(self):
        mbtool.state["kernel"] = self.game
        done = notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [3, 5, 7], "blocksPlaced": 10, "ticks": 40})
        self.assertEqual((done["id"], done["tags"], done["status"], done["auto"]), ("auto-build-0-0-4-4", ["auto", "build", "done"], "done", True))
        self.assertEqual(Path(done["file"]), self.root / "auto" / "auto-build-0-0-4-4.md")
        self.assertEqual(done["anchors"][0]["pos"], [3, 5, 7]); self.assertIn("build done at 3,5,7", done["title"])
        text = lambda note: json.loads(Path(note["file"]).read_text().split("\n\n", 1)[1])
        self.assertEqual(text(done)["blocksPlaced"], 10)
        again = notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [2, 6, 5], "blocksPlaced": 3})
        self.assertEqual((again["id"], text(again)["blocksPlaced"], len(list((self.root / "auto").glob("*.md")))), ("auto-build-0-0-4-4", 3, 1))  # same 4-block cell: rewritten, not duplicated
        self.assertIsNone(notes.journal(self.game, "nav.build", {"state": "failed"}))                 # failures arrive as BridgeError
        self.assertIsNone(notes.journal(self.game, "nav.goto", {"state": "succeeded", "arrival": {"pos": [1, 1, 1]}}))
        self.assertIsNone(notes.journal(self.game, "obs.block", {"state": "succeeded"}))
        error = BridgeError("action_failed", "no path", "nav.route", {"error": {"receipt": {"state": "failed", "reason": "stuck", "goal": [9, 9, 9], "jobId": "j1"}}})
        failed = notes.journal(self.game, "nav.route", None, error=error)
        self.assertEqual((failed["id"], failed["status"], failed["tags"]), ("auto-route-0-8-8-8", "open", ["auto", "failed", "route"]))
        self.assertIn("stuck", failed["title"]); self.assertEqual(text(failed)["jobId"], "j1")
        self.assertIsNone(notes.journal(self.game, "nav.mine", None, error=BridgeError("cancelled", "stopped", "nav.mine", {})))
        # tracked() wires it together: the receipt is journaled, then the fresh auto note surfaces at the arrival position.
        self.game.receipt = {"state": "succeeded", "goal": [40, 64, 40], "blocksMined": 5}
        result = notes.tracked("nav.mine", 30, blocks=[{"id": "a:b"}])
        self.assertEqual(result["blocksMined"], 5); self.assertEqual(ids(result["notes"]), ["auto-mine-0-40-64-40"])
        self.assertEqual(result["endedAt"], [round(v, 1) for v in self.game.pos])  # where the job left you rides on its receipt
        self.game.receipt = {"state": "running"}
        self.assertNotIn("notes", notes.tracked("nav.mine", 30, blocks=[]))
        class Failing(Game):
            def call(self, method, **params):
                if method.startswith("nav."): raise error
                return super().call(method, **params)
        mbtool.state["kernel"] = Failing(self.world)
        with self.assertRaises(BridgeError): notes.tracked("nav.route", 30, name="x")
        self.assertEqual(list(self.root.glob("*.md")), [])                                       # nothing of the journal among the model's own notes
        self.put("mine", self.at(10, 64, 20))
        self.assertEqual((self.find(), sorted(self.find(auto=True)), len(self.find(auto=None))), (["mine"], ["auto-build-0-0-4-4", "auto-mine-0-40-64-40", "auto-route-0-8-8-8"], 4))
        self.assertEqual(sorted(ids(notes.surface(self.game, position=[8, 8, 8], dimension=0, radius=8, reason="arrival"))), ["auto-build-0-0-4-4", "auto-route-0-8-8-8"])  # they still surface

    def test_item_and_topic_notes_have_no_place_and_surface_by_subject(self):
        self.put("cassiterite", dict(kind="item", item="gregtech:gt.blockores:1823"))
        self.put("boiler", dict(kind="topic", topic="Machine:Boiler"))
        self.put("here", self.at(10, 64, 20))
        result = {"slots": [{"id": "gregtech:gt.blockores", "meta": 1823, "count": 3}, {"id": "minecraft:stick", "meta": 0}]}
        self.assertEqual(notes.item_subjects(result), ["gregtech:gt.blockores", "gregtech:gt.blockores:1823", "minecraft:stick", "minecraft:stick:0"])
        mbtool.state["kernel"] = self.game
        self.assertEqual(ids(notes.with_item_notes(result)["notes"]), ["cassiterite"])
        self.assertNotIn("notes", notes.with_item_notes(result))  # shown once

    def test_the_tools_create_with_captured_anchors_append_and_find(self):
        mbtool.state["kernel"] = self.game
        made = notes.mb_note_new("plan", "LV workshop", [{"kind": "block", "pos": [3, 65, 3]}, {"kind": "location"}, {"kind": "region", "min": [0, 60, 0], "max": [5, 70, 5]}, {"kind": "item", "item": "Minecraft:Chest"}],
                                 text="roof first", tags=["base"], data={"drawing": {"layers": []}})
        self.assertEqual(made["file"], str(self.root / "plan.md"))
        for a in made["anchors"]: self.assertRegex(a.pop("observedAt"), r"^20\d\d-")
        self.assertEqual(made["anchors"], [dict(kind="block", dimension=0, pos=[3, 65, 3], observed=dict(id="minecraft:stone", meta=0)), self.at(10, 64, 20),
                                           dict(kind="region", dimension=0, min=[0, 60, 0], max=[5, 70, 5]), dict(kind="item", item="minecraft:chest")])
        self.assertEqual(json.loads((self.root / "plan.json").read_text()), {"drawing": {"layers": []}})
        with self.assertRaisesRegex(ValueError, "exists: edit"): notes.mb_note_new("plan", "again", [{"kind": "location"}])
        for bad in ([{"pos": [1, 2, 3]}], [{"kind": "chest"}], []):
            with self.assertRaises(ValueError): notes.mb_note_new("other", "t", bad)
        self.assertTrue(notes.mb_note_append("plan", "walls done")["file"].endswith("plan.md"))
        self.assertRegex((self.root / "plan.md").read_text(), r"\n\nroof first\n\[20\d\d-\d\d-\d\dT\d\d:\d\d\] walls done\n$")
        with self.assertRaisesRegex(ValueError, "not found"): notes.mb_note_append("nope", "x")
        captured = notes.mb_notes("capture", {"kind": "topic", "topic": "Machine:Boiler"})
        self.assertEqual((captured["anchor"]["topic"], captured["line"].startswith('anchor: {"kind":"topic"')), ("machine:boiler", True))
        path = self.root / "plan.md"; path.write_text(path.read_text().replace("\n\n", "\n" + captured["line"] + "\n\n", 1))  # as the model adds it by hand
        notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [10, 64, 20]})
        listed = notes.mb_notes("find", {"subject": "machine:boiler"})
        self.assertEqual((listed["notesFolder"], ids(listed["notes"]), "notesUnreadable" in listed), (str(self.root), ["plan"], False))
        self.assertEqual({k: v for k, v in listed["notes"][0].items() if k not in ("updated", "anchors")}, {"id": "plan", "title": "LV workshop", "file": "plan.md", "tags": ["base"]})
        self.assertEqual(ids(notes.mb_notes("find", {"near": "player", "radius": 8})["notes"]), ["plan"])
        self.assertEqual([n["file"] for n in notes.mb_notes("find", {"near": "player", "radius": 8, "auto": True})["notes"]], ["auto/auto-build-0-8-64-20.md"])
        self.assertEqual(ids(notes.mb_notes()["notes"]), ["plan"]); self.assertEqual(notes.mb_notes("find", {"dimension": -1, "kind": "region"})["notes"], [])
        with self.assertRaisesRegex(ValueError, "find or capture"): notes.mb_notes("search", {"query": "x"})

    def test_goal_stack_persists_and_flags_a_stall_only_in_running_game_time(self):
        self.assertIn("unset", notes.goal(self.game))
        notes.goal(self.game, dict(chapter="Steam Age", quest="Bronze", subgoal="mine copper", serves=None))
        mbtool.state.pop("goal", None); self.addCleanup(mbtool.state.pop, "goal", None)
        first = notes.goal(self.game)
        self.assertEqual((first["quest"], first["subgoal"], first["quietGameMinutes"]), ("Bronze", "mine copper", 0))
        self.game.ticks = 30000
        self.assertIn("stale", notes.goal(self.game))
        self.game.inventory = {"counts": {"minecraft:cobblestone": 1}}
        self.assertNotIn("stale", notes.goal(self.game))
        self.game.ticks = 10  # server restarted: the counter went backwards and must not count
        self.assertEqual(notes.goal(self.game)["quietGameMinutes"], 0)
        self.assertEqual(notes.goal(self.game, dict(subgoal="smelt copper"))["chapter"], "Steam Age")
        self.assertEqual(json.loads((self.root / "goal-stack.json").read_text())["subgoal"], "smelt copper")
        self.assertTrue((self.root / "goal-stack.md").read_text().endswith("\n\nchapter: Steam Age / quest: Bronze / subgoal: smelt copper\n"))
        self.assertNotIn("progress", notes.goal(self.game))  # the estimate is optional
        self.assertEqual(notes.goal(self.game, dict(subgoal="alloy bronze", progress=60))["progress"], 60)
        self.assertEqual(notes.goal(self.game, dict(quest="Bronze", progress=40))["progress"], 40)  # it may go down
        self.assertEqual(notes.goal(self.game, dict(subgoal="hand it in"))["progress"], 40)
        self.assertNotIn("progress", notes.goal(self.game, dict(quest="Steel")))  # a new quest starts without one
        with self.assertRaisesRegex(ValueError, "progress must be an integer in 0..100"): notes.goal(self.game, dict(progress=140))
        path = self.root / "goal-stack.md"; path.write_text(path.read_text().replace("status: open", "status: archived"))
        self.assertIn("unset", notes.goal(self.game))
        self.assertEqual(notes.goal(self.game, dict(quest="Steel"))["chapter"], "")  # a cleared stack starts again

if __name__=="__main__":unittest.main()
