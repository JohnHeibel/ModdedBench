# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline GTNH wiki: search and read a snapshot made by harness/wiki/fetch.py. Never touches Minecraft or the network.

The snapshot is MB_WIKI_DB (default <repo>/.runtime/wiki/gtnh-wiki.sqlite). Its text is
CC BY-SA 4.0, so every result carries the page URL and revision.
"""
from __future__ import annotations

from contextlib import closing
import os
from pathlib import Path
import re
import sqlite3
from typing import Any

from mbtool import tool

DEFAULT = Path(__file__).resolve().parents[2] / ".runtime" / "wiki" / "gtnh-wiki.sqlite"


def _db() -> sqlite3.Connection:
    path = Path(os.environ.get("MB_WIKI_DB") or DEFAULT)
    if not path.is_file(): raise FileNotFoundError(f"no wiki snapshot at {path}; the operator makes one with python harness/wiki/fetch.py")
    return sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)


def _source(db, title: str, revid: Any) -> dict:
    meta = dict(db.execute("select key, value from meta"))
    return {"url": meta["source"] + title.replace(" ", "_"), "revision": revid, "fetched": meta["fetched"], "license": meta["license"]}


@tool(lane="read", coverage=["meta"])
def mb_wiki_search(query: str, limit: int = 8) -> Any:
    """Search the offline GTNH wiki snapshot (full text, title matches first). Returns titles with snippets; read one with mb_wiki_read.

    Plain words are ANDed; fewer, more specific words find more. The wiki explains
    progression, multiblocks, mechanics, ore and bee guides: what to build next and
    why. It follows the newest pack version and can be ahead of this pack, so NEI
    (mb_recipes) and the quest book win on exact recipes and requirements.
    """
    words = re.findall(r"\w+", query)
    if not words: raise ValueError("query needs at least one word")
    with closing(_db()) as db:
        rows = db.execute("select title, snippet(search, 1, '', '', ' … ', 24) from search where search match ? order by bm25(search, 8.0, 1.0) limit ?",
                          (" ".join(f'"{w}"' for w in words), max(1, min(limit, 25)))).fetchall()
        alias = db.execute("select title from alias where name = ?", (query.strip(),)).fetchone()
        exact = db.execute("select title, substr(text, 1, 160) from page where title = ? collate nocase", (query.strip(),)).fetchone()
    if exact: rows = [exact, *(r for r in rows if r[0] != exact[0])]  # a page named exactly like the query comes first
    return {"results": [{"title": t, "snippet": s} for t, s in rows], **({"redirect": alias[0]} if alias else {})}


@tool(lane="read", coverage=["meta"])
def mb_wiki_read(title: str, section: str = "", offset: int = 0, limit: int = 6000) -> Any:
    """Read a wiki page from the offline snapshot as wikitext. Titles and redirects match case-insensitively.

    A page longer than limit (500..20000 characters) answers first with its lead and an
    outline of every section and its size: read the sections you need, which are rarely
    the first table. With section (a heading from the outline, or an unambiguous part of
    one) you get that section only. Keep reading with offset=next. Tables and {{templates}} are raw
    wikitext; they hold the multiblock and recipe details.
    """
    with closing(_db()) as db:
        row = db.execute("select title, revid, text from page where title = ? collate nocase", (title.strip().replace("_", " "),)).fetchone()
        if row is None:
            alias = db.execute("select title from alias where name = ?", (title.strip().replace("_", " "),)).fetchone()
            if alias: row = db.execute("select title, revid, text from page where title = ?", (alias[0],)).fetchone()
        if row is None: raise ValueError(f"no page titled {title!r}; use mb_wiki_search")
        name, revid, text = row; source = _source(db, name, revid)
    heads = [(m.start(), m.group(2).strip(), len(m.group(1))) for m in re.finditer(r"^(={1,4})\s*(.+?)\s*\1\s*$", text, flags=re.M)]
    ends = [next((h[0] for h in heads[i + 1:] if h[2] <= head[2]), len(text)) for i, head in enumerate(heads)]  # subsections belong to it
    limit = max(500, min(limit, 20000)); offset = max(0, offset)
    if not section and not offset and len(text) > limit and heads:
        outline = [{"section": "  " * (h[2] - 1) + h[1], "chars": end - h[0]} for h, end in zip(heads, ends)]
        return {"title": name, "length": len(text), "note": "long page: read it by section", "outline": outline,
                "lead": text[:min(heads[0][0], limit)], "source": source}
    if section:
        want = section.strip().lower(); partial = [i for i, h in enumerate(heads) if want in h[1].lower()]
        at = next((i for i, h in enumerate(heads) if h[1].lower() == want), partial[0] if len(partial) == 1 else None)
        if at is None: raise ValueError(f"no section {section!r}; sections: {[h[1] for h in heads]}")
        text = text[heads[at][0]:ends[at]]
    part = text[offset:offset + limit]
    out = {"title": name, "sections": [h[1] for h in heads], "text": part, "length": len(text), "source": source}
    if offset + limit < len(text): out["next"] = offset + limit
    return out
