# SPDX-License-Identifier: LGPL-3.0-or-later
"""Snapshot the GTNH wiki into one searchable SQLite file for offline use by the agent.

The wiki's text is CC BY-SA 4.0 and is not part of this repository: this script
fetches it through the MediaWiki API (the route its robots.txt allows) into
.runtime/wiki/gtnh-wiki.sqlite, which the wiki tools read. Every page keeps its
revision id and URL so answers can be attributed. Run it on the host; the agent
container has no route to the wiki and mounts the file read-only.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import time
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://wiki.gtnewhorizons.com/w/api.php"
PAGE = "https://wiki.gtnewhorizons.com/wiki/"
DEFAULT = Path(__file__).resolve().parents[2] / ".runtime" / "wiki" / "gtnh-wiki.sqlite"
AGENT = "ModdedBench-wiki-cache/0.1 (offline research snapshot)"


def api(**params):
    query = urllib.parse.urlencode({"format": "json", "formatversion": 2, "maxlag": 5, **params})
    for attempt in range(5):
        with urllib.request.urlopen(urllib.request.Request(f"{API}?{query}", headers={"User-Agent": AGENT}), timeout=60) as reply:
            data = json.load(reply)
        if data.get("error", {}).get("code") != "maxlag": return data
        time.sleep(5 * (attempt + 1))
    raise RuntimeError("the wiki stayed lagged; try again later")


def pages(**params):
    """Follow API continuation, one polite request per second."""
    cont = {}
    while True:
        data = api(action="query", **params, **cont)
        if "error" in data: raise RuntimeError(data["error"])
        yield data.get("query", {})
        if "continue" not in data: return
        cont = data["continue"]; time.sleep(1)


def clean(text: str) -> str:
    """Wikitext stays (tables and templates carry the recipes); only noise that costs tokens goes."""
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", "", text, flags=re.S)
    text = re.sub(r"\[\[(?:File|Image|Category):[^\[\]]*(?:\[\[[^\]]*\]\][^\[\]]*)*\]\]", "", text, flags=re.I)
    text = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"'{2,}", "", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT)
    out = parser.parse_args().out
    out.parent.mkdir(parents=True, exist_ok=True)
    fresh = out.with_suffix(".tmp"); fresh.unlink(missing_ok=True)
    db = sqlite3.connect(fresh)
    db.executescript("""
        create table meta(key text primary key, value text);
        create table page(title text primary key, revid integer, touched text, text text);
        create table alias(name text primary key collate nocase, title text);
        create virtual table search using fts5(title, text, tokenize='porter unicode61');
    """)
    info = api(action="query", meta="siteinfo", siprop="general|rightsinfo")["query"]
    count = 0
    for batch in pages(generator="allpages", gapnamespace=0, gapfilterredir="nonredirects", gaplimit=50,
                       prop="revisions", rvprop="ids|timestamp|content", rvslots="main"):
        for page in batch.get("pages", []):
            rev = (page.get("revisions") or [{}])[0]
            text = clean(rev.get("slots", {}).get("main", {}).get("content", ""))
            if not text: continue
            db.execute("insert or replace into page values(?,?,?,?)", (page["title"], rev.get("revid"), rev.get("timestamp"), text))
            db.execute("insert into search(title, text) values(?,?)", (page["title"], text)); count += 1
        print(f"\r{count} pages", end="", flush=True)
    for batch in pages(list="allredirects", arnamespace=0, arlimit=500, arprop="title|ids"):
        ids = [str(r["fromid"]) for r in batch.get("allredirects", [])]
        for start in range(0, len(ids), 50):
            named = {p["pageid"]: p["title"] for p in api(action="query", pageids="|".join(ids[start:start + 50]))["query"]["pages"]}
            for r in batch["allredirects"][start:start + 50]:
                if r["fromid"] in named: db.execute("insert or ignore into alias values(?,?)", (named[r["fromid"]], r["title"]))
            time.sleep(1)
    db.executemany("insert into meta values(?,?)", {
        "source": PAGE, "license": info["rightsinfo"]["text"], "generator": info["general"]["generator"],
        "fetched": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "pages": str(count)}.items())
    db.commit(); db.execute("vacuum"); db.close()
    fresh.replace(out)
    print(f"\n{out} ({out.stat().st_size >> 10} KiB, {count} pages)")


if __name__ == "__main__":
    main()
