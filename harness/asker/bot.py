# SPDX-License-Identifier: LGPL-3.0-or-later
"""The stream's chat bot: a viewer types `!ask why did it build that?` and gets ask.py's answer as a reply in chat.

    python harness/asker/bot.py --log codex-loop.log --feed feed.jsonl --notes notes/ --work DIR
        joins Twitch chat as MB_TWITCH_NICK in #MB_TWITCH_CHANNEL with MB_TWITCH_TOKEN (the bot account's chat token)
    python harness/asker/bot.py ... --console
        the same desk on stdin, no Twitch: lines of "name: !ask question"

Anyone may ask. One question is answered at a time, with a short line behind it, a wait per viewer and a cap per hour,
so what the answers cost is bounded by those four numbers whatever chat does. Chat is strangers' text: the bot never
repeats it, the model can only search the logs (ask.py), and what it says is cleaned before it is posted. Nothing
here reaches the playing agent or the game. Every question and answer is kept, with its evidence, in DIR/asks.jsonl.
"""
import argparse, json, os, queue, re, socket, ssl, sys, threading, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ask import ask  # noqa: E402
from corpus import build  # noqa: E402

HOST, LINE = "irc.chat.twitch.tv", re.compile(r"(?:@(\S+) )?:(\w+)!\S+ PRIVMSG #\w+ :(.*)")


class Desk:
    """Who may ask now. offer() takes a chat line; the questions it lets through wait in `line` for answer()."""

    def __init__(self, line=3, wait=120, hourly=20, clock=time.time):
        self.line, self.wait, self.hourly, self.clock = queue.Queue(line), wait, hourly, clock
        self.last, self.taken, self.told = {}, [], -1e9  # viewer -> when they last asked; when each question this hour was taken; when chat was last told no

    def offer(self, user, text, ref=None):
        """What to tell chat about this line, or None: for a line that is not a question, for a question taken, and for a refusal within a minute of the last one (so refusals cannot flood chat)."""
        m = re.match(r"!ask\s+(\S.{5,})", text.strip(), re.I)
        if not m: return None
        now = self.clock(); self.taken = [t for t in self.taken if now - t < 3600]; left = self.wait - (now - self.last.get(user, -1e9))
        no = (f"One question every {self.wait // 60} minutes each: {int(left) + 1} s to go." if left > 0 else
              "That is all the questions I can take this hour." if len(self.taken) >= self.hourly else
              "The line is full: ask again in a minute." if self.line.full() else None)
        if no:
            if now - self.told < 60: return None
            self.told = now; return no
        self.last[user] = now; self.taken.append(now); self.line.put((user, m.group(1)[:300], ref))
        return None


def heard(raw):
    """(viewer, text, message id) from one line of Twitch chat, or None for anything that is not a chat message."""
    m = LINE.match(raw)
    return m and (m.group(2).lower(), m.group(3).strip(), dict(t.split("=", 1) for t in (m.group(1) or "").split(";") if "=" in t).get("id"))


def answer(desk, say, paths, work, every=60):
    """One question at a time, for ever: bring the logs up to date, ask, post, keep the record."""
    corpus, built = work / "corpus", 0.0
    while True:
        user, question, ref = desk.line.get()
        try:
            if time.time() - built > every and Path(paths["log"]).stat().st_mtime > built: build(paths["log"], paths["feed"], paths["notes"], corpus); built = time.time()
        except Exception as e: print(f"logs: {e!r}", flush=True)  # away or half written: the last copy answers
        try: r = ask(question, corpus)
        except Exception as e: r = {"error": repr(e)}
        try: say(r.get("answer") or "I could not find that out from the logs.", ref)
        except OSError: pass  # chat dropped while it was thinking
        with open(work / "asks.jsonl", "a", encoding="utf-8") as f: f.write(json.dumps({"ts": time.time(), "user": user, "question": question, **r}, ensure_ascii=False) + "\n")
        desk.line.task_done()


def twitch(desk, box, channel, nick, token):
    """Twitch chat over IRC, for ever. box["say"](text, reply to) posts; a lost connection is made again, a refused login ends the bot."""
    pause, lock = 1, threading.Lock()
    while True:
        try:
            with ssl.create_default_context().wrap_socket(socket.create_connection((HOST, 6697), 30), server_hostname=HOST) as s:
                def send(text):
                    with lock: s.sendall((text.replace("\r", " ").replace("\n", " ") + "\r\n").encode())
                for text in ("CAP REQ :twitch.tv/tags", f"PASS oauth:{token.removeprefix('oauth:')}", f"NICK {nick}", f"JOIN #{channel}"): send(text)
                box["say"] = lambda text, ref=None: send((f"@reply-parent-msg-id={ref} " if ref else "") + f"PRIVMSG #{channel} :{text}")
                s.settimeout(600)  # Twitch pings every five minutes: ten of silence is a dead line
                for raw in s.makefile(encoding="utf-8", errors="replace"):
                    if raw.startswith("PING"): send("PONG" + raw[4:].rstrip()); pause = 1
                    elif "Login authentication failed" in raw or "Improperly formatted auth" in raw: sys.exit("Twitch refused the login: check MB_TWITCH_NICK and MB_TWITCH_TOKEN")
                    elif (m := heard(raw.rstrip())) and m[0] != nick.lower():
                        no = desk.offer(*m)
                        if no: box["say"](no, m[2])
        except OSError as e: print(f"chat: {e!r}; again in {pause} s", flush=True)
        time.sleep(pause); pause = min(pause * 2, 60)


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--log", required=True); p.add_argument("--feed", required=True); p.add_argument("--notes", default=""); p.add_argument("--work", required=True)
    p.add_argument("--console", action="store_true"); p.add_argument("--line", type=int, default=3); p.add_argument("--wait", type=int, default=120); p.add_argument("--hourly", type=int, default=20)
    a = p.parse_args(); work = Path(a.work); work.mkdir(parents=True, exist_ok=True); sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    desk, box = Desk(a.line, a.wait, a.hourly), {"say": lambda text, ref=None: print(f"> {text}", flush=True)}
    threading.Thread(target=answer, args=(desk, lambda text, ref=None: box["say"](text, ref), {"log": a.log, "feed": a.feed, "notes": a.notes}, work), daemon=True).start()
    if a.console:
        for raw in sys.stdin:
            user, _, text = raw.lstrip("﻿").partition(":"); no = desk.offer(user.strip().lower(), text)
            if no: box["say"](no)
        return desk.line.join()
    env = {k: os.environ.get(f"MB_TWITCH_{k}", "").strip() for k in ("CHANNEL", "NICK", "TOKEN")}
    if not all(env.values()): sys.exit("set MB_TWITCH_CHANNEL, MB_TWITCH_NICK and MB_TWITCH_TOKEN (the bot account's chat token), or pass --console")
    twitch(desk, box, env["CHANNEL"].lstrip("#").lower(), env["NICK"].lower(), env["TOKEN"])


if __name__ == "__main__": main()
