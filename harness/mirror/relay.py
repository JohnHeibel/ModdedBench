# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Spectator relay: python -m harness.mirror.relay (see docs/MIRROR.md).

Runs on a public machine. Viewers connect here, never to the agent's PC. One forwarder links in (authenticated,
one way) and this fans its frames out to every viewer. It keeps the last snapshot and the frames since, so a late
viewer catches up, and keeps the world on screen when the agent or the link goes away: viewers stay in a frozen
world with a notice, and the next session's world replaces it in place, without a reconnect or a loading screen
unless the dimension differs. The feed is mirrored to disk so a restarted relay comes back with the last world.
"""
from __future__ import annotations
import argparse, asyncio, collections, hmac, json, os, secrets, ssl, struct, time
from pathlib import Path
from . import board
from .link import END, HEAD, HEARTBEAT, INVENTORY, LIVE, MAGIC, MAX, NEWS, SNAPSHOT, STATUS, header, proof, token, unheaded
from .forwarder import clearing
from .proxy import Stage
from .state import Mirror
from .wire import Reader, Splitter, frame, pack, split, string, varint

FROZEN = "the agent is reconnecting; this view is frozen until it returns"
DROPPED = "the feed from the agent's PC dropped; this view is frozen until it is back"
BACK = "the agent is back"
WINDOW = 1          # the only window a viewer is ever shown: the agent's inventory
COMMANDS = {"/invsee": "the agent's inventory, live", "/help": "this list"}
CHAT_GAP = 1.5      # seconds between one viewer's chat lines


def notice(text: str) -> bytes:
    return frame(0x02, string(json.dumps({"text": f"[spectators] {text}", "color": "gray"})))


def said(name: str, text: str) -> bytes:
    return frame(0x02, string(json.dumps({"text": "", "extra": [
        {"text": "[spectator] ", "color": "dark_aqua"}, {"text": name, "color": "aqua"}, {"text": f": {text}", "color": "white"}]})))


class World:
    """One session as the relay holds it: the latest snapshot and the frames since, and once it has ended, the
    frames that empty it."""
    def __init__(self, meta: dict, entries: list[tuple[int, bytes]]):
        self.session, self.dim, self.host, self.prelude = meta["session"], meta["dim"], meta["host"], meta["prelude"]
        self.respawn, self.snap = [f for _, f in entries[:meta["respawn"]]], entries[meta["respawn"]:]
        self.tail, self.tail_bytes = [], 0
        self.clear, self.end_dim = None, None  # (world-scoped, tab list), once the session has ended

    def catch_up(self) -> list[tuple[int, bytes]]:
        return self.snap + [(0, f) for f in self.tail]

    def settle(self):
        """Works out what empties a viewer's copy of this world from the frames the relay sent, for a session whose
        own clearing frames never came (the forwarder restarted without ending it)."""
        m = Mirror(); m.login("", self.host)
        for _, data in self.catch_up():
            for f in Splitter().feed(data):
                try: m.server(f, *split(f))
                except Exception: pass  # best effort: what it cannot follow it leaves on screen
        self.clear, self.end_dim = clearing(m), m.dim

    def into(self, old: "World") -> list[bytes]:
        """What moves a viewer who watched old (now cleared) into this world without a new login."""
        world, tab = old.clear
        return (self.respawn if old.end_dim != self.dim else world) + tab + [f for _, f in self.snap[self.prelude:]]


class Relay(Stage):
    motd = "ModdedBench spectators"

    def __init__(self, secret: bytes, state: Path | None = None, max_viewers: int = 50, viewer_queue: int = 64 << 20,
                 tail_cap: int = 512 << 20):
        super().__init__(max_viewers, viewer_queue)
        self.secret, self.state, self.tail_cap = secret, state, tail_cap
        self.world, self.link, self.log = None, None, None
        self.inventory = None  # (window title, the agent's inventory as 45 wire slots)
        self.status, self.news = {}, collections.deque(maxlen=3)  # the sidebar's status; the agent's latest lines
        self.dim = None  # the dimension the viewers' clients are in, to know when they start a new client world

    # ---- viewers
    def refuse(self, name):
        if self.world is None: return "the spectator feed has not started yet; try again shortly"
        if name.lower() == self.world.host.lower(): return "that name is the agent's; pick another"

    def welcome(self, v):
        v.invsee, v.spoke, v.board = False, 0.0, None
        v.hold(self.world.catch_up())
        v.send(list(self.news) + [notice(f"you are watching {self.world.host}. Chat here reaches other spectators only; /invsee shows its inventory")])
        self.draw(v, len(self.viewers) + 1)
        if self.world.clear is not None: v.send([notice(FROZEN)])
        elif self.link is None: v.send([notice(DROPPED)])

    def heard(self, v, pid, body):
        """Viewers talk to each other and to the relay, never to the game: nothing here goes anywhere but viewers."""
        r = Reader(body)
        if pid == 0x01: self.chat(v, r.string()[:100])
        elif pid == 0x14:
            typed = r.string()[:100].lower()
            matches = [c for c in COMMANDS if typed.startswith("/") and c.startswith(typed)]
            v.send([frame(0x3A, varint(len(matches)) + b"".join(map(string, matches)))])
        elif pid == 0x0E and r.u("b") == WINDOW and v.invsee:  # a click: undo whatever the client predicted
            v.send([frame(0x2F, pack("bh", -1, -1) + pack("h", -1)), self.window_items()])
        elif pid == 0x0D and r.u("b") == WINDOW: v.invsee = False

    def chat(self, v, text):
        text = text.replace("\u00a7", "").strip()
        if not text: return
        if text.startswith("/"):
            command = text.split()[0].lower()
            if command in ("/invsee", "/inv"): return self.invsee(v)
            if command == "/help": return v.send([notice(f"{c}: {what}") for c, what in COMMANDS.items()])
            return v.send([notice(f"no command {command} here; try /help")])
        now = time.monotonic()
        if now - v.spoke < CHAT_GAP: return v.send([notice("a little slower, please")])
        v.spoke = now
        self.broadcast([said(v.name, text)])

    def invsee(self, v):
        if self.inventory is None: return v.send([notice("the agent's inventory has not arrived yet")])
        v.invsee = True
        v.send([frame(0x2D, pack("BB", WINDOW, 0) + string(self.inventory[0]) + pack("B?", 45, True)), self.window_items()])

    def draw(self, v, watching: int | None = None):
        """Brings v's sidebar up to date: created once per client world (v.board None), then only changed rows."""
        rows = board.lines(self.status, len(self.viewers) if watching is None else watching)
        out = board.created(rows) if v.board is None else board.updated(v.board, rows)
        v.board = rows
        if out: v.send(out)

    def moved(self, frames: list[bytes]) -> bool:
        """Follows the viewers' dimension through frames about to reach them; True if their clients start a new world
        (a Respawn to another dimension), which forgets the sidebar."""
        new = False
        for data in frames:
            for f in Splitter().feed(data):
                pid, body = split(f)
                if pid == 0x01: self.dim = Reader(body).u("iBb")[2]
                elif pid == 0x07:
                    dim = Reader(body).u("i")
                    new |= dim != self.dim; self.dim = dim
        return new

    def draw_all(self, new_world: bool = False):
        for v in self.viewers:
            if new_world: v.board = None
            self.draw(v)

    async def ticker(self, every: float = 15.0):
        while True:
            await asyncio.sleep(every)
            self.draw_all()

    def window_items(self) -> bytes:
        return frame(0x30, pack("Bh", WINDOW, 45) + self.inventory[1])

    # ---- the feed
    def handle(self, kind: int, payload: bytes):
        w = self.world
        if kind == SNAPSHOT:
            meta, entries = unheaded(payload)
            if w and w.session == meta["session"] and w.clear is None:  # a refresh: viewers already have all this
                w.snap, w.respawn = entries[meta["respawn"]:], [f for _, f in entries[:meta["respawn"]]]
                w.tail, w.tail_bytes = [], 0
            else:
                new = World(meta, entries)
                if self.viewers and w:
                    if w.clear is None: w.settle()
                    moved = new.into(w)
                    self.broadcast(moved + [notice(BACK)])
                    if self.moved(moved): self.draw_all(new_world=True)
                else: self.moved([f for _, f in new.snap])  # where a viewer joining now will be
                self.world = new
        elif kind == LIVE and w and w.clear is None:
            w.tail.append(payload); w.tail_bytes += len(payload)
            self.broadcast([payload])
            if self.moved([payload]): self.draw_all(new_world=True)
            if w.tail_bytes > self.tail_cap:
                self.kick("the spectator feed overflowed; rejoin shortly"); self.world = None
        elif kind == INVENTORY:
            meta, at = header(payload)
            self.inventory = (meta["title"][:32], payload[at:])
            f = self.window_items()
            for v in self.viewers:
                if getattr(v, "invsee", False): v.send([f])
        elif kind == STATUS:
            self.status = json.loads(payload); self.draw_all()
        elif kind == NEWS:
            line = board.news(w.host if w else "the agent", json.loads(payload))
            self.news.append(line); self.broadcast([line])
        elif kind == END and w and w.clear is None:
            meta, entries = unheaded(payload)
            if meta["session"] != w.session: return
            frames = [f for _, f in entries]
            w.clear, w.end_dim = (frames[:meta["world"]], frames[meta["world"]:]), meta["dim"]
            self.broadcast([notice(FROZEN)])

    def keep(self, kind: int, payload: bytes):
        """Mirror the feed to disk from its latest snapshot on, so a restart can come back with the same world."""
        if self.state is None or kind == HEARTBEAT: return
        msg = HEAD.pack(kind, len(payload)) + payload
        if kind == SNAPSHOT:
            if self.log: self.log.close()
            tmp = self.state / "feed.tmp"
            tmp.write_bytes(msg); os.replace(tmp, self.state / "feed.bin")
            self.log = open(self.state / "feed.bin", "ab", buffering=0)
        elif self.log: self.log.write(msg)

    def restore(self):
        if self.state is None: return
        self.state.mkdir(parents=True, exist_ok=True)
        path = self.state / "feed.bin"
        if not path.exists(): return
        data, pos = path.read_bytes(), 0
        while pos + HEAD.size <= len(data):
            kind, n = HEAD.unpack_from(data, pos)
            if pos + HEAD.size + n > len(data): break  # a write the last run did not finish
            self.handle(kind, data[pos + HEAD.size:pos + HEAD.size + n]); pos += HEAD.size + n
        with open(path, "r+b") as f: f.truncate(pos)
        self.log = open(path, "ab", buffering=0)
        if self.world: print(f"relay: restored session {self.world.session} from disk", flush=True)

    async def linker(self, r: asyncio.StreamReader, w: asyncio.StreamWriter):
        nonce = secrets.token_bytes(32)
        try:
            w.write(MAGIC + nonce); await w.drain()
            if not hmac.compare_digest(await asyncio.wait_for(r.readexactly(32), 10), proof(self.secret, nonce)):
                print("relay: refused a link with a wrong token", flush=True); return w.close()
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError, OSError, ssl.SSLError):
            return w.close()
        if self.link: self.link.close()  # the newest authenticated link wins
        self.link = w
        print("relay: forwarder linked", flush=True)
        try:
            while self.link is w:
                kind, n = HEAD.unpack(await asyncio.wait_for(r.readexactly(HEAD.size), 30))  # heartbeats every 5 s
                if n > MAX: break
                payload = await r.readexactly(n)
                if self.link is not w: break
                self.handle(kind, payload); self.keep(kind, payload)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError, OSError, ssl.SSLError, ValueError, struct.error) as e:
            print(f"relay: link ended: {e!r}", flush=True)
        finally:
            if self.link is w:
                self.link = None
                if self.world and self.world.clear is None: self.broadcast([notice(DROPPED)])
            w.close()

    async def run(self, viewers, link, tls: ssl.SSLContext | None):
        self.restore()
        ls = await asyncio.start_server(self.linker, *link, ssl=tls)
        vs = await asyncio.start_server(self.viewer, *viewers)
        print(f"relay: viewers on {viewers[0]}:{vs.sockets[0].getsockname()[1]}, "
              f"link on {link[0]}:{ls.sockets[0].getsockname()[1]}{'' if tls else ' (plain)'}", flush=True)
        await asyncio.gather(vs.serve_forever(), ls.serve_forever(), self.keepalive(), self.ticker())


def main():
    from .__main__ import address
    from .forwarder import _loopback
    p = argparse.ArgumentParser(prog="python -m harness.mirror.relay", description=__doc__)
    p.add_argument("--viewers", type=address, default=("0.0.0.0", 25565), help="where viewers connect (offline mode)")
    p.add_argument("--link", type=address, default=("0.0.0.0", 25591), help="where the forwarder links in")
    p.add_argument("--cert", help="TLS certificate for the link"); p.add_argument("--key", help="its private key")
    p.add_argument("--state", type=Path, default=Path("relay-state"), help="where the last world is kept across restarts")
    p.add_argument("--max-viewers", type=int, default=50)
    a = p.parse_args()
    tls = None
    if a.cert:
        tls = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH); tls.load_cert_chain(a.cert, a.key)
    elif not _loopback(a.link[0]):
        raise SystemExit("a link off loopback needs --cert and --key")
    relay = Relay(token(), a.state, a.max_viewers)
    try: asyncio.run(relay.run(a.viewers, a.link, tls))
    except KeyboardInterrupt: pass


if __name__ == "__main__":
    main()
