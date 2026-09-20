# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
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
from world_notes import NotesStore, attachment, read_notes, write_note


class WorldNotesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
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
        code = "from world_notes import NotesStore;import sys,json;print(json.dumps(NotesStore(sys.argv[1],sys.argv[2]).get('terminal')))"
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


if __name__=="__main__":unittest.main()
