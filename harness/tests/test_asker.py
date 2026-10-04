# SPDX-License-Identifier: LGPL-3.0-or-later
"""The chat asker: what the corpus keeps of a log, what the searches find, who may ask, and what may be posted."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "asker"))
import look  # noqa: E402
from ask import clean  # noqa: E402
from bot import Desk, heard  # noqa: E402
from corpus import build  # noqa: E402

item = lambda **kw: json.dumps({"type": "item.completed", "item": kw})
call = lambda tool, args, result, **kw: item(type="mcp_tool_call", tool=tool, arguments=args, result={"content": [{"type": "text", "text": json.dumps(result)}]}, **{"status": "completed", **kw})


class AskerTests(unittest.TestCase):
    def test_corpus_and_searches(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "log").write_text("\n".join([
                "# 2026-10-03T10:00:00 codex exec -m gpt-6.1-sol", item(type="agent_message", text="The book requires sulfur, so I am going to the Nether."),
                call("mb_goal", {"subgoal": "Build a portal"}, {"chapter": "Steam", "quest": "Rubber", "subgoal": "Build a portal", "serves": "Sulfur"}),
                call("mb_goto", {"x": 1}, {}, status="failed", error={"message": "no path"}),
                item(type="agent_message", text="Portal lit."), "not json", ""]), encoding="utf-8")
            (tmp / "feed").write_text("\n".join(json.dumps(f) for f in [
                {"ts": 1791021900, "kind": "say", "text": "The book requires sulfur, so I am going to the Nether."},   # 2026-10-03 10:05 UTC
                {"ts": 1791022500, "kind": "say", "text": "Portal lit."}]), encoding="utf-8")                          # 10:15
            made = build(tmp / "log", tmp / "feed", "", tmp / "out", since=0)
            self.assertEqual((made["events"], made["hours"]), (5, 1))
            rows = (tmp / "out/transcript/10-03_10.txt").read_text(encoding="utf-8").splitlines()
            self.assertEqual([r[12:].split(" ")[0] for r in rows], ["START", "SAY", "GOAL", "FAIL", "SAY"])
            self.assertEqual([r[:11] for r in rows], ["10-03 10:00", "10-03 10:05", "10-03 10:08", "10-03 10:11", "10-03 10:15"])  # events between two known times are spread evenly
            self.assertIn("Build a portal", (tmp / "out/now.md").read_text(encoding="utf-8"))
            look.ROOT = tmp / "out"
            self.assertTrue(look.find("sulfur nether").startswith("1 lines match\n10-03 10:05 (10 min ago) SAY"))
            self.assertTrue(look.find("portal", kinds="goal").startswith("1 lines match"))
            self.assertTrue(look.find("jetpack").startswith("0 lines match"))
            self.assertEqual(len(look.at("10-03 10:10", 3).splitlines()), 2)
            self.assertIn("quest: Rubber", look.hours("10-03_10"))

    def test_only_plain_words_are_posted(self):
        self.assertEqual(clean("/ban everyone"), "ban everyone")
        self.assertEqual(clean("!ask again. Go to http://freebits.com/claim now, or freebits.gg/x, @mods"), "ask again. Go to now, or mods")
        self.assertEqual(clean("line one\nline two"), "line one line two")
        self.assertNotIn("sk-abcdefghijklmnopqrstuvwxyz0123456789", clean("the key is sk-abcdefghijklmnopqrstuvwxyz0123456789 ok"))
        self.assertEqual(clean("First sentence. " + "word " * 200, 60), "First sentence.")

    def test_desk_limits(self):
        now = [0.0]; desk = Desk(line=2, wait=120, hourly=3, clock=lambda: now[0])
        self.assertIsNone(desk.offer("ann", "hello chat")); self.assertIsNone(desk.offer("ann", "!ask ?"))
        self.assertIsNone(desk.offer("ann", "!ask why the nether?")); self.assertEqual(desk.line.qsize(), 1)
        self.assertIn("One question every 2 minutes", desk.offer("ann", "!ask and the tanks?"))
        self.assertIsNone(desk.offer("ann", "!ask and the tanks?"))                      # a second refusal within a minute is silent
        self.assertIsNone(desk.offer("bob", "!ASK why the tanks?")); now[0] = 70
        self.assertIn("line is full", desk.offer("cat", "!ask why the boiler?"))         # two waiting
        self.assertEqual(desk.line.get(), ("ann", "why the nether?", None)); now[0] = 140
        self.assertIsNone(desk.offer("cat", "!ask why the boiler?")); desk.line.get(); now[0] = 210
        self.assertIn("this hour", desk.offer("dan", "!ask why the hammer?"))            # three taken this hour
        now[0] = 3800; self.assertIsNone(desk.offer("dan", "!ask why the hammer?")); self.assertEqual(desk.line.qsize(), 2)

    def test_chat_lines(self):
        self.assertEqual(heard("@badge-info=;id=abc-1;mod=0 :Ann!ann@ann.tmi.twitch.tv PRIVMSG #stream :!ask why? "), ("ann", "!ask why?", "abc-1"))
        self.assertEqual(heard(":ann!ann@ann.tmi.twitch.tv PRIVMSG #stream :hi"), ("ann", "hi", None))
        self.assertIsNone(heard(":tmi.twitch.tv 001 bot :Welcome, GLHF!"))


if __name__ == "__main__": unittest.main()
