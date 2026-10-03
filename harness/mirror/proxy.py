# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The host pipe, the tap that reads a copy of it, and the viewer listener.

The host <-> server path is two blocking threads per connection that forward bytes as they arrive and then hand
a copy to the event loop. Nothing the mirror does can block, delay or close that path: a tap that cannot parse
or falls too far behind turns mirroring off for the session and the bytes keep flowing.
"""
from __future__ import annotations
import asyncio, json, os, random, socket, struct, threading, time
from pathlib import Path
from .state import BARRIER_REPLY, Mirror, disconnect
from .wire import Reader, Splitter, frame, offline_uuid, pack, split, string

C, S = 0, 1  # directions: host client -> server, server -> host client
BACKLOG = 64 << 20  # bytes a tap may fall behind before mirroring is turned off
CAPTURE = struct.Struct(">dBII")  # time, direction (2 = new connection), connection number, frame length
READY = "ModdedBench mirror: the agent is not in the game right now; try again shortly"
GONE = "agent reconnecting; rejoin shortly"


class Tap:
    """Follows one host connection's protocol state from a copy of its bytes."""
    def __init__(self, hub: "Feed", n: int = 0):
        self.n, self.hub, self.split, self.state, self.dead = n, hub, (Splitter(), Splitter()), ["handshake", "handshake"], False
        self.early = []  # server frames whose copy overtook the client's handshake (the two directions are separate threads)

    def feed(self, d: int, data: bytes):
        if self.dead: return
        try:
            frames = self.split[d].feed(data)
        except ValueError as e:
            return self.stop(f"{'CS'[d]} framing: {e}")
        for f in frames:
            if self.dead: return
            self.frame(d, f)

    def stop(self, why: str):
        self.dead = True
        if self.hub.session is self: self.hub.disable(why)

    def frame(self, d: int, f: bytes):
        if d == S and self.state[S] == "handshake":
            self.early.append(f)
            return self.stop("server spoke before any handshake") if len(self.early) > 256 else None
        hub = self.hub
        try:
            pid, body = split(f)
            state = self.state[d]
            hub.count(d, state, pid, f, body)
            hub.record(d, self.n, f)
            if state == "handshake":
                r = Reader(body); r.varint(); r.string(); r.u("H")
                self.state[:] = [{1: "status", 2: "login"}.get(r.varint(), "other")] * 2
                for e in self.early: self.frame(S, e)
                self.early = []
            elif state == "login" and d == C and pid == 0x00: self.state[C] = "play"
            elif state == "login" and d == S and pid == 0x02:
                r = Reader(body); hub.begin(self, r.string(), r.string()); self.state[S] = "play"
        except Exception as e:  # noqa: BLE001 - a copy that cannot be read only ends the mirror
            return self.stop(f"{'CS'[d]} {self.state[d]}: {e!r}")
        if state == "play" and hub.session is self:
            if d == S and pid == 0x40: return hub.end(self)
            hub.play(d, pid, body, f)


class Viewer:
    def __init__(self, reader, writer, name: str, uuid: str, cap: int):
        self.r, self.w, self.name, self.uuid, self.cap = reader, writer, name, uuid, cap  # uuid: dashed
        self.q, self.size, self.closing, self.ev = [], 0, False, asyncio.Event()
        self.held, self.hs = [], 0  # (gate, frame) waiting on this viewer's FML handshake replies

    def hold(self, gated):
        self.held += list(gated); self.release()

    def handshook(self):
        self.hs += 1; self.release()

    def release(self):
        n = 0
        while n < len(self.held) and self.held[n][0] <= self.hs: n += 1
        out, self.held = [f for _, f in self.held[:n]], self.held[n:]
        if out: self.send(out, now=True)

    def send(self, frames, now=False):
        if self.closing: return
        if self.held and not now:  # live frames queue behind a snapshot still waiting on the handshake
            self.held += [(0, f) for f in frames]
            if sum(len(f) for _, f in self.held) > self.cap: self.q, self.held, self.closing = [], [], True; self.ev.set()
            return
        for f in frames: self.q.append(f); self.size += len(f)
        if self.size > self.cap: self.q, self.closing = [], True  # too slow to keep up: drop without a goodbye
        self.ev.set()

    def close(self, text: str | None = None):
        if text: self.send([disconnect(text)])
        self.closing = True; self.ev.set()

    async def pump(self):
        try:
            while True:
                await self.ev.wait(); self.ev.clear()
                while self.q:
                    batch, self.q, self.size = self.q, [], 0
                    self.w.write(b"".join(batch)); await self.w.drain()
                if self.closing: break
        except (ConnectionError, OSError):
            pass
        finally:
            self.w.close()


class Feed:
    """The tap side: follows host sessions from copies of their bytes and keeps their Mirror. Where the viewer frames
    go is the subclass's business: played() gets each batch, ended() hears a session stop before its state is reset."""
    def __init__(self, capture_path: Path | None = None, mirror: Mirror | None = None):
        self.mirror = mirror or Mirror()
        self.session, self.disabled = None, None
        self.packets = ({}, {}); self.channels = ({}, {}); self.errors = []
        self.capture = open(capture_path, "ab") if capture_path else None
        self.loop, self.taps = None, 0

    def played(self, frames: list[bytes]): pass
    def ended(self, text: str): pass

    def count(self, d, state, pid, f, body):
        key = f"{pid:02x}" if state == "play" else f"{state}:{pid:02x}"
        c = self.packets[d].setdefault(key, [0, 0]); c[0] += 1; c[1] += len(f)
        if state == "play" and pid == (0x17, 0x3F)[d]:
            ch = self.channels[d].setdefault(Reader(body).string(), [0, 0]); ch[0] += 1; ch[1] += len(f)

    def record(self, d, n, f):
        if self.capture: self.capture.write(CAPTURE.pack(time.time(), d, n, len(f)) + f)

    def begin(self, tap, uuid, name):
        self.end(self.session)
        self.session, self.disabled = tap, None
        self.mirror.login(uuid, name)

    def end(self, tap):
        if tap is None or tap is not self.session: return
        self.session = None
        self.ended(GONE)
        self.mirror.reset()

    def disable(self, why: str):
        self.disabled = why
        self.errors = (self.errors + [f"{time.strftime('%H:%M:%S')} {why}"])[-20:]
        print(f"mirror: off for this session: {why}", flush=True)
        self.ended("ModdedBench mirror stopped for this session; the agent is unaffected")
        self.mirror.reset()

    def play(self, d, pid, body, f):
        if self.disabled: return
        try:
            out = self.mirror.server(f, pid, body) if d == S else self.mirror.client(pid, body)
        except Exception as e:  # the mirror is best effort; the pipe never sees this
            return self.disable(f"{'CS'[d]} {pid:02x}: {e!r}")
        self.played(out)

    def new_tap(self) -> Tap:
        self.taps += 1
        self.record(2, self.taps, b"")
        return Tap(self, self.taps)

    def tap_stats(self) -> dict:
        named = lambda t: {k: {"count": n, "bytes": b} for k, (n, b) in sorted(t.items())}
        return {"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "session": self.session is not None,
                "disabled": self.disabled, "errors": self.errors, "mirror": self.mirror.stats(),
                "packets": {"to_server": named(self.packets[C]), "to_client": named(self.packets[S])},
                "channels": {"to_server": named(self.channels[C]), "to_client": named(self.channels[S])}}


class Stage:
    """The viewer side: greets 1.7.10 clients, in online mode (auth.py) when asked, hands each the frames that catch
    it up, and ignores everything a viewer sends except the Forge handshake replies that release those frames."""
    motd = "ModdedBench mirror"

    def __init__(self, max_viewers: int = 8, viewer_queue: int = 64 << 20, online: bool = False):
        self.max_viewers, self.viewer_queue, self.viewers = max_viewers, viewer_queue, set()
        self.greeting = 0  # connections still in their handshake; capped, as the port may be public
        self.auth = None
        if online:
            from . import auth
            self.auth = auth.Online()

    def refuse(self, name: str) -> str | None:
        """Why this viewer cannot join now, or None."""
        return None

    def welcome(self, v: Viewer): pass

    def heard(self, v: Viewer, pid: int, body: bytes):
        """A play packet from a viewer, other than its Forge handshake replies."""

    def broadcast(self, frames):
        if frames:
            for v in self.viewers: v.send(frames)

    def kick(self, text):
        for v in list(self.viewers): v.close(text)
        self.viewers.clear()

    async def viewer(self, r: asyncio.StreamReader, w: asyncio.StreamWriter):
        if self.greeting >= 4 * self.max_viewers: return w.close()
        self.greeting += 1
        try:
            v = await asyncio.wait_for(self._greet(r, w), 25)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ValueError, KeyError, OSError, UnicodeDecodeError, struct.error) as e:
            if self.auth and not isinstance(e, (asyncio.IncompleteReadError, ConnectionError)): print(f"{self.motd}: a login failed: {e!r}", flush=True)
            v = None
        finally:
            self.greeting -= 1
        if v is None: return w.close()
        drain, start = asyncio.create_task(self._drain(v.r, v)), time.monotonic()
        await v.pump()
        self.viewers.discard(v); drain.cancel()
        print(f"{self.motd}: viewer {v.name} left after {time.monotonic() - start:.0f} s "
              f"({v.hs} handshake replies, {len(v.held)} frames still waiting on them)", flush=True)

    async def _greet(self, r, w):
        first = await r.readexactly(1)
        if first == b"\xfe": return  # pre-1.7 server list ping
        pid, body = split(await read_frame(r, first))
        hr = Reader(body); protocol = hr.varint(); hr.string(); hr.u("H"); nxt = hr.varint()
        if nxt not in (1, 2): return
        if nxt == 1:
            await read_frame(r)
            online = len(self.viewers)
            w.write(frame(0x00, string(json.dumps({
                "version": {"name": "1.7.10", "protocol": 5}, "description": {"text": self.motd},
                "players": {"max": self.max_viewers, "online": online, "sample": []}}))))
            await w.drain()
            _, ping = split(await read_frame(r))
            w.write(frame(0x01, ping)); await w.drain()
            return
        _, body = split(await read_frame(r))
        name = Reader(body).string()[:16]
        why = ("ModdedBench mirror needs a 1.7.10 client" if protocol != 5 else
               "ModdedBench mirror is full" if len(self.viewers) >= self.max_viewers else self.refuse(name))
        if why:
            w.write(disconnect(why, login=True)); await w.drain()
            return
        uuid = offline_uuid(name)
        if self.auth:
            r, w, uuid, name = await self.auth.login(r, w, name)
            if uuid is None: return
            for old in [o for o in self.viewers if o.uuid == uuid]: old.close("you joined again from somewhere else")
        v = Viewer(r, w, name, uuid, self.viewer_queue)
        v.send([frame(0x02, string(uuid) + string(name))])
        self.welcome(v)
        self.viewers.add(v)
        print(f"{self.motd}: viewer {name} joined ({len(self.viewers)} watching)", flush=True)
        return v

    async def _drain(self, r, v):
        s = Splitter()
        try:  # a viewer's Forge handshake replies and its barrier answer release the snapshot; the rest goes to heard()
            while (data := await r.read(65536)) and not v.closing:
                for f in s.feed(data):
                    pid, body = split(f)
                    if pid == 0x17 and Reader(body).string() == "FML|HS" or pid == 0x0F and body == BARRIER_REPLY:
                        v.handshook()
                    else: self.heard(v, pid, body)
        except (ConnectionError, OSError, ValueError, UnicodeDecodeError, struct.error):
            pass
        v.close(); self.viewers.discard(v)

    async def keepalive(self, every=5.0):
        while True:
            await asyncio.sleep(every)
            f = frame(0x00, pack("i", random.randint(1, 2**31 - 1)))
            for v in self.viewers: v.send([f])


class Hub(Feed, Stage):
    """The inline mirror: the host's connection runs through this process, and viewers connect to it."""
    def __init__(self, upstream=("127.0.0.1", 25575), stats_path: Path | None = None, capture_path: Path | None = None,
                 max_viewers: int = 8, viewer_queue: int = 64 << 20, mirror: Mirror | None = None):
        Feed.__init__(self, capture_path, mirror); Stage.__init__(self, max_viewers, viewer_queue)
        self.upstream, self.stats_path = upstream, stats_path

    def played(self, frames): self.broadcast(frames)
    def ended(self, text): self.kick(text)

    def refuse(self, name):
        return ("ModdedBench mirror is off for this session" if self.disabled else
                READY if not self.mirror.ready else
                "that name is the agent's; pick another" if name.lower() == self.mirror.name.lower() else None)

    def welcome(self, v): v.hold(self.mirror.gated())

    # ---- host pipe (threads)
    def serve_host(self, sock: socket.socket):
        while True:
            client, _ = sock.accept()
            threading.Thread(target=self._host, args=(client,), daemon=True).start()

    def _host(self, client: socket.socket):
        try:
            server = socket.create_connection(self.upstream, 10)
            server.settimeout(None)
        except OSError:
            client.close(); return
        for s in (client, server): s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        tap = asyncio.run_coroutine_threadsafe(self._tap(), self.loop).result()
        backlog = [0]; lock = threading.Lock()

        def fed(d, data):
            with lock: backlog[0] -= len(data)
            tap.feed(d, data)

        def pump(src, dst, d):
            try:
                while data := src.recv(65536):
                    dst.sendall(data)
                    if tap.dead: continue
                    with lock: backlog[0] += len(data); over = backlog[0] > BACKLOG
                    try:
                        if over: tap.dead = True; self.loop.call_soon_threadsafe(tap.stop, "tap fell too far behind")
                        else: self.loop.call_soon_threadsafe(fed, d, data)
                    except RuntimeError:  # the event loop is gone; keep piping
                        tap.dead = True
            except OSError:
                pass
            finally:
                for s in (src, dst):
                    try: s.shutdown(socket.SHUT_RDWR)
                    except OSError: pass

        up = threading.Thread(target=pump, args=(client, server, C), daemon=True); up.start()
        pump(server, client, S); up.join()
        client.close(); server.close()
        try: self.loop.call_soon_threadsafe(lambda: (setattr(tap, "dead", True), self.end(tap)))
        except RuntimeError: pass

    async def _tap(self) -> Tap:
        return self.new_tap()

    def stats(self) -> dict:
        return {**self.tap_stats(), "viewers": sorted(v.name for v in self.viewers)}

    async def write_stats(self, every=10.0):
        while self.stats_path:
            try:
                self.stats_path.parent.mkdir(parents=True, exist_ok=True)
                tmp = self.stats_path.with_suffix(".tmp")
                tmp.write_text(json.dumps(self.stats(), indent=1))
                os.replace(tmp, self.stats_path)
                if self.capture: self.capture.flush()
            except Exception as e:  # noqa: BLE001 - never let bookkeeping end the process the pipe lives in
                print(f"mirror: stats: {e!r}", flush=True)
            await asyncio.sleep(every)

    async def run(self, listen, viewers, replay: Path | None = None):
        self.loop = asyncio.get_running_loop()
        if replay:
            load_capture(self, replay)
        else:
            host = socket.create_server(listen)
            threading.Thread(target=self.serve_host, args=(host,), daemon=True).start()
            print(f"mirror: host pipe {listen[0]}:{host.getsockname()[1]} -> {self.upstream[0]}:{self.upstream[1]}", flush=True)
        vs = await asyncio.start_server(self.viewer, *viewers)
        print(f"mirror: viewers on {viewers[0]}:{vs.sockets[0].getsockname()[1]}", flush=True)
        await asyncio.gather(vs.serve_forever(), self.keepalive(), self.write_stats())


async def read_frame(r: asyncio.StreamReader, first: bytes = b"") -> bytes:
    raw = bytearray(first or await r.readexactly(1))
    while raw[-1] & 0x80:
        if len(raw) >= 3: raise ValueError("frame length varint too long")
        raw += await r.readexactly(1)
    n = Reader(bytes(raw)).varint()
    if not 0 < n < 1 << 16: raise ValueError(f"unexpected frame length {n}")  # handshake/login/status frames are small
    return bytes(raw) + await r.readexactly(n)


def load_capture(hub: Hub, path: Path):
    """Feed a raw capture through the tap, as if the host session were happening now."""
    taps = {}
    with open(path, "rb") as fh:
        while head := fh.read(CAPTURE.size):
            _, d, conn, n = CAPTURE.unpack(head)
            f = fh.read(n)
            if d == 2: taps[conn] = Tap(hub, conn)
            elif (tap := taps.get(conn)) and not tap.dead: tap.frame(d, f)
