# SPDX-License-Identifier: LGPL-3.0-or-later
"""The offline wiki tools against a small snapshot with the fetcher's schema."""
from __future__ import annotations

import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "mcp"), str(ROOT / "tools"), str(ROOT / "wiki")]
import fetch
import wiki

PAGE = "Intro about bronze.\n== Building ==\nNeeds casings.\n=== Casings ===\nTwenty of them.\n== Usage ==\nFeed it steam."


class WikiTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(); self.addCleanup(self.dir.cleanup)
        path = Path(self.dir.name) / "wiki.sqlite"
        db = sqlite3.connect(path)
        db.executescript("""create table meta(key text primary key, value text);
            create table page(title text primary key, revid integer, touched text, text text);
            create table alias(name text primary key collate nocase, title text);
            create virtual table search using fts5(title, text, tokenize='porter unicode61');""")
        db.executemany("insert into meta values(?,?)", [("source", "https://wiki.example/wiki/"), ("fetched", "2026-09-20T00:00:00Z"), ("license", "CC BY-SA 4.0")])
        for title, text in (("Steam Grinder", PAGE), ("Windmill", "Grinds before steam exists.")):
            db.execute("insert into page values(?,?,?,?)", (title, 7, "", text)); db.execute("insert into search values(?,?)", (title, text))
        db.execute("insert into alias values('Grinder','Steam Grinder')"); db.commit(); db.close()
        env = patch.dict(os.environ, {"MB_WIKI_DB": str(path)}); env.start(); self.addCleanup(env.stop)

    def test_search_ranks_titles_first_and_names_redirects(self):
        found = wiki.mb_wiki_search("steam")
        self.assertEqual([r["title"] for r in found["results"]], ["Steam Grinder", "Windmill"])
        self.assertEqual(wiki.mb_wiki_search("grinder")["redirect"], "Steam Grinder")
        self.assertEqual(wiki.mb_wiki_search('casings" AND (')["results"], [])  # query text is never FTS syntax

    def test_read_follows_redirects_sections_and_paging(self):
        page = wiki.mb_wiki_read("grinder")
        self.assertEqual((page["title"], page["sections"]), ("Steam Grinder", ["Building", "Casings", "Usage"]))
        self.assertEqual(page["source"]["url"], "https://wiki.example/wiki/Steam_Grinder")
        building = wiki.mb_wiki_read("steam_grinder", section="building")["text"]
        self.assertIn("Twenty of them", building); self.assertNotIn("Feed it steam", building)
        with self.assertRaises(ValueError): wiki.mb_wiki_read("Nothing")

    def test_missing_snapshot_says_how_to_make_one(self):
        with patch.dict(os.environ, {"MB_WIKI_DB": str(Path(self.dir.name) / "absent.sqlite")}):
            with self.assertRaisesRegex(FileNotFoundError, "fetch.py"): wiki.mb_wiki_search("steam")

    def test_clean_drops_noise_and_keeps_tables(self):
        text = fetch.clean("<!-- x -->'''Bold''' [[Coke Oven|oven]] [[File:a.png|thumb|[[x]] cap]]<ref>r</ref>\n{| class=t\n| a\n|}")
        self.assertEqual(text, "Bold oven \n{| class=t\n| a\n|}")


if __name__ == "__main__":
    unittest.main()
