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
from .state import Mirror, disconnect
from .wire import Reader, Splitter, frame, offline_uuid, pack, split, string

C, S = 0, 1  # directions: host client -> server, server -> host client
BACKLOG = 64 << 20  # bytes a tap may fall behind before mirroring is turned off
CAPTURE = struct.Struct(">dBII")  # time, direction (2 = new connection), connection number, frame length
READY = "ModdedBench mirror: the agent is not in the game right now; try again shortly"
GONE = "agent reconnecting; rejoin shortly"


class Tap:
    """Follows one host connection's protocol state from a copy of its bytes."""
    def __init__(self, hub: "Hub", n: int = 0):
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
    def __init__(self, writer: asyncio.StreamWriter, name: str, cap: int):
        self.w, self.name, self.cap = writer, name, cap
        self.q, self.size, self.closing, self.ev = [], 0, False, asyncio.Event()

    def send(self, frames):
        if self.closing: return
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


class Hub:
    def __init__(self, upstream=("127.0.0.1", 25575), stats_path: Path | None = None, capture_path: Path | None = None,
                 max_viewers: int = 8, viewer_queue: int = 64 << 20, mirror: Mirror | None = None):
        self.upstream, self.stats_path, self.max_viewers, self.viewer_queue = upstream, stats_path, max_viewers, viewer_queue
        self.mirror = mirror or Mirror()
        self.session, self.disabled, self.viewers = None, None, set()
        self.packets = ({}, {}); self.channels = ({}, {}); self.errors = []
        self.capture = open(capture_path, "ab") if capture_path else None
        self.loop, self.taps = None, 0

    # ---- tap side (event loop thread)
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
        self.kick(GONE)
        self.mirror.reset()

    def disable(self, why: str):
        self.disabled = why
        self.errors = (self.errors + [f"{time.strftime('%H:%M:%S')} {why}"])[-20:]
        print(f"mirror: off for this session: {why}", flush=True)
        self.kick("ModdedBench mirror stopped for this session; the agent is unaffected")
        self.mirror.reset()

    def kick(self, text):
        for v in list(self.viewers): v.close(text)
        self.viewers.clear()

    def play(self, d, pid, body, f):
        if self.disabled: return
        try:
            out = self.mirror.server(f, pid, body) if d == S else self.mirror.client(pid, body)
        except Exception as e:  # the mirror is best effort; the pipe never sees this
            return self.disable(f"{'CS'[d]} {pid:02x}: {e!r}")
        if out:
            for v in self.viewers: v.send(out)

    def new_tap(self) -> Tap:
        self.taps += 1
        self.record(2, self.taps, b"")
        return Tap(self, self.taps)

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

    # ---- viewers (event loop)
    async def viewer(self, r: asyncio.StreamReader, w: asyncio.StreamWriter):
        try:
            v = await asyncio.wait_for(self._greet(r, w), 15)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ValueError, ConnectionError, UnicodeDecodeError, struct.error):
            v = None
        if v is None: return w.close()
        drain = asyncio.create_task(self._drain(r, v))
        await v.pump()
        self.viewers.discard(v); drain.cancel()

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
                "version": {"name": "1.7.10", "protocol": 5}, "description": {"text": "ModdedBench mirror"},
                "players": {"max": self.max_viewers, "online": online, "sample": []}}))))
            await w.drain()
            _, ping = split(await read_frame(r))
            w.write(frame(0x01, ping)); await w.drain()
            return
        _, body = split(await read_frame(r))
        name = Reader(body).string()[:16]
        why = ("ModdedBench mirror needs a 1.7.10 client" if protocol != 5 else
               "ModdedBench mirror is off for this session" if self.disabled else
               READY if not self.mirror.ready else
               "ModdedBench mirror is full" if len(self.viewers) >= self.max_viewers else
               "that name is the agent's; pick another" if name.lower() == self.mirror.name.lower() else None)
        if why:
            w.write(disconnect(why, login=True)); await w.drain()
            return
        v = Viewer(w, name, self.viewer_queue)
        v.send([frame(0x02, string(offline_uuid(name)) + string(name)), *self.mirror.snapshot()])
        self.viewers.add(v)
        print(f"mirror: viewer {name} joined ({len(self.viewers)} watching)", flush=True)
        return v

    async def _drain(self, r, v):
        try:  # everything a viewer sends is ignored, keepalive replies included
            while await r.read(65536) and not v.closing: pass
        except (ConnectionError, OSError):
            pass
        v.close(); self.viewers.discard(v)

    async def keepalive(self, every=5.0):
        while True:
            await asyncio.sleep(every)
            f = frame(0x00, pack("i", random.randint(1, 2**31 - 1)))
            for v in self.viewers: v.send([f])

    def stats(self) -> dict:
        named = lambda t: {k: {"count": n, "bytes": b} for k, (n, b) in sorted(t.items())}
        return {"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "session": self.session is not None,
                "disabled": self.disabled, "errors": self.errors, "viewers": sorted(v.name for v in self.viewers),
                "mirror": self.mirror.stats(),
                "packets": {"to_server": named(self.packets[C]), "to_client": named(self.packets[S])},
                "channels": {"to_server": named(self.channels[C]), "to_client": named(self.channels[S])}}

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
