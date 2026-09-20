# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline tests for the operator console: the prompt it writes and who may press its buttons."""
from __future__ import annotations
import json, sys, threading, unittest, urllib.error, urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "harness" / "console"))
import console


class ConsoleTests(unittest.TestCase):
    def test_the_shipped_prompt_has_every_placeholder_the_console_fills(self):
        text = console.fill_prompt((REPO / "PROMPT.md").read_text(encoding="utf-8"), "Steam Macerator", "Tier 0.5 - Steam Age")
        self.assertIn('TARGET_QUEST      = "Steam Macerator"', text); self.assertIn('REPO              = "/work/modbench"', text)
        self.assertNotRegex(text, r'(?m)^(TARGET_|WORLD|REPO)\w*\s*=\s*"<')
        for bad in ("", 'a"b', "a\nb"):
            with self.assertRaises(ValueError): console.fill_prompt("x", bad, "chapter")

    def test_actions_need_the_page_token_and_a_loopback_host(self):
        class Fake:
            def act(self, name, args): return {"accepted": name}
        console.Handler.console = Fake()
        server = ThreadingHTTPServer(("127.0.0.1", 0), console.Handler); threading.Thread(target=server.serve_forever, daemon=True).start()
        def post(headers):
            req = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/api/action", data=b'{"name":"server.stop"}', headers=headers)
            try: return urllib.request.urlopen(req, timeout=5).status
            except urllib.error.HTTPError as e: return e.code
        try:
            self.assertEqual(post({}), 403)
            self.assertEqual(post({"X-Console-Token": console.TOKEN, "Host": "evil.example"}), 403)
            self.assertEqual(post({"X-Console-Token": console.TOKEN}), 200)
        finally: server.shutdown(); server.server_close()


if __name__ == "__main__":
    unittest.main()
