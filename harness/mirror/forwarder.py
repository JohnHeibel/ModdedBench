# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Spectator forwarder: python -m harness.mirror.forwarder (see docs/MIRROR.md).

Runs beside the agent's client. The client's tap (SpectatorTap.java) appends each game connection to a file; this
reads the newest from its first byte, keeps the Mirror, and dials out to the relay with viewer frames only. Reading
from the start means this can restart, or start late, without the client reconnecting. Nothing ever travels from
the relay back towards the game: the forwarder reads the relay's nonce and nothing after it, and the tap is one way.
"""
from __future__ import annotations
import argparse, asyncio, json, secrets, ssl, struct, zlib
from pathlib import Path
from .link import END, HEARTBEAT, INVENTORY, LIVE, MAGIC, NEWS, NONCE, SNAPSHOT, STATUS, headed, message, proof, token
from .proxy import Feed
from .state import SPECTATOR, Mirror
from .wire import Reader, frame, pack, payload, split, string

TAP_MAGIC = b"MBTAP1\n"
TAP = struct.Struct(">BII")  # kind, connection, length (SpectatorTap.java)
OPENED, CLOSED = 2, 3        # kinds 0 and 1 are the directions, the same numbers as proxy.C and proxy.S
TAP_RECORD = 32 << 20


def public(f: bytes) -> bool:
    """False for our own MB| plugin channels: they carry the harness's private traffic and never leave this PC."""
    r = Reader(f); r.varint()
    return r.varint() != 0x3F or not payload(f[r.pos:])[0].startswith("MB|")


def clearing(m: Mirror) -> tuple[list[bytes], list[bytes]]:
    """Frames that take a viewer who has watched this session back to an empty world: (world-scoped, tab list)."""
    z = zlib.compress(bytes(256))  # an unload is a full chunk with no sections: just the biome array
    chunks = set(m.chunks) | {c for e in m.log.values() for c, _ in e[1]}
    world = [frame(0x21, pack("ii?HHi", *c, True, 0, 0, len(z)) + z) for c in sorted(chunks)]
    eids = [eid for eid, e in m.ents.items() if e.spawn is not None] + ([m.eid] if m.avatar else [])
    world += [frame(0x13, pack("b", len(b)) + b"".join(pack("i", e) for e in b)) for b in (eids[i:i + 100] for i in range(0, len(eids), 100))]
    objectives, teams = {}, {}
    for f in m.scores:  # a 1.7.10 client refuses to create an objective or team that already exists
        pid, body = split(f); r = Reader(body)
        if pid == 0x3B: name = r.string(); r.string(); objectives[name] = r.u("b") != 1
        elif pid == 0x3E:
            name = r.string(); mode = r.u("b")
            if mode in (0, 1): teams[name] = mode == 0
    world += [frame(0x3B, string(n) + string("") + pack("b", 1)) for n, live in objectives.items() if live]
    world += [frame(0x3E, string(n) + pack("b", 1)) for n, live in teams.items() if live]
    world.append(frame(0x2B, pack("Bf", 1, 0)))  # rain stops
    return world, [frame(0x38, string(n) + pack("?h", False, 0)) for n in m.players]


def inventory(m: Mirror) -> bytes:
    """The host's inventory as 45 wire slots for a 5-row chest: main inventory, hotbar, then armor and, after a gap,
    any slots a mod adds past the player container's 45 (window 0: 0-4 crafting, 5-8 armor, 9-35 main, 36-44 hotbar)."""
    order = [*range(9, 45), *range(5, 9), None, *sorted(s for s in m.inv if s >= 45)[:4]]
    return b"".join(m.inv.get(s, EMPTY) if s is not None else EMPTY for s in order + [None] * (45 - len(order)))


EMPTY = pack("h", -1)


class Forwarder(Feed):
    def __init__(self, relay: tuple[str, int], secret: bytes, tls: ssl.SSLContext | None, mirror: Mirror | None = None,
                 refresh_min: int = 4 << 20, backlog: int = 64 << 20):
        super().__init__(mirror=mirror)
        self.relay, self.secret, self.tls, self.refresh_min, self.backlog = relay, secret, tls, refresh_min, backlog
        self.boot, self.sessions = secrets.token_hex(4), 0
        self.link = None     # the relay link's writer, once authenticated
        self.open = None     # the session id the relay is showing
        self.pending = None  # END for a session the relay has not heard close
        self.snap_bytes = self.tail_bytes = 0
        self.inv_sent = -1
        self.current = False  # read up to the end of the tap file: before that, frames only rebuild the mirror

    # ---- what goes to the relay
    def send(self, msg: bytes) -> bool:
        if self.link is None: return False
        self.link.write(msg)
        if self.link.transport.get_write_buffer_size() > self.backlog:  # the uplink cannot keep up: start over later
            print("forwarder: relay link fell too far behind; dropping it", flush=True)
            self.link.transport.abort(); self.lost()
        return self.link is not None

    def snapshot(self):
        m = self.mirror
        _, join = split(m.prelude[m.join])
        start, diff = Reader(join).u("iBbB")[2:]
        respawn = frame(0x07, pack("iBB", start, diff, SPECTATOR) + join[8:])
        snap = [(g, f) for g, f in m.gated() if public(f)]
        self.snap_bytes, self.tail_bytes = sum(len(f) for _, f in snap), 0
        meta = {"session": self.open, "dim": start, "host": m.name, "prelude": len(m.prelude), "respawn": 1,
                "eid": m.eid, "pos": m.hpos, "uuid": m.uuid}  # the agent's entity and where it stands (/tp); its account
        self.send(message(SNAPSHOT, headed(meta, [(0, respawn), *snap])))
        self.inv_sent = -1; self.share_inventory()

    def share_inventory(self):
        m = self.mirror
        if m.inv_version == self.inv_sent or not m.inv: return
        self.inv_sent = m.inv_version
        self.send(message(INVENTORY, headed({"title": f"{m.name[:16]}'s inventory"}, []) + inventory(m)))

    def opening(self):
        if self.link is None or self.open is not None or self.disabled or not self.mirror.ready or not self.current: return
        self.sessions += 1; self.open = f"{self.boot}-{self.sessions}"
        print(f"forwarder: showing session {self.open} ({self.mirror.name})", flush=True)
        self.snapshot()

    def close_session(self):
        if self.open is None: return
        world, tab = clearing(self.mirror)
        self.pending = message(END, headed({"session": self.open, "dim": self.mirror.dim, "world": len(world)},
                                           [(0, f) for f in world + tab]))
        self.open = None
        if self.send(self.pending): self.pending = None

    # ---- Feed hooks
    def played(self, frames):
        if not self.current: return
        if self.open is None: return self.opening()  # a snapshot taken now already holds these frames
        self.share_inventory()
        live = b"".join(f for f in frames if public(f))
        if not live: return
        self.send(message(LIVE, live)); self.tail_bytes += len(live)
        if self.open and self.tail_bytes > max(self.snap_bytes, self.refresh_min): self.snapshot()  # bounds the relay's cache

    def ended(self, text):
        self.close_session()

    # ---- the link
    def linked(self, w):
        self.link = w
        if self.pending and self.send(self.pending): self.pending = None
        self.opening()

    def lost(self):
        if self.link is None: return
        self.link = None      # first, so the END below waits for the next link instead of going down with this one
        self.close_session()  # what the relay has is this session up to now

    async def dial(self):
        delay = 1
        while True:
            w = None
            try:
                r, w = await asyncio.wait_for(asyncio.open_connection(*self.relay, ssl=self.tls,
                                                                      server_hostname=self.relay[0] if self.tls else None), 15)
                hello = await asyncio.wait_for(r.readexactly(len(MAGIC) + NONCE), 15)
                if hello[:len(MAGIC)] != MAGIC: raise ConnectionError("not a spectator relay")
                w.write(proof(self.secret, hello[len(MAGIC):]))
                print(f"forwarder: linked to {self.relay[0]}:{self.relay[1]}", flush=True)
                self.linked(w); delay = 1
                while self.link is w:
                    try:
                        if not await asyncio.wait_for(r.read(4096), 5): break  # the relay closed; it never sends
                    except asyncio.TimeoutError:
                        self.send(message(HEARTBEAT)); await asyncio.wait_for(w.drain(), 30)
            except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError, ssl.SSLError) as e:
                print(f"forwarder: relay link: {e!r}", flush=True)
            if self.link is w: self.lost()
            if w: w.close()
            await asyncio.sleep(delay); delay = min(delay * 2, 60)

    # ---- the tap
    async def follow(self, tap_dir: Path, poll: float = 0.5, chunk: int = 1 << 20):
        """Reads every connection file the client's tap writes, each from its first byte, oldest first, and keeps up
        with them. Which of them is the game is the protocol's business, as with any client: a server list ping is a
        connection too."""
        files, done = {}, set()
        try:
            while True:
                for path in sorted(tap_dir.glob("*.tap"), key=_started):
                    if path not in files and path not in done:
                        files[path] = TapFile(path); print(f"forwarder: reading {path.name}", flush=True)
                behind = False
                for path, t in list(files.items()):
                    behind |= t.read(self, chunk)
                    if t.over: t.close(); del files[path]; done.add(path)
                if behind: await asyncio.sleep(0); continue
                if not self.current:
                    self.current = True
                    print("forwarder: caught up with the tap", flush=True)
                    self.opening()
                await asyncio.sleep(poll)
        finally:
            for t in files.values(): t.close()

    # ---- the overlay
    async def narrate(self, overlay: Path, poll: float = 2.0):
        """The stream overlay's public story (overlay/live.json and feed.jsonl, written by the console) for the relay's
        sidebar and chat: what the agent is after, what it says, and the quests it claims. Only what the stream shows."""
        feed, sent, link = overlay / "feed.jsonl", None, None
        pos = feed.stat().st_size if feed.exists() else 0
        while True:
            if self.link is not link: sent, link = None, self.link  # a new link hears the status again
            try:
                status = status_of(json.loads((overlay / "live.json").read_text(encoding="utf-8")))
                if status != sent and self.send(message(STATUS, json.dumps(status).encode())): sent = status
            except (OSError, ValueError): pass
            try:
                if feed.stat().st_size < pos: pos = 0  # started over
                with open(feed, "rb") as f: f.seek(pos); data = f.read()
                cut = data.rfind(b"\n") + 1; pos += cut
                for line in data[:cut].splitlines():
                    e = json.loads(line)
                    if e.get("kind") in ("say", "mark") and e.get("text"):
                        self.send(message(NEWS, json.dumps({"kind": e["kind"], "text": e["text"][:600]}).encode()))
            except (OSError, ValueError): pass
            await asyncio.sleep(poll)

    async def run(self, tap_dir: Path, overlay: Path | None = None):
        self.loop = asyncio.get_running_loop()
        print(f"forwarder: following the tap in {tap_dir}" + (f" and the overlay in {overlay}" if overlay else ""), flush=True)
        await asyncio.gather(self.follow(tap_dir), self.dial(), *([self.narrate(overlay)] if overlay else []))


def status_of(live: dict) -> dict:
    g, run = live.get("goal") or {}, live.get("run") or {}
    return {"model": run.get("model"), "chapter": g.get("chapter"), "quest": g.get("quest"),
            "claims": (live.get("stats") or {}).get("claims"), "started": (live.get("budget") or {}).get("startedAt")}


class TapFile:
    """One connection's file, as SpectatorTap.java appends it: TAP_MAGIC, then records."""
    def __init__(self, path: Path):
        self.path, self.f, self.buf, self.magic, self.taps, self.over = path, open(path, "rb"), b"", False, {}, False

    def read(self, fw: "Forwarder", chunk: int) -> bool:
        """Takes in what has been written since; True while there is more already waiting."""
        data = self.f.read(chunk)
        self.buf += data
        if not self.magic and len(self.buf) >= len(TAP_MAGIC):
            if self.buf[:len(TAP_MAGIC)] != TAP_MAGIC: return self.give_up(fw, "is not a tap file")
            self.buf, self.magic = self.buf[len(TAP_MAGIC):], True
        pos = 0
        while self.magic and len(self.buf) - pos >= TAP.size:
            kind, conn, n = TAP.unpack_from(self.buf, pos)
            if n > TAP_RECORD: return self.give_up(fw, "is damaged")
            if len(self.buf) - pos - TAP.size < n: break
            body = self.buf[pos + TAP.size:pos + TAP.size + n]; pos += TAP.size + n
            if kind == OPENED: self.taps[conn] = fw.new_tap()
            elif (tap := self.taps.get(conn)) is None: continue
            elif kind == CLOSED: del self.taps[conn]; tap.dead = True; fw.end(tap); self.over = not self.taps
            else: tap.feed(kind, body)
        self.buf = self.buf[pos:]
        return len(data) == chunk

    def give_up(self, fw, why) -> bool:
        print(f"forwarder: {self.path.name} {why}; leaving it", flush=True)
        for tap in self.taps.values(): tap.dead = True; fw.end(tap)
        self.taps.clear(); self.over = True
        return False

    def close(self): self.f.close()


def _started(p: Path) -> int:
    try: return int(p.name.split("-")[0])
    except ValueError: return -1


def _loopback(host: str) -> bool:
    import ipaddress, socket
    try: return ipaddress.ip_address(socket.gethostbyname(host)).is_loopback
    except (OSError, ValueError): return False


def main():
    from .__main__ import address
    p = argparse.ArgumentParser(prog="python -m harness.mirror.forwarder", description=__doc__)
    p.add_argument("--tap", type=Path, default=Path.home() / ".moddedbench" / "tap",
                   help="the directory the client's tap writes (the path in ~/.moddedbench/spectator-tap)")
    p.add_argument("--relay", type=address, required=True, help="the relay's link port")
    p.add_argument("--relay-cert", help="the relay's self-signed certificate, pinned; default: the system's CAs")
    p.add_argument("--plain", action="store_true", help="no TLS (a relay on loopback, for tests)")
    p.add_argument("--overlay", type=Path, help="the stream overlay's directory (.runtime/outbox/overlay): sidebar and agent chat")
    p.add_argument("--payload-cap", type=int, default=8 << 20)
    a = p.parse_args()
    if a.plain and not _loopback(a.relay[0]): raise SystemExit("--plain is for a relay on loopback only")
    tls = None
    if not a.plain:
        tls = ssl.create_default_context(cafile=a.relay_cert)
        if a.relay_cert: tls.check_hostname = False  # the pinned certificate is the identity
    fw = Forwarder(a.relay, token(), tls, Mirror(payload_cap=a.payload_cap))
    try: asyncio.run(fw.run(a.tap, a.overlay))
    except KeyboardInterrupt: pass


if __name__ == "__main__":
    main()
