# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Storage failures, concurrent edits and long-horizon retrieval contracts."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
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
from mbtools_gtnh.notes import NotesStore, attachment, read_notes, write_note
from mbtools_gtnh import notes
from kernel import BridgeError


def clock(hour, minute=0, day=4):
    """Pin the notes' wall clock (UTC) for a block of writes or reads."""
    return patch.object(notes, "_now", lambda: datetime(2026, 10, day, hour, minute, tzinfo=timezone.utc))


def ids(found):
    return [n["id"] for n in found["notes"]]


class WorldNotesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        mbtool.state.pop("notes", None)
        self.addCleanup(mbtool.state.pop, "notes", None)
        self.world = str(uuid.uuid4())
        self.store = NotesStore(Path(self.tmp.name)/"notes.sqlite3", self.world)

    def note(self, **overrides):
        return dict(dict(title="ME terminal adapter", text="Implement request UI after reaching AE2.",
                         attachments=[dict(kind="block",dimension=0,pos=[10,64,20],observed=dict(id="ae:terminal",meta=2))],
                         tags=["Adapter", "AE2"]), **overrides)

    def save(self, id="terminal", **kwargs):
        return self.store.write(id,0,"create-"+id,self.note(**kwargs))["note"]

    def test_reopen_new_process_history_retry_and_restore(self):
        original = self.save()
        self.store.write("terminal",1,"archive",{"status":"archived"})
        replay = self.store.write("terminal",0,"create-terminal",self.note())
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["note"], original)
        code = "import mbtool;from mbtools_gtnh.notes import NotesStore;import sys,json;print(json.dumps(NotesStore(sys.argv[1],sys.argv[2]).get('terminal')))"
        result = subprocess.check_output([sys.executable,"-c",code,str(self.store.path),self.world],cwd=MCP,text=True)
        self.assertEqual(json.loads(result)["status"],"archived")
        self.assertEqual(len(self.store.history("terminal")["revisions"]),2)
        self.assertEqual(self.store.search()["notes"],[])
        self.store.write("terminal",2,"restore",{"status":"open","text":original["text"]})
        self.assertEqual(self.store.get("terminal")["revision"],3)
        with self.assertRaisesRegex(ValueError,"different arguments"):
            self.store.write("terminal",3,"archive",{"status":"done"})

    def test_concurrent_writers_cannot_lose_updates(self):
        self.save()
        def edit(n):
            try:
                return self.store.write("terminal",1,f"edit-{n}",{"text":str(n)})
            except ValueError as error:
                return str(error)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(edit,range(2)))
        self.assertEqual(sum(isinstance(v,dict) for v in results),1)
        self.assertTrue(any("revision conflict" in v for v in results if isinstance(v,str)))
        self.assertEqual(len(self.store.history("terminal")["revisions"]),2)

    def test_failed_history_write_rolls_back_current_note(self):
        self.save()
        with closing(self.store.connect()) as db:
            db.execute("CREATE TRIGGER fail_history BEFORE INSERT ON history BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END")
        with self.assertRaises(sqlite3.DatabaseError):
            self.store.write("terminal",1,"fails",{"text":"must not persist"})
        self.assertEqual(self.store.get("terminal")["revision"],1)
        self.assertEqual(self.store.get("terminal")["text"],self.note()["text"])

    def test_invalid_data_and_world_scope_fail_without_erasing_notes(self):
        self.save()
        for value in [dict(kind="block",dimension=0,pos=[1.2,64,3]),dict(kind="entity",dimension=0,uuid=str(uuid.uuid4()),uuidScope="client_session",lastSeen=[1,64,3])]:
            with self.assertRaises(ValueError):
                self.store.write("terminal",1,"invalid",{"attachments":[value]})
        self.assertEqual(self.store.status()["sequence"],1)
        with self.assertRaisesRegex(ValueError,"world identity mismatch"):
            NotesStore(self.store.path,str(uuid.uuid4()))
        with closing(self.store.connect()) as db:
            db.execute("PRAGMA user_version=999")
        with self.assertRaisesRegex(ValueError,"unsupported"):
            NotesStore(self.store.path,self.world)

    def test_corrupt_database_is_never_reset(self):
        path=Path(self.tmp.name)/"corrupt.sqlite3"
        path.write_bytes(b"broken database")
        with self.assertRaises(sqlite3.DatabaseError):
            NotesStore(path,self.world)
        self.assertEqual(path.read_bytes(),b"broken database")

    def test_process_death_during_transaction_preserves_committed_note(self):
        self.save()
        code = "import sqlite3,os,sys;db=sqlite3.connect(sys.argv[1]);db.execute('BEGIN IMMEDIATE');db.execute(\"UPDATE notes SET snapshot='incomplete'\");os._exit(17)"
        result = subprocess.run([sys.executable,"-c",code,str(self.store.path)],check=False)
        self.assertEqual(result.returncode,17)
        reopened=NotesStore(self.store.path,self.world)
        self.assertEqual(reopened.get("terminal")["text"],self.note()["text"])
        self.assertEqual(reopened.status()["integrity"],"ok")

    def test_spatial_tags_regions_and_dimensions_use_same_attachment(self):
        entity_id=str(uuid.uuid4())
        self.save("base",attachments=[dict(kind="region",dimension=0,min=[0,60,0],max=[20,80,20])],tags=["PLAN","base"])
        self.save("pig",attachments=[dict(kind="entity",dimension=0,uuid=entity_id,uuidScope="server",lastSeen=[30,64,20])])
        self.save("nether",attachments=[dict(kind="location",dimension=-1,pos=[10,64,20])])
        self.save("cross",attachments=[dict(kind="location",dimension=0,pos=[500,64,500]),dict(kind="location",dimension=-1,pos=[10,64,20])])
        self.assertEqual([n["id"] for n in self.store.search(dimension=0,near=[10,64,20],radius=0)["notes"]],["base"])
        self.assertEqual(len(self.store.search(dimension=0,region=dict(min=[19,79,19],max=[50,90,50]))["notes"]),1)
        self.assertEqual(self.store.search(tags=["pLaN"],query="terminal")["notes"][0]["id"],"base")
        self.assertEqual(self.store.search(entity_uuid=entity_id)["notes"][0]["id"],"pig")
        with self.assertRaisesRegex(ValueError,"requires a dimension"):
            self.store.search(near=[10,64,20])

    def test_pagination_keeps_snapshot_during_edits(self):
        for name in ["a","b","c"]:
            self.save(name)
        first=self.store.search(limit=1)
        self.assertEqual(first["notes"][0]["id"],"c")  # newest-changed first
        self.store.write("b",1,"done-b",{"status":"archived"})
        self.save("aa")
        second=self.store.search(limit=1,cursor=first["nextCursor"])
        self.assertEqual(second["notes"][0]["id"],"b")
        self.assertEqual(second["notes"][0]["revision"],1)
        third=self.store.search(limit=1,cursor=second["nextCursor"])
        self.assertEqual(third["notes"][0]["id"],"a")
        self.assertIsNone(third["nextCursor"])
        self.assertEqual([n["id"] for n in self.store.search()["notes"]],["aa","c","a"])  # a new search sees the edits
        for changed in (dict(query="changed"),dict(since="1h"),dict(author="all"),dict(regex=True)):
            with self.assertRaisesRegex(ValueError,"another query"):
                self.store.search(cursor=first["nextCursor"],**changed)

    def test_search_bounds_text_without_losing_full_note_or_anchors(self):
        self.save(text="machine routine "*1000,data={"source":"my_adapter.py"})
        summary=self.store.search()["notes"][0]
        self.assertEqual(len(summary["excerpt"]),280)
        self.assertNotIn("text",summary)
        self.assertEqual(summary["attachments"][0]["pos"],[10,64,20])
        full=self.store.search(detail="full")["notes"][0]
        self.assertEqual(full.pop("age"),"0m")
        self.assertEqual(full,self.store.get("terminal"))

    def test_backup_copies_committed_wal_and_history(self):
        self.save()
        self.store.write("terminal",1,"edit",{"text":"new instructions"})
        output=Path(self.tmp.name)/"backup.sqlite3"
        self.store.backup(output)
        copy=NotesStore(output,self.world)
        self.assertEqual(copy.get("terminal"),self.store.get("terminal"))
        self.assertEqual(copy.status()["integrity"],"ok")
        self.assertEqual(len(copy.history("terminal")["revisions"]),2)
        with self.assertRaises(ValueError):
            self.store.backup(output)

    def test_capture_resolve_preserves_notes_and_world_write_guard(self):
        class Game:
            block="ae:terminal"
            def call(game,method,**params):
                if method=="memory.context":return dict(worldId=self.world,dimension=0,pos=[10,64,20])
                if method=="obs.block":return dict(id=game.block,meta=2,pos=[10,64,20])
                raise AssertionError(method)
        game=Game()
        with patch.dict("os.environ",MODBENCH_NOTES_DIR=self.tmp.name):
            captured=read_notes(game,"capture",dict(kind="block",pos=[10,64,20]))
            write_note(game,self.world,"anchor",0,"put",self.note(attachments=[captured["attachment"]]))
            game.block="minecraft:air"
            resolved=read_notes(game,"resolve",dict(id="anchor"))
            self.assertEqual(resolved["resolved"][0]["status"],"identity_changed_or_unrecorded")
            self.assertEqual(read_notes(game,"get",dict(id="anchor"))["revision"],1)
            with self.assertRaisesRegex(ValueError,"world changed"):
                write_note(game,str(uuid.uuid4()),"anchor",1,"wrongworld",{"text":"wrong"})

    def test_search_finds_every_word_or_one_regex_and_returns_the_matching_lines(self):
        body = ["head", "Tin: 12 in chest A.B", "copper 40", "x" * 300 + " TIN dust " + "y" * 300, "tail", "end"]
        self.save("log", title="Bronze line", text="\n".join(body))
        self.save("other", title="Steam", text="nothing here")
        find = lambda **q: ids(self.store.search(**q))
        self.assertEqual(find(query="tin"), ["log"])                           # a substring, whatever its case
        self.assertEqual(find(query="tin", case=True), [])
        self.assertEqual(find(query="TIN dust", case=True), ["log"])
        self.assertEqual((find(query="copper tin"), find(query="copper bronze dust"), find(query="copper zinc")), (["log"], ["log"], []))  # every word, anywhere in the note
        self.assertEqual((find(query="copper tin", regex=True), find(query="tin dust", regex=True)), ([], ["log"]))                     # one pattern: side by side on a line
        both = self.store.search(query="copper head", context=0)["notes"][0]
        self.assertEqual((both["matchingLines"], both["excerpt"]), (2, "1:head\n3:copper 40"))  # a line with any of the words is shown
        self.assertEqual(find(query="t.n"), []); self.assertEqual(find(query="a.b"), ["log"])   # a dot is a dot
        self.assertEqual(find(query="t.n", regex=True), ["log"])
        self.assertEqual(find(query=r"^copper \d+$", regex=True), ["log"])     # a regular expression is tried on each line
        self.assertEqual(find(query="("), [])
        with self.assertRaisesRegex(ValueError, "not a valid regular expression"): self.store.search(query="(", regex=True)
        hit = self.store.search(query="tin")["notes"][0]
        lines = hit["excerpt"].split("\n")
        self.assertEqual((hit["matchingLines"], lines[:3], lines[4:]), (2, ["1-head", "2:Tin: 12 in chest A.B", "3-copper 40"], ["5-tail"]))
        self.assertTrue(lines[3].startswith("4:...x") and " TIN dust " in lines[3] and len(lines[3]) < 170, lines[3])  # a long line is cut around the match
        self.assertNotIn("text", hit); self.assertEqual(hit["attachments"][0]["pos"], [10, 64, 20])
        self.assertEqual(len(self.store.search(query="tin", context=0)["notes"][0]["excerpt"].split("\n")), 2)
        self.assertEqual(len(self.store.search(query="tin", context=5)["notes"][0]["excerpt"].split("\n")), 6)
        with self.assertRaises(ValueError): self.store.search(query="tin", context=6)
        for where in ("bronze", "LOG", "ae2"):                                 # found in the title, id or a tag: the note, with the start of its text
            named = self.store.search(query=where, subject=None)["notes"]
            self.assertEqual((named[-1]["id"], named[-1]["excerpt"], "matchingLines" in named[-1]), ("log", "\n".join(body)[:280], False))
        self.save("many", text="\n".join(f"ore {i}" for i in range(9)))
        many = self.store.search(query="ore", context=0)["notes"][0]
        self.assertEqual((many["matchingLines"], many["excerpt"].split("\n")), (9, [f"{i + 1}:ore {i}" for i in range(5)]))

    def test_search_is_newest_changed_first_and_filters_by_time_with_ages(self):
        with clock(8): self.save("old")
        with clock(10): self.save("mid", tags=["plan"])
        with clock(11, 30): self.save("new")
        with clock(11, 50): self.store.write("old", 1, "touch-old", {"status": "done"})
        find = lambda **q: ids(self.store.search(status="all", **q))
        with clock(12):
            found = self.store.search(status="all")
            self.assertEqual([(n["id"], n["age"]) for n in found["notes"]], [("old", "10m"), ("new", "30m"), ("mid", "2h")])
            self.assertEqual(found["now"], "2026-10-04T12:00")
            self.assertEqual(find(since="45m"), ["old", "new"])
            self.assertEqual(find(before="45m"), ["mid"])                      # by its last change, not its first
            self.assertEqual(find(since="2026-10-04T09:00", before="2026-10-04T11:00"), ["mid"])
            self.assertEqual(find(since="2026-10-04T11:40:00+00:00"), ["old"])
            self.assertEqual(find(since="1d", tags=["plan"], query="terminal", dimension=0, near=[10, 64, 20], radius=1), ["mid"])  # every filter must hold
            self.assertEqual(find(since="1h", tags=["plan"]), [])
            self.assertEqual(ids(self.store.search(before="1h", query="terminal", kind="block", status="open")), ["mid"])
            for bad in ("yesterday", "5 h", 5, "", {"hours": 5}, ["5h"]):
                with self.assertRaisesRegex(ValueError, "since must be an age"): self.store.search(since=bad)
            with self.assertRaisesRegex(ValueError, "before must be an age"): self.store.search(before="h")
        with clock(15): self.assertEqual([n["age"] for n in self.store.search(status="all")["notes"]], ["3h", "3h", "5h"])
        with clock(12, day=7): self.assertEqual([n["age"] for n in self.store.search(status="all")["notes"]], ["3d", "3d", "3d"])

    def test_a_page_is_capped_in_characters_and_the_cursor_continues(self):
        for i in range(12): self.save(f"n{i:02}", text=f"line {i} " + "pad " * 100)
        size = lambda page: len(json.dumps(page["notes"], separators=(",", ":"), ensure_ascii=False))  # as the MCP server serialises it
        seen, cursor, pages = [], None, 0
        while not pages or cursor:
            page = self.store.search(max_chars=1500, cursor=cursor); pages += 1
            self.assertLessEqual(size(page), 1500); self.assertTrue(page["notes"])
            seen += ids(page); cursor = page["nextCursor"]
        self.assertEqual(seen, [f"n{i:02}" for i in reversed(range(12))]); self.assertGreater(pages, 3)
        self.assertEqual(len(self.store.search(limit=100)["notes"]), 12)       # the store's own callers (surfacing, mb_view) are not capped
        self.save("huge", text="x" * 30000)
        big = self.store.search(detail="full", max_chars=1000)
        self.assertEqual((ids(big), big["notes"][0]["bodyCharacters"], "text" in big["notes"][0]), (["huge", "n11"], 30000, False))  # named, not sent
        self.assertIn("get reads it", big["notes"][0]["omitted"]); self.assertLessEqual(size(big), 1000)
        self.assertEqual(ids(self.store.search(detail="full", max_chars=1000, cursor=big["nextCursor"])), ["n10"])
        for bad in (999, 20001, "many", True):
            with self.assertRaises(ValueError): self.store.search(max_chars=bad)

    def test_append_adds_a_dated_entry_without_the_text_and_is_retry_safe(self):
        with clock(9): self.save("stock", text="Main chest wall.")
        with clock(12, 5): done = self.store.append("stock", "op-a", " 40 copper ingots in chest 3 \n")
        self.assertEqual((done["note"]["revision"], done["note"]["text"]), (2, "Main chest wall.\n[2026-10-04T12:05] 40 copper ingots in chest 3"))
        self.assertEqual((done["note"]["createdAt"][:16], done["note"]["updatedAt"][:16]), ("2026-10-04T09:00", "2026-10-04T12:05"))
        with clock(13): again = self.store.append("stock", "op-a", " 40 copper ingots in chest 3 \n")   # the retry of a lost reply
        self.assertEqual((again["replayed"], again["note"], self.store.get("stock")["revision"]), (True, done["note"], 2))
        with self.assertRaisesRegex(ValueError, "different arguments"): self.store.append("stock", "op-a", "something else")
        with self.assertRaisesRegex(ValueError, "not found"): self.store.append("missing", "op-b", "x")
        for bad in ("", "  ", 5, "x" * 40000):
            with self.assertRaises(ValueError): self.store.append("stock", "op-bad", bad)
        with self.assertRaisesRegex(ValueError, "patch must contain"): self.store.write("stock", 2, "op-c", {"append": "x"})
        with self.assertRaisesRegex(ValueError, "patch must contain"): self.store.write("stock", None, "op-d", {"text": "x"})
        self.assertEqual(self.store.get("stock"), done["note"])
        with clock(14), ThreadPoolExecutor(max_workers=4) as pool:               # no revision guard: entries from several writers all land
            list(pool.map(lambda n: self.store.append("stock", f"many-{n}", f"entry {n}"), range(4)))
        note = self.store.get("stock")
        self.assertEqual((note["revision"], sorted(note["text"].split("\n")[2:])), (6, [f"[2026-10-04T14:00] entry {n}" for n in range(4)]))
        self.assertEqual(self.store.search(query="copper ingots", context=0)["notes"][0]["excerpt"], "2:[2026-10-04T12:05] 40 copper ingots in chest 3")
        self.save("empty", text="")
        with clock(15): self.assertEqual(self.store.append("empty", "op-e", "first")["note"]["text"], "[2026-10-04T15:00] first")

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


class NotesSurfacingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.env = patch.dict("os.environ", MODBENCH_NOTES_DIR=self.tmp.name); self.env.start(); self.addCleanup(self.env.stop)
        mbtool.state.pop("notes", None); self.addCleanup(mbtool.state.pop, "notes", None)
        self.world = str(uuid.uuid4()); self.game = Game(self.world)
        self.store = notes.store_for(self.world)
        self.addCleanup(mbtool.state.pop, "kernel", None)

    def put(self, id, attachment, **extra):
        self.store.write(id, 0, "create-" + id, dict(title=f"note {id}", text="details " * 3 + id, attachments=[attachment], tags=["plan"], **extra))

    def at(self, x, y, z, dimension=0): return dict(kind="location", dimension=dimension, pos=[x, y, z])

    def test_surface_is_bounded_nearest_first_deduplicated_and_reset_by_movement(self):
        for i in range(7): self.put(f"n{i}", self.at(10 + 2 * i, 64, 20))
        self.put("far", self.at(500, 64, 500)); self.put("nether", self.at(10, 64, 20, dimension=-1))
        found = notes.surface(self.game, reason="session", radius=32)
        self.assertEqual([n["id"] for n in found], ["n0", "n1", "n2", "n3", "n4"])
        self.assertRegex(found[0].pop("updated"), r"^20\d\d-\d\d-\d\dT\d\d:\d\d$")  # surfaced notes carry their age, to the minute
        self.assertEqual(found[0], {"id": "n0", "title": "note n0"})  # a note rides along by name: mb_notes get reads it
        self.assertEqual([n["id"] for n in notes.surface(self.game, reason="session", radius=32)], ["n5", "n6"])   # the rest, once
        self.assertEqual(notes.surface(self.game, reason="session", radius=32), [])                                 # nothing new
        self.game.pos = [12, 64, 20]
        self.assertEqual(notes.surface(self.game, position=[12, 64, 20], dimension=0, reason="position"), [])       # small move: still shown
        self.game.pos = [120, 64, 20]
        self.assertEqual(notes.surface(self.game, position=[120, 64, 20], dimension=0, radius=200, reason="position")[0]["id"], "n6")
        self.assertEqual(notes.surface(self.game, reason="session", radius=32), [])                                 # far from every note now
        self.assertEqual(notes.surface(Game(str(uuid.uuid4())), reason="session"), [])                              # world without notes: no store created
        self.assertEqual(sorted(p.name for p in Path(self.tmp.name).iterdir() if p.suffix == ".sqlite3"), [f"{self.world}.sqlite3"])

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
        self.assertEqual([n["id"] for n in seen["notes"]], ["room", "anchor"])  # equally near: the newer note first
        self.assertNotIn("notes", notes.after("obs.block", {"x": 40, "y": 65, "z": 40}, {"id": "minecraft:stone", "pos": [40, 65, 40]}))
        found = notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "server", "pos": [30, 64, 20]})
        self.assertEqual([n["id"] for n in found["notes"]], ["pig"])
        self.assertNotIn("notes", notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "client_session"}))
        self.assertEqual(notes.after("obs.player", {}, "not an object"), "not an object")
        self.put("camp", self.at(150, 64, 150)); self.game.pos = [200, 64, 200]
        arrived = notes.after("nav.goto", {}, {"state": "succeeded", "arrival": {"pos": [152, 64, 150]}})
        self.assertEqual([n["id"] for n in arrived["notes"]], ["camp"])
        self.assertNotIn("notes", notes.after("nav.goto", {}, {"state": "succeeded", "arrival": {"pos": [152, 64, 150]}}))  # shown already

    def test_auto_journal_keys_by_location_and_records_failures(self):
        mbtool.state["kernel"] = self.game
        done = notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [3, 5, 7], "blocksPlaced": 10, "ticks": 40})
        self.assertEqual((done["id"], done["tags"], done["status"], done["revision"]), ("auto-build-0-0-4-4", ["auto", "build", "done"], "done", 1))
        self.assertEqual(done["attachments"][0]["pos"], [3, 5, 7]); self.assertIn("build done at 3,5,7", done["title"])
        self.assertEqual(json.loads(done["text"])["blocksPlaced"], 10)
        again = notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [2, 6, 5], "blocksPlaced": 3})
        self.assertEqual((again["id"], again["revision"]), ("auto-build-0-0-4-4", 2))                    # same 4-block cell: updated, not duplicated
        self.assertEqual(len(self.store.search(tags=["auto"], status="all")["notes"]), 1)
        self.assertIsNone(notes.journal(self.game, "nav.build", {"state": "failed"}))                 # failures arrive as BridgeError
        self.assertIsNone(notes.journal(self.game, "nav.goto", {"state": "succeeded", "arrival": {"pos": [1, 1, 1]}}))
        self.assertIsNone(notes.journal(self.game, "obs.block", {"state": "succeeded"}))
        error = BridgeError("action_failed", "no path", "nav.route", {"error": {"receipt": {"state": "failed", "reason": "stuck", "goal": [9, 9, 9], "jobId": "j1"}}})
        failed = notes.journal(self.game, "nav.route", None, error=error)
        self.assertEqual((failed["id"], failed["status"], failed["tags"]), ("auto-route-0-8-8-8", "open", ["auto", "failed", "route"]))
        self.assertIn("stuck", failed["title"]); self.assertEqual(json.loads(failed["text"])["jobId"], "j1")
        self.assertIsNone(notes.journal(self.game, "nav.mine", None, error=BridgeError("cancelled", "stopped", "nav.mine", {})))
        # tracked() wires it together: the receipt is journaled, then the fresh auto note surfaces at the arrival position.
        self.game.receipt = {"state": "succeeded", "goal": [40, 64, 40], "blocksMined": 5}
        result = notes.tracked("nav.mine", 30, blocks=[{"id": "a:b"}])
        self.assertEqual(result["blocksMined"], 5); self.assertEqual([n["id"] for n in result["notes"]], ["auto-mine-0-40-64-40"])
        self.assertEqual(result["endedAt"], [round(v, 1) for v in self.game.pos])  # where the job left you rides on its receipt
        self.game.receipt = {"state": "running"}
        self.assertNotIn("notes", notes.tracked("nav.mine", 30, blocks=[]))
        class Failing(Game):
            def call(self, method, **params):
                if method.startswith("nav."): raise error
                return super().call(method, **params)
        mbtool.state["kernel"] = Failing(self.world)
        with self.assertRaises(BridgeError): notes.tracked("nav.route", 30, name="x")
        self.assertEqual(self.store.get("auto-route-0-8-8-8")["revision"], 2)

    def test_note_write_answers_with_a_receipt_not_the_note_again(self):
        mbtool.state["kernel"] = self.game
        place = dict(kind="location", dimension=0, pos=[10, 64, 20])
        done = notes.mb_note_write(self.world, "plan", 0, "op-1", dict(title="LV workshop", text="roof first " * 80, attachments=[place], tags=["base"]))
        self.assertEqual(done, {"saved": True, "replayed": False, "sequence": 1, "id": "plan", "revision": 1, "size": 880, "attachments": [place]})
        again = notes.mb_note_write(self.world, "plan", 0, "op-1", dict(title="LV workshop", text="roof first " * 80, attachments=[place], tags=["base"]))
        self.assertEqual((again["replayed"], again["revision"]), (True, 1))
        self.assertEqual(notes.mb_note_write(self.world, "plan", 1, "op-2", dict(status="done")), {**done, "sequence": 2, "revision": 2})
        self.assertEqual(self.store.get("plan")["text"], "roof first " * 80)

    def test_item_and_topic_notes_have_no_place_and_surface_by_subject(self):
        self.put("cassiterite", dict(kind="item", item="gregtech:gt.blockores:1823"))
        self.put("boiler", dict(kind="topic", topic="Machine:Boiler"))
        self.put("here", self.at(10, 64, 20))
        self.assertEqual([n["id"] for n in self.store.search(subject="machine:boiler")["notes"]], ["boiler"])
        self.assertEqual([n["id"] for n in self.store.search(dimension=0, near=[10, 64, 20], radius=4)["notes"]], ["here"])
        self.assertEqual(len(self.store.search(dimension=-1)["notes"]), 2)  # a dimension filter never hides notes that have no place
        result = {"slots": [{"id": "gregtech:gt.blockores", "meta": 1823, "count": 3}, {"id": "minecraft:stick", "meta": 0}]}
        self.assertEqual(notes.item_subjects(result), ["gregtech:gt.blockores", "gregtech:gt.blockores:1823", "minecraft:stick", "minecraft:stick:0"])
        found = notes.surface(self.game, subjects=notes.item_subjects(result), reason="item")
        self.assertEqual([n["id"] for n in found], ["cassiterite"])
        self.assertEqual(notes.surface(self.game, subjects=notes.item_subjects(result)), [])  # shown once
        with self.assertRaisesRegex(ValueError, "unknown attachment fields"):
            attachment(dict(kind="item", item="a:b", pos=[0, 0, 0]))

    def test_auto_notes_stay_out_of_search_unless_asked_for_and_still_surface(self):
        mbtool.state["kernel"] = self.game
        self.put("mine", self.at(10, 64, 20))
        notes.journal(self.game, "nav.build", {"state": "succeeded", "origin": [10, 64, 20], "blocksPlaced": 10})
        find = lambda **q: ids(self.store.search(status="all", **q))
        self.assertEqual((find(), find(author="me")), (["mine"], ["mine"]))
        self.assertEqual(find(author="auto"), ["auto-build-0-8-64-20"])
        self.assertEqual(find(author="all"), ["auto-build-0-8-64-20", "mine"])
        self.assertEqual(find(tags=["auto"]), ["auto-build-0-8-64-20"])            # asking for the tag is asking for them
        self.assertEqual(find(tags=["auto"], author="me"), [])
        self.assertEqual((find(query="blocksPlaced"), find(query="blocksPlaced", author="all")), ([], ["auto-build-0-8-64-20"]))
        with self.assertRaisesRegex(ValueError, "author must be"): self.store.search(author="harness")
        self.assertEqual(ids(notes.mb_notes("search", {"near": "player", "radius": 8, "status": "all"})), ["mine"])
        self.assertEqual(sorted(n["id"] for n in notes.surface(self.game, reason="session", radius=32)), ["auto-build-0-8-64-20", "mine"])

    def test_notes_tool_search_is_capped_by_default_and_null_does_not_lift_it(self):
        mbtool.state["kernel"] = self.game
        for i in range(30): self.put(f"n{i:02}", self.at(10, 64, 20))
        for params in ({"limit": 100}, {"limit": 100, "max_chars": None}):
            page = notes.mb_notes("search", params)
            self.assertLessEqual(len(json.dumps(page["notes"], separators=(",", ":"), ensure_ascii=False)), notes.SEARCH_CHARS)
            self.assertTrue(0 < len(page["notes"]) < 30); self.assertIsNotNone(page["nextCursor"])
        rest = notes.mb_notes("search", {"limit": 100, "max_chars": 20000, "cursor": page["nextCursor"]})
        self.assertEqual(ids(page) + ids(rest), [f"n{i:02}" for i in reversed(range(30))])
        self.assertEqual(ids(notes.mb_notes()), ids(page)[:20])  # the bare call still works: a page of the newest

    def test_note_append_tool_and_get_show_when_things_were_written(self):
        mbtool.state["kernel"] = self.game
        place = self.at(10, 64, 20)
        with clock(9): self.put("stock", place)
        with clock(11): done = notes.mb_note_append("stock", "40 copper", "op-1")
        text = "details details details stock\n[2026-10-04T11:00] 40 copper"
        self.assertEqual(done, {"saved": True, "replayed": False, "sequence": 2, "id": "stock", "revision": 2, "size": len(text), "attachments": [place]})
        self.assertEqual(notes.mb_note_append("stock", "40 copper", "op-1")["replayed"], True)
        with clock(14, 30): note = notes.mb_notes("get", {"id": "stock"})
        self.assertEqual((note["age"], note["now"], note["createdAt"][:16], note["updatedAt"][:16], note["text"]), ("3h", "2026-10-04T14:30", "2026-10-04T09:00", "2026-10-04T11:00", text))
        with self.assertRaisesRegex(ValueError, "not found"): notes.mb_note_append("nope", "x", "op-2")

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
        self.assertEqual(self.store.get("goal-stack")["revision"], 2)
        self.assertNotIn("progress", notes.goal(self.game))  # the estimate is optional
        self.assertEqual(notes.goal(self.game, dict(subgoal="alloy bronze", progress=60))["progress"], 60)
        self.assertEqual(notes.goal(self.game, dict(quest="Bronze", progress=40))["progress"], 40)  # it may go down
        self.assertEqual(notes.goal(self.game, dict(subgoal="hand it in"))["progress"], 40)
        self.assertNotIn("progress", notes.goal(self.game, dict(quest="Steel")))  # a new quest starts without one
        with self.assertRaisesRegex(ValueError, "progress must be an integer in 0..100"): notes.goal(self.game, dict(progress=140))
        self.store.write("goal-stack", 6, "archive-goal", {"status": "archived"})
        self.assertIn("unset", notes.goal(self.game))
        self.assertEqual(notes.goal(self.game, dict(quest="Steel"))["chapter"], "")  # a cleared stack starts again

if __name__=="__main__":unittest.main()
