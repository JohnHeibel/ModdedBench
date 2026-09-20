# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Storage failures, concurrent edits and long-horizon retrieval contracts."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
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
        self.store.write("b",1,"done-b",{"status":"archived"})
        self.save("aa")
        second=self.store.search(limit=1,cursor=first["nextCursor"])
        self.assertEqual(second["notes"][0]["id"],"b")
        self.assertEqual(second["notes"][0]["revision"],1)
        third=self.store.search(limit=1,cursor=second["nextCursor"])
        self.assertEqual(third["notes"][0]["id"],"c")
        self.assertIsNone(third["nextCursor"])
        with self.assertRaisesRegex(ValueError,"another query"):
            self.store.search(query="changed",cursor=first["nextCursor"])

    def test_search_bounds_text_without_losing_full_note_or_anchors(self):
        self.save(text="machine routine "*1000,data={"source":"my_adapter.py"})
        summary=self.store.search()["notes"][0]
        self.assertEqual(len(summary["excerpt"]),280)
        self.assertNotIn("text",summary)
        self.assertEqual(summary["attachments"][0]["pos"],[10,64,20])
        self.assertEqual(self.store.search(detail="full")["notes"][0],self.store.get("terminal"))

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
        if method == "time.state": return dict(simulationTicks=self.ticks)
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
        self.assertTrue(found[0].pop("updated").startswith("20"))  # surfaced notes carry their age
        self.assertEqual(found[0], {"id": "n0", "kind": "location", "title": "note n0", "revision": 1, "status": "open", "at": [10, 64, 20],
                                    "distance": 0.0, "excerpt": "details details details n0", "tags": ["plan"], "why": "session"})
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
        self.assertEqual([(n["id"], n["why"], n["at"]) for n in seen["notes"]], [("anchor", "block", [3, 65, 3]), ("room", "block", {"min": [0, 60, 0], "max": [5, 70, 5]})])
        self.assertNotIn("notes", notes.after("obs.block", {"x": 40, "y": 65, "z": 40}, {"id": "minecraft:stone", "pos": [40, 65, 40]}))
        found = notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "server", "pos": [30, 64, 20]})
        self.assertEqual([n["id"] for n in found["notes"]], ["pig"])
        self.assertNotIn("notes", notes.after("obs.entity", {}, {"found": True, "uuid": entity, "uuidScope": "client_session"}))
        self.assertEqual(notes.after("obs.player", {}, "not an object"), "not an object")
        self.put("camp", self.at(150, 64, 150)); self.game.pos = [200, 64, 200]
        arrived = notes.after("nav.goto", {}, {"state": "succeeded", "arrival": {"pos": [152, 64, 150]}})
        self.assertEqual([(n["id"], n["why"], n["distance"]) for n in arrived["notes"]], [("camp", "arrival", 2.0)])
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
        self.game.receipt = {"state": "running"}
        self.assertNotIn("notes", notes.tracked("nav.mine", 30, blocks=[]))
        class Failing(Game):
            def call(self, method, **params):
                if method.startswith("nav."): raise error
                return super().call(method, **params)
        mbtool.state["kernel"] = Failing(self.world)
        with self.assertRaises(BridgeError): notes.tracked("nav.route", 30, name="x")
        self.assertEqual(self.store.get("auto-route-0-8-8-8")["revision"], 2)

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
        self.assertEqual([(n["id"], n["at"]) for n in found], [("cassiterite", "gregtech:gt.blockores:1823")])
        self.assertEqual(notes.surface(self.game, subjects=notes.item_subjects(result)), [])  # shown once
        with self.assertRaisesRegex(ValueError, "unknown attachment fields"):
            attachment(dict(kind="item", item="a:b", pos=[0, 0, 0]))

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

if __name__=="__main__":unittest.main()
