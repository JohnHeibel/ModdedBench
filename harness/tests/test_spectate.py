# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Spectator forwarder and relay, end to end over loopback: a client tap's files in, a viewer's frames out."""
import asyncio
import json
import os
import struct
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from harness.mirror import board, bq, forwarder, link, relay, state  # noqa: E402
from harness.mirror.wire import Reader, Splitter, frame, pack, payload, split, string, varint  # noqa: E402
from harness.tests.test_mirror import CLIENT, SERVER, UUID, block, chunk, handshake, parsed, s3f  # noqa: E402

SECRET = b"a test secret of enough length"


def rec(kind, conn, data=b""): return struct.pack(">BII", kind, conn, len(data)) + data


def session(conn, server=SERVER, client=CLIENT):
    """A client's tap records for one game connection, in the order the client saw them."""
    return (rec(forwarder.OPENED, conn, b"127.0.0.1:25575") + b"".join(rec(0, conn, f) for f in client[:2])
            + b"".join(rec(1, conn, f) for f in server) + b"".join(rec(0, conn, f) for f in client[2:]))


def dimension(dim): return [frame(0x01, pack("iBbBB", 42, 0, dim, 1, 20) + string("default")) if split(f)[0] == 0x01 and i < 5 else f
                            for i, f in enumerate(SERVER)]


async def until(cond, timeout=5.0):
    for _ in range(int(timeout / 0.02)):
        if cond(): return
        await asyncio.sleep(0.02)
    raise AssertionError("timed out")


class FileTap:
    """What SpectatorTap.java writes: a file per connection, named so that a later connection sorts later."""
    def __init__(self, d: Path): self.dir = d

    def write(self, data: bytes):
        conn = struct.unpack_from(">I", data, 1)[0]
        path = self.dir / f"{1000 + conn}-1-{conn}.tap"
        head = b"" if path.exists() else forwarder.TAP_MAGIC
        with open(path, "ab") as f: f.write(head + data)

    async def drain(self): pass

    def close(self): pass


class Watcher:
    async def join(self, port, name="Watcher"):
        self.r, self.w = await asyncio.open_connection("127.0.0.1", port)
        self.w.write(handshake() + frame(0, string(name))); await self.w.drain()
        self.split, self.got = Splitter(), []
        return self

    async def until(self, cond, timeout=5.0):
        async def read():
            while not cond(self.got):
                data = await self.r.read(65536)
                if not data: raise ConnectionError("closed")
                self.got += parsed(self.split.feed(data))
        await asyncio.wait_for(read(), timeout)
        return self.got

    def notices(self): return [Reader(b).string() for p, b in self.got if p == 0x02 and b"[spectators]" in b]

    def say(self, f): self.w.write(f)


def quests(*ids, merge=True):
    """A BQ quest_sync: the full database (merge False) or progress for the quests named."""
    return {"ID": "betterquesting:quest_sync", "merge": merge, "resetCompletion": True, "data": [
        {"questIDHigh": 0, "questIDLow": i, "progress": {"completed": [{"uuid": UUID, "claimed": True}], "tasks": []}} for i in ids]}


def names(name): return {"ID": "betterquesting:name_sync", "merge": True, "data": [{"uuid": UUID, "name": name, "isOP": True}]}
def message(mid, **kw): return {"ID": f"betterquesting:{mid}", **kw}


def book(frames):
    """The BQ messages in a stream of (pid, body), joined and decoded, in order."""
    j, out = bq.Joiner(), []
    for p, b in frames:
        if p != 0x3F: continue
        channel, data = payload(b)
        if channel == bq.CHANNEL and (whole := j.feed(data)): out.append(bq.read(whole))
    return out


def slots(got):
    """The 45 slots of the last window-1 S30 in got."""
    r = Reader(next(b for p, b in reversed(got) if p == 0x30 and b[0] == relay.WINDOW)); r.u("Bh")
    return [r.item() for _ in range(45)]


class EndToEnd(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.relay = relay.Relay(SECRET, Path(self.dir.name))
        self.relay.restore()
        self.wire = bytearray()  # everything that crossed the link
        linker = self.relay.linker

        async def spy(r, w):
            read = r.readexactly
            async def copy(n):
                b = await read(n); self.wire.extend(b); return b
            r.readexactly = copy
            await linker(r, w)
        self.links = await asyncio.start_server(spy, "127.0.0.1", 0)
        self.views = await asyncio.start_server(self.relay.viewer, "127.0.0.1", 0)
        self.vport = self.views.sockets[0].getsockname()[1]
        self.tap = FileTap(Path(self.dir.name) / "tap"); self.tap.dir.mkdir()
        self.tasks = []
        self.fw = self.forwarder()
        await until(lambda: self.relay.link is not None)

    def forwarder(self):
        fw = forwarder.Forwarder(("127.0.0.1", self.links.sockets[0].getsockname()[1]), SECRET, None)
        self.tasks += [asyncio.create_task(fw.dial()), asyncio.create_task(fw.follow(self.tap.dir, poll=0.02))]
        return fw

    async def asyncTearDown(self):
        for t in self.tasks: t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for s in (self.links, self.views): s.close()
        if self.relay.log: self.relay.log.close()
        self.dir.cleanup()

    async def play(self, conn, server=SERVER):
        before = self.fw.sessions
        self.tap.write(session(conn, server)); await self.tap.drain()
        await until(lambda: self.fw.sessions > before and self.relay.world is not None and self.relay.world.session == self.fw.open)

    async def test_late_viewer_sees_the_world_and_never_our_channels(self):
        await self.play(1)
        self.tap.write(rec(1, 1, block(7, 64, 7, 3))); await self.tap.drain()
        await until(lambda: self.relay.world.tail)
        v = await Watcher().join(self.vport)
        got = await v.until(lambda g: (0x23, pack("iBi", 7, 64, 7) + bytes([0, 3, 0])) in g)
        self.assertEqual(got[0][0], 0x02)                       # LoginSuccess, then the snapshot
        join = next(b for p, b in got if p == 0x01)
        self.assertEqual(Reader(join).u("iBb"), (state.VIEWER_EID, state.SPECTATOR, 0))
        self.assertNotIn(b"secret", bytes(self.wire))          # MB| payloads never leave the PC
        self.assertNotIn(b"secret", b"".join(b for _, b in got))

    async def test_a_refreshed_snapshot_is_exactly_the_mirror(self):
        await self.play(1)
        self.fw.snapshot()
        await until(lambda: not self.relay.world.tail and len(self.relay.world.snap) > 5)
        v = await Watcher().join(self.vport)
        want = parsed([f for f in self.fw.mirror.snapshot() if forwarder.public(f)])
        got = await v.until(lambda g: len(g) > len(want) + 1)
        self.assertEqual(got[1:1 + len(want)], want)                # then the relay's own: notices, sidebar

    async def test_a_new_session_replaces_the_world_in_place(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x08 for p, _ in g))
        first = self.fw.open
        self.tap.write(rec(forwarder.CLOSED, 1)); await self.tap.drain()
        await v.until(lambda g: any(relay.FROZEN in t for t in v.notices()))
        n = len(v.got)
        await self.play(2)
        self.assertNotEqual(self.fw.open, first)
        got = await v.until(lambda g: relay.BACK in "".join(v.notices()))
        fresh = got[n:]
        pids = [p for p, _ in fresh]
        self.assertNotIn(0x01, pids)                           # no JoinGame: the same login carries on
        self.assertNotIn(0x07, pids)                           # same dimension: no loading screen
        unloads = {Reader(b).u("ii") for p, b in fresh if p == 0x21 and Reader(b).u("ii?H")[2:] == (True, 0)}
        self.assertTrue({(0, 0), (3, 0)} <= unloads)                # the chunks the viewer still had
        self.assertIn(0x13, pids)                               # the old entities go
        self.assertIn((0x38, string("Agent") + pack("?h", False, 0)), fresh)
        self.assertLess(pids.index(0x13), pids.index(0x08))     # emptied before the new world arrives
        self.assertEqual(len(self.relay.viewers), 1)

    async def test_another_dimension_respawns_instead(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x08 for p, _ in g))
        self.tap.write(rec(forwarder.CLOSED, 1)); await self.tap.drain()
        await v.until(lambda g: any(relay.FROZEN in t for t in v.notices()))
        n = len(v.got)
        await self.play(2, dimension(-1))
        fresh = (await v.until(lambda g: relay.BACK in "".join(v.notices())))[n:]
        self.assertEqual(fresh[0][0], 0x07)
        self.assertEqual(Reader(fresh[0][1]).u("iBB"), (-1, 1, state.SPECTATOR))
        pids = [p for p, _ in fresh]
        self.assertEqual(set(pids[1:pids.index(0x39)]), {0x38})  # only the tab list: a new world is empty, scoreboard and all
        got = (await v.until(lambda g: 0x3D in [p for p, _ in g[n:]]))[n:]
        self.assertEqual([p for p, b in got if p == 0x3B], [0x3B])  # so the sidebar is made again, once

    async def test_the_sidebar_is_made_once_when_the_world_stays(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x3D for p, _ in g))
        self.tap.write(rec(forwarder.CLOSED, 1)); await self.tap.drain()
        await v.until(lambda g: any(relay.FROZEN in t for t in v.notices()))
        await self.play(2)
        await v.until(lambda g: relay.BACK in "".join(v.notices()))
        self.relay.draw_all(); await asyncio.sleep(0.2)
        self.assertEqual([p for p, _ in v.got].count(0x3B), 1)  # the client kept it: a second would drop the viewer

    async def test_a_dropped_link_resyncs_viewers_without_a_reconnect(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x08 for p, _ in g))
        first = self.fw.open
        self.relay.link.close()
        await v.until(lambda g: any(relay.DROPPED in t for t in v.notices()))
        await until(lambda: self.fw.open not in (None, first), timeout=8)
        got = await v.until(lambda g: relay.BACK in "".join(v.notices()))
        self.assertIn(0x13, [p for p, _ in got[-40:]])
        self.assertEqual(len(self.relay.viewers), 1)

    async def test_a_restarted_relay_comes_back_with_the_same_world(self):
        await self.play(1)
        self.tap.write(rec(1, 1, chunk(9, 9))); await self.tap.drain()
        await until(lambda: self.relay.world.tail)
        self.relay.log.close()
        again = relay.Relay(SECRET, Path(self.dir.name)); again.restore()
        self.assertEqual(again.world.session, self.relay.world.session)
        self.assertEqual(again.world.catch_up(), self.relay.world.catch_up())
        again.log.close()

    async def inventory(self, slot, item):
        self.tap.write(rec(1, 1, frame(0x2F, pack("bh", 0, slot) + item))); await self.tap.drain()

    async def test_invsee_shows_the_agents_inventory_live_and_read_only(self):
        await self.play(1)
        dirt, stone = pack("hbhh", 3, 5, 0, -1) + varint(5), pack("hbhh", 1, 64, 0, -1) + varint(64)
        await self.inventory(36, dirt)                          # the first hotbar slot
        await until(lambda: self.relay.inventory is not None and dirt in self.relay.inventory[1])
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x08 for p, _ in g))
        v.say(frame(0x01, string("/invsee")))
        got = await v.until(lambda g: any(p == 0x30 for p, _ in g))
        opened = next(b for p, b in got if p == 0x2D)
        r = Reader(opened); self.assertEqual(r.u("BB"), (relay.WINDOW, 0)); self.assertIn("Agent", r.string())
        self.assertEqual(r.u("B?"), (45, True))
        self.assertEqual(slots(got)[27], dirt)                 # main inventory first, then the hotbar
        await self.inventory(9, stone)
        got = await v.until(lambda g: len([p for p, _ in g if p == 0x30]) >= 2)
        self.assertEqual(slots(got)[0], stone)
        n = len(v.got)
        v.say(frame(0x0E, pack("bhbhb", relay.WINDOW, 0, 0, 1, 0) + stone))  # takes the stone
        got = (await v.until(lambda g: any(p == 0x30 for p, _ in g[n:])))[n:]
        self.assertIn((0x2F, pack("bh", -1, -1) + pack("h", -1)), got)  # the cursor is emptied again
        self.assertEqual(slots(got)[0], stone)                 # and the stone put back
        self.assertNotIn(b"/invsee", bytes(self.wire))         # nothing a viewer says reaches the link

    async def test_spectator_chat_reaches_viewers_only_and_slowly(self):
        await self.play(1)
        a, b = await Watcher().join(self.vport, "Alice"), await Watcher().join(self.vport, "Bob")
        for v in (a, b): await v.until(lambda g: any(p == 0x08 for p, _ in g))
        a.say(frame(0x01, string("hi §kthere")))
        a.say(frame(0x01, string("again")))
        chat = lambda g: [Reader(x).string() for p, x in g if p == 0x02 and b"[spectator] " in x]
        got = await b.until(lambda g: chat(g))
        self.assertIn('": hi kthere"', chat(got)[0])
        await a.until(lambda g: any("slower" in t for t in a.notices()))
        self.assertEqual(len(chat(b.got)), 1)
        a.say(frame(0x01, string("/nope")))
        await a.until(lambda g: any("/nope" in t for t in a.notices()))
        a.say(frame(0x14, string("/in")))
        got = await a.until(lambda g: any(p == 0x3A for p, _ in g))
        r = Reader(next(x for p, x in got if p == 0x3A)); self.assertEqual((r.varint(), r.string()), (1, "/invsee"))

    async def test_spectator_toggles_tp_finds_the_agent_and_night_vision_lasts(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        got = await v.until(lambda g: any(p == 0x08 for p, _ in g))
        modes = lambda g: [Reader(b).u("Bf")[1] for p, b in g if p == 0x2B and b[0] == 3]
        self.assertEqual(modes(got), [2.0])                        # adventure, flying
        self.assertIn((0x39, pack("Bff", 7, 0.1, 0.1)), got)
        v.say(frame(0x01, string("/spectator")))
        await v.until(lambda g: modes(g) == [2.0, state.SPECTATOR])
        v.say(frame(0x01, string("/spec")))
        await v.until(lambda g: modes(g) == [2.0, state.SPECTATOR, 2.0])
        place = lambda g: [Reader(b).u("dddff") for p, b in g if p == 0x08]
        n = len(v.got)
        v.say(frame(0x01, string("/tp")))
        await v.until(lambda g: place(g[n:]))
        x, y, z, yaw, _ = place(v.got[n:])[0]
        self.assertEqual((x, y, z, yaw), (101.0, 64.0 + state.EYE, -20.0, 45.0))  # where the agent last moved to
        self.tap.write(rec(0, 1, frame(0x04, pack("dddd?", 110.0, 70.0, 71.62, -5.0, True)))); await self.tap.drain()
        await until(lambda: self.relay.agent and self.relay.agent[0] == 110.0)
        n = len(v.got)
        v.say(frame(0x01, string("/tp")))
        await v.until(lambda g: place(g[n:]))
        self.assertEqual(place(v.got[n:])[0][:3], (110.0, 70.0 + state.EYE, -5.0))
        v.say(frame(0x01, string("/nightvision")))
        await v.until(lambda g: any(p == 0x1D for p, _ in g))
        effect = next(b for p, b in v.got if p == 0x1D)
        self.assertEqual(Reader(effect).u("iBBh"), (state.VIEWER_EID, relay.NIGHT_VISION, 0, 32767))
        n = len(v.got)
        nether = frame(0x07, pack("iBB", -1, 1, 0) + string("default"))
        self.tap.write(rec(1, 1, nether)); await self.tap.drain()   # a Respawn: a new player, so both come back
        got = (await v.until(lambda g: any(p == 0x1D for p, _ in g[n:])))[n:]
        pids = [p for p, _ in got]
        self.assertLess(pids.index(0x07), pids.index(0x1D))
        self.assertEqual(modes(got), [2.0])
        self.assertIsNone(self.relay.agent)                       # not known in the new dimension until it shows
        v.say(frame(0x01, string("/nv")))
        await v.until(lambda g: any(p == 0x1E for p, _ in g))
        self.assertNotIn(b"/tp", bytes(self.wire))

    async def test_a_late_viewer_reads_the_agents_quest_book_as_its_own(self):
        await self.play(1)
        sync = [message("main_sync", reset=True, respond=True), quests(1, 2, 3, merge=False), names("Agent"),
                quests(2), message("notification", mainText="done")]
        self.tap.write(b"".join(rec(1, 1, f) for m in sync for f in bq.frames(m))); await self.tap.drain()
        self.fw.mirror.payload_cap = 100                          # other mods' chatter cannot push the book out
        self.tap.write(b"".join(rec(1, 1, s3f("GalacticraftCore", bytes(200))) for _ in range(20))); await self.tap.drain()
        await until(lambda: self.fw.mirror.stats()["payloads"]["dropped"]["entries"] > 0)
        self.fw.snapshot()                                        # a refreshed snapshot, as for a late viewer
        await until(lambda: any(b"BQ_NET_CHAN" in f for _, f in self.relay.world.snap))
        v = await Watcher().join(self.vport)
        got = await v.until(lambda g: any(m["ID"].endswith("name_sync") and m["data"][0]["name"] == "Watcher" for m in book(g)))
        ids = [m["ID"].split(":")[1] for m in book(got)]
        self.assertEqual(ids, ["main_sync", "quest_sync", "name_sync", "quest_sync", "name_sync"])
        self.assertEqual(book(got)[-1]["data"], [{"uuid": UUID, "name": "Watcher", "isOP": 0}])
        n = len(v.got)
        for f in bq.frames(names("Agent")): self.tap.write(rec(1, 1, f))  # the agent's own names come back live
        await self.tap.drain()
        got = (await v.until(lambda g: len(book(g[n:])) >= 2))[n:]
        self.assertEqual([m["data"][0]["name"] for m in book(got)], ["Agent", "Watcher"])

    async def test_a_restarted_forwarder_reads_the_connection_again_and_viewers_stay(self):
        await self.play(1)
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x08 for p, _ in g))
        first = self.fw.open
        for t in self.tasks: t.cancel()                         # the forwarder dies without a word to the relay
        await asyncio.gather(*self.tasks, return_exceptions=True)
        self.fw.link.transport.abort()
        await v.until(lambda g: any(relay.DROPPED in t for t in v.notices()))
        self.tap.write(rec(1, 1, block(7, 64, 7, 3)))           # the game goes on meanwhile
        n = len(v.got)
        self.fw = self.forwarder()
        got = (await v.until(lambda g: relay.BACK in "".join(v.notices()[-1:])))[n:]
        self.assertNotEqual(self.fw.open, first)
        self.assertEqual(self.relay.world.tail, [])            # caught up before showing: nothing replayed as live
        pids = [p for p, _ in got]
        self.assertIn(0x13, pids)                               # the relay emptied the old world itself
        self.assertIn((0x23, pack("iBi", 7, 64, 7) + bytes([0, 3, 0])), got)
        self.assertEqual(len(self.relay.viewers), 1)

    async def test_a_server_list_ping_beside_the_game_changes_nothing(self):
        await self.play(1)
        first = self.fw.open
        ping = rec(forwarder.OPENED, 2, b"127.0.0.1:25575") + rec(0, 2, handshake(1)) + rec(0, 2, frame(0x00, b""))
        self.tap.write(ping + rec(1, 2, frame(0x00, string("{}"))) + rec(forwarder.CLOSED, 2))
        self.tap.write(rec(1, 1, block(7, 64, 7, 3)))
        await until(lambda: any(block(7, 64, 7, 3) in t for t in self.relay.world.tail))
        self.assertEqual(self.fw.open, first)

    async def test_the_sidebar_and_the_agents_lines(self):
        overlay = Path(self.dir.name) / "overlay"; overlay.mkdir()
        live = lambda quest: (overlay / "live.json").write_text(json.dumps({
            "goal": {"chapter": "Tier 0.5 - Steam", "quest": quest}, "run": {"model": "gpt-6.1-sol"},
            "stats": {"claims": 28}, "budget": {"startedAt": time.time() - 3725}}))
        live("Ooo, Shiny! (Not Platinum!)"); (overlay / "feed.jsonl").write_text('{"kind": "say", "text": "old news"}\n')
        self.tasks.append(asyncio.create_task(self.fw.narrate(overlay, poll=0.02)))
        await self.play(1)
        await until(lambda: self.relay.status.get("quest"))
        v = await Watcher().join(self.vport)
        await v.until(lambda g: any(p == 0x3D for p, _ in g))
        made = lambda g: [b for p, b in g if p == 0x3B and Reader(b).string() == board.OBJECTIVE]
        self.assertEqual(len(made(v.got)), 1)
        rows = sidebar(v.got)
        self.assertIn("Ooo, Shiny! (Not Platinum!)", rows)
        self.assertIn("Running 1h 02m", rows)
        live("Steam Power")                                       # a change updates the row, never re-creates
        await v.until(lambda g: "Steam Power" in sidebar(g))
        with open(overlay / "feed.jsonl", "a") as f:
            f.write('{"kind": "say", "text": "Smelting bronze."}\n{"kind": "mark", "text": "quest claimed: Steam Power"}\n')
        got = await v.until(lambda g: any(b"Steam Power" in b and p == 0x02 for p, b in g))
        lines = [Reader(b).string() for p, b in got if p == 0x02]
        self.assertTrue(any("Smelting bronze." in t and '"Agent"' in t for t in lines))
        self.assertFalse(any("old news" in t for t in lines))      # the story starts when the forwarder does
        n = len(v.got)
        nether = frame(0x07, pack("iBB", -1, 1, 0) + string("default"))
        self.tap.write(rec(1, 1, nether))                           # another dimension: a new client world
        got = (await v.until(lambda g: made(g[n:])))[n:]
        pids = [p for p, _ in got]
        self.assertLess(pids.index(0x07), pids.index(0x3B))
        self.assertEqual(len(made(v.got)), 2)

    async def test_a_wrong_token_is_refused(self):
        bad = forwarder.Forwarder(self.fw.relay, b"not the secret, wrong one", None)
        task = asyncio.create_task(bad.dial())
        await asyncio.sleep(0.3)
        self.assertIsNot(self.relay.link, bad.link)
        task.cancel()


class Tls(unittest.IsolatedAsyncioTestCase):
    async def test_a_pinned_certificate_links_and_another_does_not(self):
        import shutil, ssl, subprocess
        if not shutil.which("openssl"): self.skipTest("needs openssl to make certificates")
        d = tempfile.TemporaryDirectory(); self.addCleanup(d.cleanup)
        for n in ("relay", "other"):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=relay",
                            "-keyout", f"{d.name}/{n}.key", "-out", f"{d.name}/{n}.pem"], check=True, capture_output=True)
        server = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH); server.load_cert_chain(f"{d.name}/relay.pem", f"{d.name}/relay.key")
        r = relay.Relay(SECRET)
        links = await asyncio.start_server(r.linker, "127.0.0.1", 0, ssl=server); self.addCleanup(links.close)
        port = links.sockets[0].getsockname()[1]
        for pem, links_up in (("other", False), ("relay", True)):
            pinned = ssl.create_default_context(cafile=f"{d.name}/{pem}.pem"); pinned.check_hostname = False
            fw = forwarder.Forwarder(("127.0.0.1", port), SECRET, pinned)
            task = asyncio.create_task(fw.dial())
            try:
                if links_up: await until(lambda: r.link is not None)
                else: await asyncio.sleep(0.5); self.assertIsNone(r.link)
            finally:
                task.cancel()


class OnlineLogin(unittest.IsolatedAsyncioTestCase):
    """A viewer's login the way a 1.7.10 client does it against an online-mode server, Mojang stood in for."""
    async def asyncSetUp(self):
        from harness.mirror import proxy
        self.asked = []
        def verify(name, digest):
            self.asked.append((name, digest))
            return ("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch") if name.lower() == "notch" else None
        self.stage = proxy.Stage(online=True); self.stage.auth.verify = verify
        self.server = await asyncio.start_server(self.stage.viewer, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self): self.server.close()

    async def login(self, name):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        from harness.mirror import auth, proxy
        r, w = await asyncio.open_connection("127.0.0.1", self.port)
        w.write(handshake() + frame(0, string(name)))
        pid, body = split(await proxy.read_frame(r))
        self.assertEqual(pid, 0x01)
        q = Reader(body); server_id = q.string(); der = q.take(q.u("h")); token = q.take(q.u("h"))
        key, secret = serialization.load_der_public_key(der), os.urandom(16)
        sealed = [key.encrypt(b, padding.PKCS1v15()) for b in (secret, token)]
        w.write(frame(0x01, b"".join(pack("h", len(b)) + b for b in sealed)))
        self.digest = auth.server_hash(server_id, secret, der)
        return split(await proxy.read_frame(auth.Sealed(r, w, secret))), w

    async def test_the_confirmed_account_is_who_joins(self):
        (pid, body), w = await self.login("notch")
        self.assertEqual(pid, 0x02)
        r = Reader(body); self.assertEqual((r.string(), r.string()), ("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch"))
        self.assertEqual(self.asked, [("notch", self.digest)])
        await until(lambda: len(self.stage.viewers) == 1)
        self.assertEqual(next(iter(self.stage.viewers)).uuid, "069a79f4-44e9-4726-a5be-fca90e38aaf5")
        w.close()

    async def test_an_unconfirmed_account_is_turned_away(self):
        (pid, body), w = await self.login("Herobrine")
        self.assertEqual(pid, 0x00)
        self.assertIn("Mojang", Reader(body).string())
        self.assertEqual(self.stage.viewers, set())
        w.close()

    def test_the_digest_is_minecrafts(self):
        from harness.mirror import auth
        import hashlib
        # Minecraft's documented examples: sha1("Notch") and sha1("jeb_") as signed hex
        for text, want in (("Notch", "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"), ("jeb_", "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1")):
            self.assertEqual(auth.server_hash(text, b"", b""), want)


def sidebar(got) -> list[str]:
    """The sidebar rows as a 1.7.10 client would draw them, from the team packets in got."""
    rows = {}
    for p, b in got:
        if p != 0x3E: continue
        r = Reader(b); name, mode = r.string(), r.u("b")
        if mode in (0, 2):
            r.string(); pre, suf = r.string(), r.string()
            assert len(pre) <= 16 and len(suf) <= 16, (pre, suf)
            rows[name] = board.CODE.sub("", pre + suf)          # what shows: the colours go, the text joins up
    return [rows[board.team(i)] for i in range(board.ROWS) if board.team(i) in rows]


class QuestBook(unittest.TestCase):
    def test_a_message_survives_its_parts(self):
        msg = message("quest_sync", merge=False, blob=os.urandom(50000), data=[{"uuid": UUID, "n": 3}])
        fs = bq.frames(msg)
        self.assertEqual(len(fs), 3)                              # 20480-byte parts, as BQ splits them
        self.assertEqual(book(parsed(fs)), [msg])

    def test_the_book_keeps_what_a_late_viewer_needs(self):
        b, seq = bq.Book(), 0
        def feed(m):
            nonlocal seq
            for f in bq.frames(m):
                seq += 1; b.feed(seq, f, payload(split(f)[1])[1])
        for m in [message("main_sync", reset=True), quests(1), message("main_sync", reset=True),  # a reset forgets the past
                  message("setting_sync", data={}), quests(1, 2, merge=False), names("Agent"), message("cache_sync", data={}),
                  quests(1), quests(2), quests(1), message("notification"), message("cache_sync", data={"x": 1})]:
            feed(m)
        kept = book(parsed(f for _, f in b.entries()))
        self.assertEqual([(m["ID"].split(":")[1], [q["questIDLow"] for q in m.get("data", []) if "questIDLow" in q]) for m in kept],
                         [("main_sync", []), ("setting_sync", []), ("quest_sync", [1, 2]), ("name_sync", []),
                          ("quest_sync", [2]), ("quest_sync", [1]), ("cache_sync", [])])
        self.assertEqual(kept[-1]["data"], {"x": 1})
        feed(quests(1, 2))                                        # covers both: the older two go
        self.assertEqual(sum(m["ID"].endswith("quest_sync") for m in book(parsed(f for _, f in b.entries()))), 2)


class Board(unittest.TestCase):
    def test_a_long_line_splits_and_keeps_its_colour(self):
        pre, suf = board.halves("§7Quests claimed §a28")
        self.assertEqual((pre, suf), ("§7Quests claimed", "§7 §a28"))
        pre, suf = board.halves("§fA quest with a rather long name indeed")
        self.assertEqual(len(pre), 16); self.assertEqual(len(suf), 16); self.assertTrue(suf.startswith("§f"))
        self.assertFalse(board.halves("x" * 15 + "§a" + "y")[0].endswith("§"))

    def test_rows_are_unique_and_fixed(self):
        self.assertEqual(len({board.entry(i) for i in range(board.ROWS)}), board.ROWS)
        self.assertEqual(len(board.lines({}, 0)), board.ROWS)
        self.assertEqual(board.updated(board.lines({}, 1), board.lines({}, 1)), [])


class Clearing(unittest.TestCase):
    def test_only_live_objectives_and_teams_are_removed(self):
        m = state.Mirror(); m.login("u", "Agent")
        for f in SERVER[:4]: m.server(f, *split(f))
        objective = lambda n, mode: frame(0x3B, string(n) + string("v") + pack("b", mode))
        team = lambda n, mode: frame(0x3E, string(n) + pack("b", mode))
        for f in [objective("a", 0), objective("b", 0), objective("b", 1), team("t", 0), team("t", 2), team("u", 0), team("u", 1)]:
            m.server(f, *split(f))
        world, _ = forwarder.clearing(m)
        self.assertIn(frame(0x3B, string("a") + string("") + pack("b", 1)), world)
        self.assertNotIn(frame(0x3B, string("b") + string("") + pack("b", 1)), world)
        self.assertEqual([f for f in world if split(f)[0] == 0x3E], [frame(0x3E, string("t") + pack("b", 1))])

    def test_link_entries_roundtrip(self):
        entries = [(0, frame(1, b"x")), (3, frame(0x21, b"y" * 300))]
        self.assertEqual(link.unheaded(link.headed({"k": 1}, entries)), ({"k": 1}, entries))

    def test_public(self):
        self.assertFalse(forwarder.public(s3f("MB|Clock", b"secret")))
        self.assertTrue(forwarder.public(s3f("FML", b"\x02")))
        self.assertTrue(forwarder.public(block(1, 2, 3)))


if __name__ == "__main__":
    unittest.main()
