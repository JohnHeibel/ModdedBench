# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""codex_loop against a fake Codex command; no model is ever called."""
import base64, contextlib, io, json, os, sys, tempfile, unittest, unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runner"))
import codex_loop

# Replays plan.json[n] for the n-th call: {"events": [...], "exit": 0, "stop": false}; records argv and stdin.
FAKE = """import json, sys
from pathlib import Path
here = Path(__file__).parent; calls = here / "calls.json"
seen = json.loads(calls.read_text()) if calls.exists() else []
plan = json.loads((here / "plan.json").read_text()); step = plan[min(len(seen), len(plan) - 1)]
seen.append({"argv": sys.argv[1:], "stdin": sys.stdin.read()}); calls.write_text(json.dumps(seen))
print("not json")
for event in step.get("events", []):
    print(json.dumps(event), flush=True)
    if event.get("sleep"): import time; time.sleep(event["sleep"])
if step.get("stop"): (here / ".state" / "STOP").write_text("")
sys.exit(step.get("exit", 0))
"""
def message(text): return {"type": "item.completed", "item": {"id": "item_1", "type": "agent_message", "text": text}}

class CodexLoopTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.repo = Path(self.tmp.name)
        (self.repo / "fake.py").write_text(FAKE); (self.repo / "PROMPT.md").write_text("the mission")
        # The agent's own container sets MODBENCH_OUTBOX to the folder a live run's overlay is written to:
        # a test run there would overwrite the feed a viewer is watching, and then not find its own.
        self.outbox = os.environ.pop("MODBENCH_OUTBOX", None)
    def tearDown(self):
        if self.outbox is not None: os.environ["MODBENCH_OUTBOX"] = self.outbox
        self.tmp.cleanup()
    def loop(self, plan, **kw):
        (self.repo / "plan.json").write_text(json.dumps(plan))
        with contextlib.redirect_stdout(io.StringIO()):
            reason = codex_loop.run(self.repo, codex=[sys.executable, str(self.repo / "fake.py")], backoff_s=0, **{"ready": lambda: True, "quests": lambda: 7, **kw})
        return reason, json.loads((self.repo / "calls.json").read_text())

    def test_a_start_that_keeps_the_planned_end_keeps_the_run_start(self):
        path = self.repo / "run.json"
        codex_loop._mark_run(path, 1000.0, 60)
        codex_loop._mark_run(path, 1600.0, 50.5)  # a fresh thread 10 min in, same end within a minute
        self.assertEqual(json.loads(path.read_text())["startedAt"], 1000.0)
        codex_loop._mark_run(path, 9000.0, 60)    # a new run
        self.assertEqual(json.loads(path.read_text()), {"startedAt": 9000.0, "endsAt": 9000.0 + 3600})

    def test_a_screenshot_is_estimated_by_its_tiles_not_its_base64(self):
        from feed import Feed, BASE
        with tempfile.TemporaryDirectory() as d:
            f = Feed(Path(d))
            f.event({"type": "turn.started"})
            f.event({"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": "mb_screenshot", "result": {"content": [{"type": "image", "data": "A" * 133000}]}}})
            self.assertLess(f.billed() - BASE, 2000)
            f.event({"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": "mb_recipes", "result": {"content": [{"type": "text", "text": "x y " * 4000}]}}})
            self.assertGreater(f.billed() - 2 * BASE, 5000)

    def test_a_turn_that_never_reports_its_usage_keeps_its_estimate(self):
        from feed import Feed
        with tempfile.TemporaryDirectory() as d:
            f = Feed(Path(d))
            f.event({"type": "turn.started"})
            f.event({"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": "mb_obs", "result": {"content": [{"type": "text", "text": "x" * 4000}]}}})
            cut = f.billed()
            f.event({"type": "turn.started"})  # the first turn was killed: no turn.completed
            self.assertEqual(cut, f.billed())
            f.event({"type": "turn.completed", "usage": {"input_tokens": 100}})
            self.assertEqual(cut + 100, f.billed())

    def test_the_overlay_clock_runs_only_inside_turns(self):
        from feed import Feed
        with tempfile.TemporaryDirectory() as d, unittest.mock.patch("feed.time.time") as now:
            now.return_value = 100.0; f = Feed(Path(d))
            f.event({"type": "turn.started"}); now.return_value = 130.0
            f.event({"type": "turn.completed", "usage": {}}); now.return_value = 1000.0
            f.status("backing_off"); f.status("between_turns")
            self.assertEqual((30.0, None), (f.live["stats"]["activeSeconds"], f.live["stats"]["activeSince"]))
            f.event({"type": "turn.started"}); now.return_value = 1010.0; f.status("acting", "mining")  # then the loop is killed
            now.return_value = 5000.0; self.assertEqual((40.0, None), (Feed(Path(d)).live["stats"]["activeSeconds"], None))

    def test_token_budget_ends_the_turn_where_it_stands_and_the_thread_resumes(self):
        call = {"type": "item.completed", "item": {"id": "c", "type": "mcp_tool_call", "tool": "mb_obs", "arguments": {}, "result": {"content": [{"type": "text", "text": "x" * 4000}]}}}
        reason, calls = self.loop([{"events": [{"type": "thread.started", "thread_id": "T-1"}, *[dict(call) for _ in range(4)], {"sleep": 30}, message("never")]}], max_tokens=3000)
        self.assertEqual("token_budget", reason); self.assertEqual(1, len(calls))
        self.assertEqual({"thread": "T-1"}, json.loads((self.repo / ".state" / "codex-loop.json").read_text()))
        self.assertTrue((self.repo / ".state" / "codex-loop.log").read_text().splitlines()[-1].endswith("budget: token_budget"))
        reason, calls = self.loop([{"events": [{"type": "thread.started", "thread_id": "T-1"}, {"type": "turn.completed", "usage": {"input_tokens": 5}}, message("MISSION COMPLETE")]}], max_minutes=0.0001)
        self.assertEqual("time_budget", reason)  # a spent clock is checked before a turn starts as well

    def test_captures_thread_id_resumes_and_stops_on_mission_complete_line(self):
        reason, calls = self.loop([
            {"events": [{"type": "thread.started", "thread_id": "T-1"}, message("not MISSION COMPLETE yet")]},
            {"events": [{"type": "thread.started", "thread_id": "T-2"}, message("claimed and verified\nMISSION COMPLETE\n")]}], extra=["-m", "x"])
        self.assertEqual("complete", reason); self.assertEqual(2, len(calls))
        self.assertEqual(["exec", "--json", "-C", str(self.repo), "-m", "x", "-"], calls[0]["argv"]); self.assertEqual("the mission", calls[0]["stdin"])
        self.assertEqual(["resume", "T-1", "-"], calls[1]["argv"][-3:]); self.assertEqual(codex_loop.CONTINUE, calls[1]["stdin"])
        self.assertEqual({"thread": "T-1"}, json.loads((self.repo / ".state" / "codex-loop.json").read_text()))
        self.assertIn("MISSION COMPLETE", (self.repo / ".state" / "codex-loop.log").read_text())
        # A restarted loop resumes the saved thread; session_id nested in a payload is also accepted.
        self.assertEqual("T-9", codex_loop._find_id({"type": "session_meta", "payload": {"session_id": "T-9"}}))
        (self.repo / "calls.json").unlink(); reason, calls = self.loop([{}], max_turns=1)
        self.assertEqual(("max_turns", ["resume", "T-1", "-"]), (reason, calls[0]["argv"][-3:]))

    def test_stop_file_ends_the_loop(self):
        reason, calls = self.loop([{"events": [{"type": "thread.started", "thread_id": "T-1"}], "stop": True}])
        self.assertEqual(("stop_file", 1), (reason, len(calls)))

    def test_the_stop_file_ends_a_turn_that_is_still_running_and_the_run_is_recorded(self):
        import threading, time
        threading.Timer(1.0, lambda: (self.repo / ".state" / "STOP").write_text("")).start()
        began = time.monotonic()
        reason, calls = self.loop([{"events": [{"type": "thread.started", "thread_id": "T-1"}, {"type": "turn.started", "sleep": 60}]}])
        self.assertEqual("stop_file", reason); self.assertLess(time.monotonic() - began, 30)  # the watchdog, not Codex printing, ended it
        [record] = [json.loads(p.read_text()) for p in (self.repo / ".state" / "runs").glob("*.json")]
        self.assertEqual(("stop_file", 7, 7, "T-1"), (record["endReason"], record["questsBefore"], record["questsAfter"], record["thread"]))
        self.assertEqual(64, len(record["promptSha256"]))

    def test_twelve_consecutive_failures_stop_and_a_success_resets_the_count(self):
        reason, calls = self.loop([{"exit": 1}, {"exit": 1}, {"exit": 0}, {"exit": 1}])
        self.assertEqual(("failed", 15), (reason, len(calls)))
        self.assertFalse((self.repo / ".state" / "codex-loop.json").exists())  # no id was ever reported

    def test_no_turn_starts_while_the_game_is_down(self):
        answers = iter([False, False, True])
        reason, calls = self.loop([{}], max_turns=1, ready=lambda: next(answers))
        self.assertEqual(("max_turns", 1), (reason, len(calls)))
        self.assertEqual(1, (self.repo / ".state" / "codex-loop.log").read_text().count("waiting for the game"))
        (self.repo / ".state" / "STOP").write_text(""); (self.repo / "calls.json").unlink()
        self.assertEqual("stop_file", codex_loop.run(self.repo, codex=["never-run"], backoff_s=0, ready=lambda: False, quests=lambda: None))

    def test_the_loop_leaves_a_feed_and_totals_for_the_overlay(self):
        def call(tool, args, result, status="completed"):
            return {"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": tool, "arguments": args, "status": status, "error": None,
                                                       "result": {"content": [{"type": "text", "text": json.dumps(result)}]}}}
        goal = {"chapter": "Stone Age", "quest": "Your First Night", "subgoal": "get eight dirt", "serves": ""}
        self.loop([{"events": [{"type": "turn.started"}, message("I will start with dirt."), call("mb_goal", {"subgoal": "get eight dirt"}, goal),
                               call("mb_craft", {"times": 2}, {"crafted": {"id": "minecraft:stick", "name": "Stick"}, "gained": 8}),
                               call("mb_scan", {"budget": 9}, {"ok": False}, "failed"), call("mb_quest_claim", {"quest_id": "q"}, {"accepted": True}),
                               call("mb_made_up", {"x": 1}, "not a dict"), {"type": "item.started", "item": {"type": "mcp_tool_call", "tool": "mb_wait", "arguments": {}}},
                               {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 60, "output_tokens": 7}}]}], max_turns=1)
        folder = self.repo / ".state" / "overlay"; live = json.loads((folder / "live.json").read_text())
        feed = [(x["kind"], x["text"]) for x in map(json.loads, (folder / "feed.jsonl").read_text(encoding="utf-8").splitlines())]
        self.assertEqual([("say", "I will start with dirt."), ("tool", "goal: get eight dirt"), ("tool", "crafted 8 × Stick"), ("fail", "looking for blocks: failed"),
                          ("mark", "quest claimed: Your First Night"), ("tool", "made up 1")], feed)
        self.assertEqual((goal, "ended", 1, 5, 1, 1, 107), (live["goal"], live["status"]["state"], live["stats"]["turns"], live["stats"]["calls"], live["stats"]["failed"],
                                                           live["stats"]["claims"], live["stats"]["tokens"]["input"] + live["stats"]["tokens"]["output"]))

    def test_look_tools_leave_pop_ups_for_the_stream_and_only_their_latest_images(self):
        from feed import Feed, POPS
        def call(tool, args, result, status="completed", image=None):
            content = ([{"type": "text", "text": json.dumps(result)}] if result is not None else []) + ([{"type": "image", "data": image, "mimeType": "image/png"}] if image else [])
            return {"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": tool, "arguments": args, "status": status, "result": {"content": content}}}
        view = {"origin": [0, 64, 0], "layers": [{"y": 64, "rows": ["#~", "#@"]}], "legend": {"#": {"id": "minecraft:stone", "count": 2}, "~": {"id": "gregtech:gt.blockmachines", "meta": 3, "count": 1, "name": "Steam Macerator", "tile": True}},
                "things": [{"what": "unnamed", "count": 2}], "you": [1, 64, 1]}
        with tempfile.TemporaryDirectory() as d:
            f = Feed(Path(d)); pops = lambda: json.loads((Path(d) / "pops.json").read_text(encoding="utf-8"))
            f.event(call("mb_obs", {"method": "player"}, {"pos": [5.5, 64.0, 7.5]}))
            f.event(call("mb_obs", {"method": "entities", "params": {"radius": 16}}, {"entities": [{"type": "Zombie", "pos": [8, 64, 7], "distance": 2.5, "hostile": True}]}))
            f.event(call("mb_view", {"look_down": False}, view))
            f.event(call("mb_scan", {"blocks": [{"id": "x:y"}]}, {"ok": False}, status="failed"))  # a failure shows nothing
            f.event(call("mb_inventory", {"container": True}, {"windowId": 3}))  # a container's slots are not drawn
            radar, shown = pops()[0], pops()[1]
            self.assertEqual(("radar", [5.5, 64.0, 7.5], "Zombie"), (radar["kind"], radar["data"]["me"], radar["data"]["entities"][0]["type"]))
            self.assertEqual(("view", {"id": "gregtech:gt.blockmachines", "count": 1, "name": "Steam Macerator", "tile": True}, []), (shown["kind"], shown["data"]["legend"]["~"], shown["data"]["things"]))
            self.assertEqual(2, len(pops()))
            png = base64.b64encode(b"\x89PNG fake").decode()
            for i in range(POPS + 2): f.event(call("mb_screenshot", {}, None, image=png))
            kept = pops(); self.assertEqual(POPS, len(kept))
            self.assertEqual(sorted(p["image"] for p in kept), sorted(x.name for x in Path(d).glob("pop-*.png")))  # images go with their records
            self.assertEqual(b"\x89PNG fake", (Path(d) / kept[-1]["image"]).read_bytes())

if __name__ == "__main__": unittest.main()
