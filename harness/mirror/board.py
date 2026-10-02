# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The spectators' sidebar and agent chat lines, drawn by the relay from the overlay's public status.

A 1.7.10 sidebar line is a scoreboard entry, at most 16 characters, so each line is a team whose prefix and
suffix carry the text around an invisible entry name: about 30 visible characters. A 1.7.10 client drops the
connection on an objective or team that already exists, or on removing one that does not, so a viewer's board is
created exactly once per client world (a Respawn to another dimension starts a new one) and only updated after.
"""
from __future__ import annotations
import json, re, time
from .wire import frame, pack, string

OBJECTIVE, TITLE, ROWS = "mb_spectate", "§6§lModdedBench", 8
CODE = re.compile("§[0-9a-fk-or]")


def lines(status: dict, watching: int, now: float | None = None) -> list[str]:
    """The sidebar, top to bottom: ROWS lines of text with formatting codes."""
    s, now = status or {}, now or time.time()
    started = s.get("started")
    running = f"{int(now - started) // 3600}h {int(now - started) % 3600 // 60:02d}m" if started else "?"
    rows = [f"§7Model §f{s.get('model') or '?'}", f"§e{s.get('chapter') or ''}", f"§f{s.get('quest') or ''}", "",
            f"§7Quests claimed §a{s.get('claims') or 0}", f"§7Running §f{running}",
            f"§7Watching §f{watching}", "§8/invsee  /help"]
    return rows[:ROWS] + [""] * (ROWS - len(rows))


def halves(text: str) -> tuple[str, str]:
    """A line split into a team prefix and suffix of 16 characters each, the suffix re-opening the prefix's colour."""
    a = text[:16]
    if a.endswith("§"): a = a[:-1]
    codes = CODE.findall(a)
    b = (codes[-1] if codes else "") + text[len(a):]
    b = b[:16]
    return a, b[:-1] if b.endswith("§") else b


def entry(i: int) -> str: return f"§{i:x}§r"  # invisible, and unique per row
def team(i: int) -> str: return f"mb_spectate{i}"


def created(rows: list[str]) -> list[bytes]:
    out = [frame(0x3B, string(OBJECTIVE) + string(TITLE) + pack("b", 0))]
    for i, text in enumerate(rows):
        pre, suf = halves(text)
        out.append(frame(0x3E, string(team(i)) + pack("b", 0) + string("") + string(pre) + string(suf) + pack("b", 0)
                         + pack("h", 1) + string(entry(i))))
        out.append(frame(0x3C, string(entry(i)) + pack("b", 0) + string(OBJECTIVE) + pack("i", ROWS - i)))
    return out + [frame(0x3D, pack("b", 1) + string(OBJECTIVE))]


def updated(old: list[str], rows: list[str]) -> list[bytes]:
    out = []
    for i, (was, text) in enumerate(zip(old, rows)):
        if was == text: continue
        pre, suf = halves(text)
        out.append(frame(0x3E, string(team(i)) + pack("b", 2) + string("") + string(pre) + string(suf) + pack("b", 0)))
    return out


def news(host: str, e: dict) -> bytes:
    """One of the agent's own lines for the chat: what it says, and its milestones."""
    text = str(e.get("text") or "")[:600]
    if e.get("kind") == "say":
        parts = [{"text": host, "color": "gold"}, {"text": ": " + text, "color": "white"}]
    elif text.startswith("quest claimed: "):
        parts = [{"text": "Quest claimed: ", "color": "green"}, {"text": text[15:], "color": "green", "bold": True}]
    else:
        parts = [{"text": f"{host} {text}", "color": "gray", "italic": True}]
    return frame(0x02, string(json.dumps({"text": "", "extra": parts})))
