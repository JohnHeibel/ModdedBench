# SPDX-License-Identifier: LGPL-3.0-or-later
"""Turns a run's logs into a folder a cheap model can search to answer viewers' questions (harness/asker/ask.py).

The loop's log (codex-loop.log) is every Codex event, 270 MB of it, mostly tool results. This writes the same history as
one short line per event with the time it happened, an hour to a file, and beside it: ``timeline.md`` (the run hour by
hour: goals, quests claimed, notes written), ``notes.md`` (the agent's journal as it stands), ``now.md`` (the present
moment). look.py searches it. Nothing here is shown to the playing agent.

    python harness/asker/corpus.py --log codex-loop.log --feed feed.jsonl --notes notes/ --out corpus/

Events carry no time of their own. The feed does (it is written as they arrive), so remarks and reasoning summaries
found in both are the clock, with the loop's ``# <time>`` lines; events between two of those are spread evenly.
"""
import argparse, json, re, shutil, sqlite3, sys, tempfile, time
from calendar import timegm
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runner"))
from feed import line as told  # the overlay's plain-words line for a tool call

flat = lambda s, n: re.sub(r"\s+", " ", re.sub("§.", "", str(s))).strip()[:n]
stamp = lambda t: time.strftime("%m-%d %H:%M", time.gmtime(t))


def events(log, since):
    """(time or None, event) from the first loop start at or after `since`; a '#' line becomes {'type': '#', 'text': ...} with its time."""
    on = False
    with open(log, encoding="utf-8", errors="replace") as f:
        for raw in f:
            if raw.startswith("#"):
                m = re.match(r"# (\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d) (.*)", raw)
                if not m: continue
                t = timegm(time.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S")); on = on or t >= since
                if on: yield t, {"type": "#", "text": m.group(2).strip()}
            elif on:
                try: e = json.loads(raw)
                except ValueError: continue
                if isinstance(e, dict) and (e.get("type") == "item.completed" and isinstance(e.get("item"), dict)): yield None, e


def timed(stream, feed):
    """Gives every event a time: its own, or the feed's for the same remark or reasoning summary, or its place between the two nearest that have one."""
    said = {}
    for f in feed:
        if f.get("kind") in ("say", "think"): said.setdefault(f["text"][:200], []).append(f["ts"])
    rows, last = [], 0.0
    for t, e in stream:
        item = e.get("item") or {}
        if t is None and item.get("type") == "agent_message": key = re.sub("§.", "", str(item.get("text", "")).strip()[:600])[:200]
        elif t is None and item.get("type") == "reasoning": key = (flat(re.sub(r"\*\*(.+?)\*\*", "", str(item.get("text") or "")), 700) or "\0")[:200]
        else: key = None
        while key and said.get(key):  # the same words twice: the first time not before the last one known
            t = said[key].pop(0)
            if t >= last: break
            t = None
        if t is not None: last = max(last, t)
        rows.append([t, e])
    known = [i for i, (t, _) in enumerate(rows) if t is not None]
    for a, b in zip(known, known[1:]):
        for i in range(a + 1, b): rows[i][0] = rows[a][0] + (rows[b][0] - rows[a][0]) * (i - a) / (b - a)
    return [(t if t is not None else (rows[known[-1]][0] if known else 0.0), e) for t, e in rows[known[0] if known else 0:]]


def text_of(e):
    """One line for one event: KIND, then what a reader needs. None for nothing worth a line."""
    if e["type"] == "#":
        if "codex exec" not in e["text"]: return "STOP " + e["text"]
        model, effort = re.search(r"-m (\S+)", e["text"]), re.search(r'effort="?(\w+)', e["text"])
        return f"START {model.group(1) if model else ''} {effort.group(1) + ' effort' if effort else ''}".strip()
    item = e["item"]; what = item.get("type")
    if what == "agent_message": return "SAY " + flat(item.get("text", ""), 1500)
    if what == "reasoning":
        text = str(item.get("text") or ""); title = (re.findall(r"\*\*(.+?)\*\*", text) or [""])[0]
        return f"THINK {title}: " + flat(re.sub(r"\*\*(.+?)\*\*", "", text), 2000) if text.strip() else None
    if what == "command_execution": return f"SHELL {flat(item.get('command', ''), 300)} => exit {item.get('exit_code')} {flat(item.get('aggregated_output', ''), 200)}"
    if what == "file_change": return "EDIT " + ", ".join(str(c.get("path")) for c in item.get("changes") or [])
    if what == "web_search": return "SEARCH " + flat(item.get("query", ""), 200)
    if what != "mcp_tool_call": return None
    tool, args = item.get("tool", ""), item.get("arguments") if isinstance(item.get("arguments"), dict) else {}
    try: body = item["result"]["content"][0]["text"]
    except (KeyError, IndexError, TypeError): body = ""
    try: result = json.loads(body); body = json.dumps(result, ensure_ascii=False, separators=(",", ":"))  # the result as the tool gave it, without the indentation
    except ValueError: result = {}
    failed = item.get("status") == "failed" or bool(item.get("error"))
    if not failed and isinstance(result, dict):
        if tool == "mb_goal" and any(args.values()) and "subgoal" in result:
            return "GOAL " + " | ".join(f"{k}={flat(result.get(k), 300)}" for k in ("chapter", "quest", "subgoal", "serves") if result.get(k))
        if tool == "mb_note_write" and result.get("saved") and isinstance(result.get("note"), dict):
            return f"NOTE {flat(result['note'].get('title'), 120)}: {flat(result['note'].get('text'), 900)}"
        if tool == "mb_quest_claim" and (result.get("claimed") or result.get("accepted")):
            return "CLAIM " + flat((result.get("quest") or {}).get("name") or args.get("quest_id"), 120)
    try: plain = told(tool, args, result if isinstance(result, dict) else {})
    except Exception: plain = ""
    why = result.get("error") if isinstance(result, dict) and isinstance(result.get("error"), dict) else {}
    tail = f"{why.get('code')}: {flat(why.get('message'), 300)}" if why else flat(item.get("error") or body, 300 if failed else 220)
    return f"{'FAIL' if failed else 'CALL'} {tool} {flat(json.dumps(args, ensure_ascii=False, separators=(',', ':')), 400)} => {plain + ' | ' if plain else ''}{tail}"


def notes_md(folder):
    """The journal as it stands, newest first: what the agent chose to write down, which is where its plans and reasons are."""
    out, snaps = [], []
    with tempfile.TemporaryDirectory() as tmp:  # read from a copy: SQLite cannot open a database with a write-ahead log in a folder it may not write to
        for db in sorted(Path(folder).glob("*.sqlite3")):
            for part in Path(folder).glob(db.name + "*"): shutil.copy(part, tmp)
            with closing(sqlite3.connect(Path(tmp) / db.name)) as con: snaps += [snap for (snap,) in con.execute("select snapshot from notes")]
    for n in map(json.loads, snaps):
        if n.get("status") == "archived" or not (n.get("title") or n.get("text")): continue
        where = "; ".join(flat(json.dumps({k: v for k, v in a.items() if k != "kind"}, ensure_ascii=False), 120) for a in n.get("attachments") or [] if isinstance(a, dict))
        out.append((str(n.get("updatedAt") or n.get("createdAt") or ""), f"## {flat(n.get('title'), 160)}\n({n.get('status')}, updated {str(n.get('updatedAt') or '')[:16]}{', ' + where if where else ''})\n{str(n.get('text') or '').strip()[:3000]}\n"))
    return "# The AI's journal, newest first\n\n" + "\n".join(text for _, text in sorted(out, reverse=True))


def build(log, feed, notes, out, since=None):
    out = Path(out); (out / "transcript").mkdir(parents=True, exist_ok=True)
    feed = [json.loads(x) for x in Path(feed).read_text(encoding="utf-8").splitlines() if x.strip()]
    rows = timed(events(log, since if since is not None else (feed[0]["ts"] - 60 if feed else 0)), feed)
    hours, lines = {}, []
    for t, e in rows:
        text = text_of(e)
        if text: lines.append((t, text)); hours.setdefault(time.strftime("%m-%d_%H", time.gmtime(t)), []).append(f"{stamp(t)} {text}")
    for old in (out / "transcript").glob("*.txt"): old.unlink()
    for hour, body in hours.items(): (out / "transcript" / f"{hour}.txt").write_text("\n".join(body) + "\n", encoding="utf-8")
    # The timeline: per hour, what changed in its goals, what it claimed and what it wrote down. It is the map for a search.
    tl, quest = ["# The run, hour by hour (UTC)\n"], None
    for hour, body in hours.items():
        keep = []
        for row in body:
            kind, _, rest = row[12:].partition(" ")
            if kind == "GOAL":
                q = re.search(r"quest=([^|]*)", rest); sub = re.search(r"subgoal=([^|]*)", rest)
                if q and q.group(1).strip() != quest: quest = q.group(1).strip(); keep.append(f"{row[6:11]} quest: {quest}")
                if sub: keep.append(f"{row[6:11]} goal: {sub.group(1).strip()[:140]}")
            elif kind == "CLAIM": keep.append(f"{row[6:11]} CLAIMED quest: {rest}")
            elif kind == "NOTE": keep.append(f"{row[6:11]} wrote note: {rest.split(':')[0][:100]}")
            elif kind in ("START", "STOP"): keep.append(f"{row[6:11]} run {kind.lower()}: {rest[:80]}")
        calls = sum(r[12:16] in ("CALL", "FAIL") for r in body); failed = sum(r[12:16] == "FAIL" for r in body)
        tl.append(f"## {hour.replace('_', ' ')}:00  ({calls} actions, {failed} failed)\n" + "\n".join(keep) + "\n")
    (out / "timeline.md").write_text("\n".join(tl), encoding="utf-8")
    try: (out / "notes.md").write_text(notes_md(notes) if notes else "", encoding="utf-8")
    except sqlite3.Error: (out / "notes.md").touch()  # copied in the middle of a write: the last journal stands
    goal = next((text for _, text in reversed(lines) if text.startswith("GOAL ")), "GOAL (none set)")
    (out / "now.md").write_text(f"# Now\nIt is {stamp(lines[-1][0])} UTC ({time.strftime('%A', time.gmtime(lines[-1][0]))}). The run began {stamp(lines[0][0])} UTC.\n"
                                f"Its goal stack: {goal[5:]}\n\n## Its last actions, oldest first\n" + "\n".join(f"{stamp(t)} {text[:400]}" for t, text in lines[-40:]) + "\n", encoding="utf-8")
    return {"events": len(lines), "hours": len(hours), "from": stamp(lines[0][0]), "to": stamp(lines[-1][0]), "bytes": sum(p.stat().st_size for p in (out / "transcript").glob("*.txt"))}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--log", required=True); p.add_argument("--feed", required=True); p.add_argument("--notes", default=""); p.add_argument("--out", required=True)
    p.add_argument("--since", default=None, help="UTC time to start from, YYYY-MM-DDTHH:MM:SS; the feed's first line by default")
    a = p.parse_args()
    print(json.dumps(build(a.log, a.feed, a.notes, a.out, timegm(time.strptime(a.since, "%Y-%m-%dT%H:%M:%S")) if a.since else None)))
