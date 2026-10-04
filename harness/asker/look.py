# SPDX-License-Identifier: LGPL-3.0-or-later
"""The four searches the model that answers viewers has, over the folder corpus.py makes. They are its only tools.

    python look.py serve --corpus DIR                           the tools, as an MCP server on stdio (what ask.py starts)
    python look.py find copper smelter --corpus DIR             the same searches by hand, to check an answer
        --any  --kind THINK,SAY,GOAL,NOTE  --from 10-02_14 --to 10-03_02
    python look.py at "10-03 14:02" --minutes 10 --corpus DIR
    python look.py hours 10-02_14 10-02_20 --corpus DIR
    python look.py notes coke oven --corpus DIR

Output is capped so one search never floods the reader: many matches show the first and the last ones and say how many are between.
"""
import argparse, sys, time
from calendar import timegm
from pathlib import Path

ROOT = Path(".")
kind_of = lambda row: row[12:].split(" ", 1)[0]
kinds_of = lambda kinds: {k.strip().upper() for k in kinds.split(",") if k.strip()}


def rows(lo="", hi="~"):
    for f in sorted((ROOT / "transcript").glob("*.txt")):
        if lo <= f.stem <= hi:
            with open(f, encoding="utf-8") as fh: yield from (line.rstrip("\n") for line in fh)


def aged(shown):
    """The rows with how long ago each was, counted from the last thing logged, so nobody has to subtract dates."""
    last = max((f for f in (ROOT / "transcript").glob("*.txt")), default=None); year = time.gmtime().tm_year
    when = lambda row: timegm(time.strptime(f"{year}-{row[:11]}", "%Y-%m-%d %H:%M"))
    now = when(last.read_text(encoding="utf-8").splitlines()[-1]) if last else 0
    def age(row):
        mins = (now - when(row)) // 60
        return f"{mins} min ago" if mins < 90 else f"{round(mins / 60)} h ago" if mins < 2880 else f"{round(mins / 1440)} days ago"
    return [f"{r[:11]} ({age(r)}){r[11:]}" if r[:2].isdigit() else r for r in shown]


def find(words: str, any_word: bool = False, kinds: str = "", start: str = "", end: str = "", most: int = 40) -> str:
    """Search everything the AI did for lines containing the words (any case), oldest first.

    words: space-separated; a line must contain every one (any_word=true: at least one). Use word stems: "boiler", not "boilers".
    kinds: comma-separated line kinds to keep, e.g. "THINK,SAY,GOAL,NOTE" (where reasons are). Empty keeps all: also CALL, FAIL, CLAIM, SHELL, EDIT.
    start, end: only these hours, "MM-DD_HH" in UTC, e.g. start="10-02_14", end="10-03_02".
    most: how many lines to show, up to 80. More matches than that show the first third and the last two thirds.
    """
    want, keep, test = words.lower().split(), kinds_of(kinds), any if any_word else all
    hits = [r for r in rows(start, end or "~") if (not keep or kind_of(r) in keep) and test(w in r.lower() for w in want)]
    cap = max(4, min(most, 80)); head = cap // 3
    shown = hits if len(hits) <= cap else hits[:head] + [f"... {len(hits) - cap} more between; narrow with start/end, kinds or more words ..."] + hits[-(cap - head):]
    return f"{len(hits)} lines match\n" + "\n".join(aged(r[:420] for r in shown))


def at(when: str, minutes: int = 5, kinds: str = "") -> str:
    """Everything the AI did within `minutes` of a moment, in order, with longer lines than find shows. when: "MM-DD HH:MM" in UTC. kinds: as in find."""
    t = timegm(time.strptime(f"{time.gmtime().tm_year}-{when.strip()}", "%Y-%m-%d %H:%M")); keep = kinds_of(kinds)
    stamp = lambda s: time.strftime("%m-%d %H:%M", time.gmtime(s)); lo, hi = stamp(t - minutes * 60), stamp(t + minutes * 60)
    near = [r for r in rows(lo[:5] + "_" + lo[6:8], hi[:5] + "_" + hi[6:8]) if lo <= r[:11] <= hi and (not keep or kind_of(r) in keep)]
    if len(near) > 70: near = near[:35] + [f"... {len(near) - 70} more lines; use fewer minutes or kinds ..."] + near[-35:]
    return "\n".join(aged(r[:700] for r in near)) or "nothing logged then"


def hours(start: str, end: str = "") -> str:
    """The run hour by hour between two hours ("MM-DD_HH", UTC; end defaults to start): the goals it set, the quests it claimed, the notes it wrote. The map for a search: at most 12 hours at once."""
    lo, hi = start[:8].replace("_", " "), (end or start)[:8].replace("_", " ")
    parts = [s for s in (ROOT / "timeline.md").read_text(encoding="utf-8").split("\n## ")[1:] if lo <= s[:8] <= hi]
    return "\n".join("## " + s.strip()[:1500] for s in parts[:12]) or "nothing logged in those hours"


def notes(words: str, any_word: bool = False) -> str:
    """Search the AI's journal (its plans, places and lessons, as they stand now) for pages containing the words. Newest first, the start of each page."""
    want, test = words.lower().split(), any if any_word else all
    pages = [s for s in (ROOT / "notes.md").read_text(encoding="utf-8").split("\n## ")[1:] if test(w in s.lower() for w in want)]
    return f"{len(pages)} journal pages match" + ("; the 8 newest:" if len(pages) > 8 else "") + "\n" + "\n\n".join("## " + s.strip()[:900] for s in pages[:8])


def main():
    global ROOT
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(); sub = p.add_subparsers(dest="cmd", required=True); each = argparse.ArgumentParser(add_help=False); each.add_argument("--corpus", default=".")
    sub.add_parser("serve", parents=[each])
    f = sub.add_parser("find", parents=[each]); f.add_argument("words", nargs="+"); f.add_argument("--any", action="store_true"); f.add_argument("--kind", default="")
    f.add_argument("--from", dest="lo", default=""); f.add_argument("--to", dest="hi", default=""); f.add_argument("--max", type=int, default=40)
    a = sub.add_parser("at", parents=[each]); a.add_argument("when"); a.add_argument("--minutes", type=int, default=5); a.add_argument("--kind", default="")
    h = sub.add_parser("hours", parents=[each]); h.add_argument("start"); h.add_argument("end", nargs="?", default="")
    n = sub.add_parser("notes", parents=[each]); n.add_argument("words", nargs="+"); n.add_argument("--any", action="store_true")
    o = p.parse_args(); ROOT = Path(o.corpus)
    if o.cmd == "serve":
        from mcp.server.fastmcp import FastMCP
        from mcp.types import ToolAnnotations
        server = FastMCP("logs")
        for tool in (find, at, hours, notes): server.tool(annotations=ToolAnnotations(readOnlyHint=True))(tool)  # read-only tools need no approval, which nobody is there to give
        server.run()
    elif o.cmd == "find": print(find(" ".join(o.words), o.any, o.kind, o.lo, o.hi, o.max))
    elif o.cmd == "at": print(at(o.when, o.minutes, o.kind))
    elif o.cmd == "hours": print(hours(o.start, o.end))
    else: print(notes(" ".join(o.words), o.any))


if __name__ == "__main__": main()
