# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Spectator mirror: wire codec, late-viewer snapshot, the byte pipe's immunity to the parser, viewer greeting."""
import asyncio
import json
import socket
import tempfile
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from harness.mirror import proxy, state, wire  # noqa: E402
from harness.mirror.wire import Reader, frame, pack, split, string, varint  # noqa: E402

HOST, UUID = "Agent", wire.offline_uuid("Agent")
HELD = pack("hbhh", 267, 1, 0, -1) + b"\x01"    # iron sword, no NBT, then GTNH's varint stack size
HELMET = pack("hbhh", 306, 1, 0, 3) + b"abc\x01"


def handshake(nxt=2): return frame(0, varint(5) + string("127.0.0.1") + pack("H", 25575) + varint(nxt))
def s3f(channel, data): return frame(0x3F, string(channel) + pack("H", len(data)) + data)
def chunk(cx, cz, mask=1, data=b"zlib"): return frame(0x21, pack("ii?HHi", cx, cz, True, mask, 0, len(data)) + data)
def bulk(cs, data=b"zlibzlib"): return frame(0x26, pack("hi?", len(cs), len(data), True) + data + b"".join(pack("iiHH", *c, 1, 0) for c in cs))
def block(x, y, z, v=1): return frame(0x23, pack("iBi", x, y, z) + bytes([0, v, 0]))
def mob(eid, x, y, z): return frame(0x0F, varint(eid) + pack("biiiBBBhhh", 50, x, y, z, 0, 0, 0, 0, 0, 0) + b"\x7f")
def fml_spawn(eid, x, y, z): return s3f("FML", pack("bi", 2, eid) + string("mod") + pack("iiiiBBB", 1, x, y, z, 0, 0, 0) + b"\x7f")

SERVER = [
    frame(0x02, string(UUID) + string(HOST)),
    s3f("REGISTER", b"FML|HS\0FML\0MB|Clock"), s3f("FML|HS", b"\x00\x02"),
    frame(0x01, pack("iBbBB", 42, 0, 0, 1, 20) + string("default")),
    s3f("gtnh-config", b"cfg"), frame(0x03, pack("qq", 1, 2)), frame(0x39, pack("Bff", 0, 0.05, 0.1)),
    frame(0x09, pack("b", 2)),
    frame(0x30, pack("Bh", 0, 45) + b"".join(HELD if i == 38 else HELMET if i == 5 else pack("h", -1) for i in range(45))),
    frame(0x08, pack("dddff?", 100.5, 65.62, -20.25, 90.0, 10.0, False)),
    chunk(0, 0), chunk(1, 0), bulk([(2, 0), (3, 0)]),
    block(5, 64, 5, 1), block(5, 64, 5, 2), block(20, 64, 5), chunk(1, 0, mask=0, data=b"u"),
    block(40, 64, 5), chunk(2, 0, mask=0, data=b"u"),
    mob(7, 32, 2048, 32), frame(0x15, pack("ibbb", 7, 32, 0, 0)), frame(0x15, pack("ibbb", 7, 32, 0, 0)),
    frame(0x1C, pack("i", 7) + bytes([0x00, 5]) + b"\x7f"),
    mob(8, 0, 0, 0), frame(0x13, pack("bi", 1, 8)),
    fml_spawn(9, 64, 2048, 64), s3f("FML", pack("biiii", 3, 9, 80, 2048, 80)),
    s3f("MB|Clock", b"secret"), frame(0x06, pack("fhf", 20, 20, 5)), frame(0x1F, pack("fhh", 0, 0, 0)),
    frame(0x2B, pack("Bf", 3, 1)), frame(0x2B, pack("Bf", 2, 0)), frame(0x2D, pack("BB", 1, 0) + string("x") + pack("B?", 9, False)),
    frame(0x37, varint(0)), frame(0x02, string('{"text":"hi"}')), frame(0x38, string("Agent") + pack("?h", True, 5)),
    frame(0x00, pack("i", 99)),
]
CLIENT = [handshake(), frame(0x00, string(HOST)),
          frame(0x06, pack("ddddff?", 101.0, 64.0, 65.62, -20.0, 45.0, 5.0, True)), frame(0x0A, pack("ib", 42, 1))]


def feed(hub, frames_c=CLIENT, frames_s=SERVER):
    tap = hub.new_tap()
    for f in frames_s[:1]: tap.frame(proxy.S, f)  # overtakes the handshake: held until it is read
    for f in frames_c[:2]: tap.frame(proxy.C, f)
    for f in frames_s[1:]: tap.frame(proxy.S, f)
    for f in frames_c[2:]: tap.frame(proxy.C, f)
    return tap


def parsed(frames): return [split(f) for f in frames]


class Codec(unittest.TestCase):
    def test_varint_string_roundtrip(self):
        for n in (0, 1, 127, 128, 300, 2**31 - 1, -1, -2**31):
            self.assertEqual(Reader(varint(n)).varint(), n)
        self.assertEqual(Reader(string("Grüße ☃")).string(), "Grüße ☃")

    def test_items_metadata_payload(self):
        r = Reader(HELMET + pack("h", -1) + b"!")
        self.assertEqual((r.item(), r.item(), r.rest()), (HELMET, pack("h", -1), b"!"))
        meta = bytes([0x00, 1, 0x84]) + string("name") + bytes([0xA5]) + HELD + bytes([0xC6]) + pack("iii", 1, 2, 3) + b"\x7f"
        self.assertEqual(sorted(Reader(meta).metadata()), [0, 4, 5, 6])
        big = bytes(40000)
        body = string("GT") + pack("HB", 0x8000 | (len(big) & 0x7FFF), len(big) >> 15) + big
        self.assertEqual(wire.payload(body), ("GT", big))

    def test_splitter_and_offline_uuid(self):
        stream = frame(1, b"a") + frame(0x26, bytes(300)) + frame(2)
        s, out = wire.Splitter(), []
        for i in range(len(stream)): out += s.feed(stream[i:i + 1])
        self.assertEqual(parsed(out), [(1, b"a"), (0x26, bytes(300)), (2, b"")])
        self.assertEqual(wire.offline_uuid("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f")
        with self.assertRaises(ValueError): wire.Splitter().feed(b"\xff\xff\xff\xff")


class Snapshot(unittest.TestCase):
    def setUp(self):
        self.hub = proxy.Hub()
        feed(self.hub)
        self.snap = parsed(self.hub.mirror.snapshot())

    def of(self, pid): return [b for p, b in self.snap if p == pid]

    def test_prelude_and_join_rewritten(self):
        self.assertEqual([p for p, _ in self.snap[:3]], [0x3F, 0x3F, 0x01])
        eid, gm, dim = Reader(self.snap[2][1]).u("iBb")
        self.assertEqual((eid, gm, dim), (state.VIEWER_EID, state.SPECTATOR, 0))
        self.assertIsNone(self.hub.disabled)

    def test_filtered(self):
        for pid in (0x00, 0x06, 0x09, 0x1F, 0x2D, 0x30, 0x37):
            self.assertEqual(self.of(pid), [], hex(pid))
        self.assertEqual(self.of(0x39), [pack("Bff", 7, 0.1, 0.1)])
        self.assertEqual([b[0] for b in self.of(0x2B)], [2])
        channels = [wire.payload(b)[0] for b in self.of(0x3F)]
        self.assertNotIn("MB|Clock", channels)
        self.assertIn("gtnh-config", channels)

    def test_chunks(self):
        loads = [Reader(b).u("ii?H") for b in self.of(0x21)]
        self.assertEqual(loads, [(0, 0, True, 1), (2, 0, True, 0)])  # chunk 1 gone; 2 unloaded after the bulk that still holds 3
        self.assertEqual(len(self.of(0x26)), 1)
        self.assertEqual([Reader(b).u("iBiBBB") for b in self.of(0x23)], [(5, 64, 5, 0, 2, 0)])

    def test_entities(self):
        spawns = [Reader(b).varint() for b in self.of(0x0F)]
        self.assertEqual(spawns, [7])
        tp = {Reader(b).u("i"): Reader(b).u("iiiiBB")[1:4] for b in self.of(0x18)}
        self.assertEqual(tp[7], (96, 2048, 32))
        self.assertEqual(tp[9], (80, 2048, 80))
        self.assertNotIn(8, tp)
        self.assertEqual(self.of(0x13), [])
        self.assertEqual(self.of(0x1C), [pack("i", 7) + bytes([0, 5]) + b"\x7f"])

    def test_avatar(self):
        (spawn,) = self.of(0x0C)
        r = Reader(spawn)
        self.assertEqual((r.varint(), r.string(), r.string(), r.varint()), (42, UUID, HOST, 0))
        self.assertEqual(r.u("iiiBBh"), (3232, 2048, -640, 32, 3, 267))
        equip = {Reader(b).u("ih")[1]: b[6:] for b in self.of(0x04)}
        self.assertEqual((equip[0], equip[4], equip[1]), (HELD, HELMET, pack("h", -1)))
        x, y, z, yaw, pitch, _ = Reader(self.snap[-1][1]).u("dddff?")
        self.assertEqual(self.snap[-1][0], 0x08)
        self.assertEqual((x, round(y, 2), z, yaw, pitch), (101.0, 65.62, -20.0, 45.0, 5.0))

    def test_live_stream(self):
        m = state.Mirror(); hub = proxy.Hub(mirror=m); out = []
        hub.viewers = {type("V", (), {"send": lambda self, fs: out.extend(split(f) for f in fs)})()}
        feed(hub)
        pids = [p for p, _ in out]
        self.assertNotIn(0x06, pids); self.assertNotIn(0x08, pids)
        self.assertIn((0x0B, varint(42) + b"\x00"), out)  # the host's swing
        self.assertTrue(any(p == 0x18 and Reader(b).u("i") == 42 for p, b in out))

    def test_respawn(self):
        m = self.hub.mirror
        same = pack("iBB", 0, 1, 0) + string("default")
        m.server(frame(0x08, pack("dddff?", 1, 71.62, 1, 0, 0, False)), 0x08, pack("dddff?", 1, 71.62, 1, 0, 0, False))
        self.assertEqual(parsed(m.server(frame(0x07, same), 0x07, same)), [(0x13, pack("bi", 1, 42))])
        pos = pack("dddff?", 3, 71.62, 3, 0, 0, False)
        self.assertEqual({p for p, _ in parsed(m.server(frame(0x08, pos), 0x08, pos))}, {0x0C, 0x04})  # avatar and its equipment
        nether = pack("iBB", -1, 1, 0) + string("default")
        out = parsed(m.server(frame(0x07, nether), 0x07, nether))
        self.assertEqual(out, [(0x07, pack("iBB", -1, 1, 3) + string("default")), (0x39, pack("Bff", 7, 0.1, 0.1))])
        out = [p for p, _ in parsed(m.server(frame(0x08, pos), 0x08, pos))]
        self.assertEqual(out[:2], [0x08, 0x0C])  # viewers follow the host through the portal
        snap = [p for p, _ in parsed(m.snapshot())]
        self.assertNotIn(0x21, snap); self.assertNotIn(0x0F, snap)
        self.assertIn(0x07, snap)

    def test_riding_keeps_position(self):
        m = self.hub.mirror
        riding = pack("ddddff?", 0.3, -999, -999, 0.1, 90.0, 0.0, False)
        m.client(0x06, riding)
        self.assertEqual(m.hpos[:3], [101.0, 64.0, -20.0])

    def test_capture_replays(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "cap.bin"
            hub = proxy.Hub(capture_path=path)
            feed(hub, frames_c=[handshake(1)], frames_s=[])  # an interleaved server-list ping
            feed(hub); hub.capture.close()
            again = proxy.Hub(); proxy.load_capture(again, path)
            self.assertEqual(again.mirror.snapshot(), self.hub.mirror.snapshot())

    def test_payload_cap_keeps_pinned(self):
        m = state.Mirror(payload_cap=100); hub = proxy.Hub(mirror=m)
        feed(hub, frames_s=SERVER + [s3f("gt", bytes(60)) for _ in range(5)])
        chans = [wire.payload(split(f)[1])[0] for f in m.snapshot() if split(f)[0] == 0x3F]
        self.assertIn("gtnh-config", chans)
        self.assertEqual(m.stats()["payloads"]["dropped"]["entries"], 4)


class Handshake(unittest.TestCase):
    def test_snapshot_waits_for_the_viewers_forge_replies(self):
        m = state.Mirror(); m.login(UUID, HOST)
        hs = lambda: split(s3f("FML|HS", b"x"))
        c17 = split(frame(0x17, string("FML|HS") + pack("h", 1) + b"y"))
        m.server(s3f("FML|HS", b"hello"), *hs())      # ServerHello: sent at once
        m.client(*c17); m.client(*c17)                # ClientHello, ModList
        m.server(s3f("FML|HS", b"mods"), *hs())       # the server's ModList answers them
        m.client(*c17)
        join = frame(0x01, pack("iBb", 7, 0, 0) + b"rest")
        m.server(join, *split(join))
        self.assertEqual([g for g, _ in m.gated()][:3], [0, 2, 3])
        v = proxy.Viewer(None, "Watcher", 1 << 20)
        v.hold(m.gated()); v.send([frame(0x03, b"live")])
        self.assertEqual(len(v.q), 1)                 # only ServerHello, and the live frame waits behind the snapshot
        v.handshook(); v.handshook(); self.assertEqual(len(v.q), 2)
        v.handshook(); self.assertFalse(v.held)
        self.assertEqual(split(v.q[-1]), (0x03, b"live"))


class Pipe(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.got = bytearray(); self.reply = asyncio.Queue()

        async def upstream(r, w):
            async def back():
                while True: w.write(await self.reply.get()); await w.drain()
            t = asyncio.create_task(back())
            while data := await r.read(65536): self.got += data
            t.cancel(); w.close()
        self.up = await asyncio.start_server(upstream, "127.0.0.1", 0)
        self.hub = proxy.Hub(upstream=("127.0.0.1", self.up.sockets[0].getsockname()[1]))
        self.hub.loop = asyncio.get_running_loop()
        sock = socket.create_server(("127.0.0.1", 0))
        self.port = sock.getsockname()[1]
        threading.Thread(target=self.hub.serve_host, args=(sock,), daemon=True).start()

    async def asyncTearDown(self):
        self.up.close()

    async def roundtrip(self, to_server: bytes, to_client: bytes):
        r, w = await asyncio.open_connection("127.0.0.1", self.port)
        w.write(to_server); await w.drain()
        for _ in range(250):
            if len(self.got) >= len(to_server): break
            await asyncio.sleep(0.02)
        await self.reply.put(to_client)
        back = await asyncio.wait_for(r.readexactly(len(to_client)), 5)
        self.assertEqual(bytes(self.got), to_server)
        self.assertEqual(back, to_client)
        w.close()

    async def test_garbage_passes_unchanged(self):
        junk = bytes(range(256)) * 50
        await self.roundtrip(b"\xff\xff\xff\xff" + junk, junk[::-1])

    async def test_parser_failure_leaves_pipe(self):
        login = handshake() + frame(0, string(HOST))
        bad = SERVER[0] + SERVER[3] + frame(0x21, b"\x00\x01") + frame(0x03, pack("qq", 1, 2))
        await self.roundtrip(login + frame(0x03, pack("?", True)), bad)
        await asyncio.sleep(0.05)
        self.assertIn("21", self.hub.disabled)


class Viewers(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.hub = proxy.Hub()
        self.srv = await asyncio.start_server(self.hub.viewer, "127.0.0.1", 0)
        self.port = self.srv.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.srv.close()

    async def test_status_ping(self):
        r, w = await asyncio.open_connection("127.0.0.1", self.port)
        w.write(handshake(1) + frame(0) + frame(1, pack("q", 1234))); await w.drain()
        pid, body = split(await proxy.read_frame(r))
        status = json.loads(Reader(body).string())
        self.assertEqual((pid, status["version"], status["description"]["text"]), (0, {"name": "1.7.10", "protocol": 5}, "ModdedBench mirror"))
        self.assertEqual(split(await proxy.read_frame(r)), (1, pack("q", 1234)))

    async def test_login_refused_then_served(self):
        r, w = await asyncio.open_connection("127.0.0.1", self.port)
        w.write(handshake() + frame(0, string("Watcher"))); await w.drain()
        pid, body = split(await proxy.read_frame(r))
        self.assertEqual(pid, 0); self.assertIn("not in the game", Reader(body).string())
        feed(self.hub)
        r, w = await asyncio.open_connection("127.0.0.1", self.port)
        w.write(handshake() + frame(0, string("Watcher"))); await w.drain()
        s = wire.Splitter(); got = []
        while not got or got[-1][0] != 0x08:
            got += parsed(s.feed(await asyncio.wait_for(r.read(65536), 5)))
        self.assertEqual(got[0], (0x02, string(wire.offline_uuid("Watcher")) + string("Watcher")))
        self.assertEqual(got[1:], parsed(self.hub.mirror.snapshot()))
        self.assertEqual(len(self.hub.viewers), 1)
        self.hub.end(self.hub.session)
        rest = parsed(s.feed(await asyncio.wait_for(r.read(65536), 5)))
        self.assertEqual(rest[-1][0], 0x40)
        w.close()


if __name__ == "__main__":
    unittest.main()
