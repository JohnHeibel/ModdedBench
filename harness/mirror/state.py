# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""What a late viewer needs to see the host's world, kept bounded, and the live stream rewritten for viewers.

Fed the host session's play frames in order (server() for S->C, client() for C->S), it returns the frames to
broadcast to viewers and can produce a snapshot for a viewer joining now. It never re-encodes chunk or block
packets (NotEnoughIDs changes their payloads); it reads only their leading coordinates.
"""
from __future__ import annotations
import heapq, json, math
from collections import deque
from .wire import Reader, frame, pack, payload, string, varint

VIEWER_EID = 0x7FFFFFFF
SPECTATOR = 3  # Et Futurum Requiem's spectator game type (enableSpectatorMode); a client without it falls back to survival
EYE = 1.62  # S08 carries the eye height; C04/C06 and entity positions carry the feet
# Server packets about the host or its screens, never shown to viewers.
DROP = {0x00, 0x06, 0x09, 0x1F, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x36, 0x37, 0x39, 0x3A, 0x01}
SPAWNS = {0x0C, 0x0E, 0x0F, 0x10, 0x11}
ARMOR = {5: 4, 6: 3, 7: 2, 8: 1}  # player container slot -> equipment slot


def fixed(v: float) -> int: return math.floor(v * 32)
def angle(deg: float) -> int: return int(deg * 256 / 360) & 0xFF


def abilities() -> bytes:
    return frame(0x39, pack("Bff", 1 | 2 | 4, 0.1, 0.1))  # invulnerable, flying, may fly


def disconnect(text: str, login: bool = False) -> bytes:
    return frame(0x00 if login else 0x40, string(json.dumps({"text": text})))


class Entity:
    __slots__ = ("spawn", "pos", "moved", "meta", "props", "equip", "effects", "attach", "head", "vel")

    def __init__(self, spawn=None, pos=None):
        self.spawn, self.pos, self.moved = spawn, pos, False  # pos: [x, y, z (fixed point), yaw, pitch (bytes)] or None
        self.meta, self.props, self.equip, self.effects, self.attach = {}, {}, {}, {}, {}
        self.head = self.vel = None

    def extras(self, eid: int) -> list[bytes]:
        out = []
        if self.moved and self.pos: out.append(frame(0x18, pack("iiiiBB", eid, *self.pos)))
        if self.meta: out.append(frame(0x1C, pack("i", eid) + b"".join(self.meta.values()) + b"\x7f"))
        if self.props: out.append(frame(0x20, pack("ii", eid, len(self.props)) + b"".join(self.props.values())))
        out += [*self.equip.values(), *self.effects.values(), *(f for f in (self.head, self.vel) if f)]
        return out


class Mirror:
    def __init__(self, payload_cap: int = 8 << 20, chat_keep: int = 20, score_keep: int = 2000):
        self.payload_cap, self.chat_keep, self.score_keep = payload_cap, chat_keep, score_keep
        self.reset()

    def reset(self, uuid: str = "", name: str = ""):
        self.uuid, self.name = uuid, name
        self.prelude, self.join, self.eid = [], None, 0
        self.hs, self.gates = 0, []  # FML|HS messages the host had sent before each prelude frame
        self.seq = self.gen = 0
        self.dim = None; self.respawn = None  # (seq, frame) of the last dimension change, replayed in order
        self.time = self.spawnpos = None
        self.players, self.scores, self.chat = {}, deque(maxlen=self.score_keep), deque(maxlen=self.chat_keep)
        self.payloads, self.pinned, self.payload_bytes, self.dropped = {}, set(), 0, [0, 0]
        self.inv, self.held = {}, 0
        self.hpos, self.avatar, self.place = None, False, False  # hpos: [x, feet y, z, yaw, pitch]
        self.chunked = False  # any chunk seen: payloads before it are pinned (login-time config sync)
        self._world()

    def _world(self):
        self.chunks, self.log, self.by_chunk, self.keyed = {}, {}, {}, {}
        self.ents, self.weather, self.unload = {}, {}, None

    @property
    def ready(self) -> bool:
        return self.join is not None and self.hpos is not None

    # ---- chunk-scoped log
    def _new_gen(self, c):
        self._end_gen(c)
        self.gen += 1; self.chunks[c] = self.gen

    def _end_gen(self, c):
        if self.chunks.pop(c, None) is None: return
        for s in self.by_chunk.pop(c, ()):
            e = self.log.get(s)
            if e and not any(self.chunks.get(cc) == g for cc, g in e[1]): self._forget(s)

    def _forget(self, s):
        f, deps, key = self.log.pop(s)
        for c, _ in deps:
            if c in self.by_chunk: self.by_chunk[c].discard(s)
        if key is not None and self.keyed.get(key) == s: del self.keyed[key]

    def _store(self, f, cs, key=None):
        deps = tuple((c, self.chunks[c]) for c in cs if c in self.chunks)
        if not deps: return
        if key is not None and (old := self.keyed.get(key)) is not None and old in self.log: self._forget(old)
        self.log[self.seq] = (f, deps, key)
        for c, _ in deps: self.by_chunk.setdefault(c, set()).add(self.seq)
        if key is not None: self.keyed[key] = self.seq

    def _block(self, f, body, kind, fmt):
        x, y, z, *rest = Reader(body).u(fmt)
        self._store(f, [(x >> 4, z >> 4)], (kind, x, y, z, *rest))

    # ---- entities
    def _ent(self, eid: int, create: bool = False):
        e = self.ents.get(eid)
        if e is None and (create or eid == self.eid): e = self.ents[eid] = Entity()
        return e

    def _spawn(self, f, pid, body):
        r = Reader(body)
        eid = r.varint()
        if pid == 0x0C:
            r.take(r.varint()); r.string()
            for _ in range(r.varint()): r.string(); r.string(); r.string()
            pos = [*r.u("iii"), *r.u("BB")]
        elif pid == 0x0E: r.take(1); x, y, z, p, yaw = r.u("iiiBB"); pos = [x, y, z, yaw, p]
        elif pid == 0x0F: r.take(1); pos = list(r.u("iiiBB"))
        elif pid == 0x11: pos = [*r.u("iii"), 0, 0]
        else: pos = None  # paintings sit on a block and never move
        self.ents[eid] = Entity(f, pos)

    def _fml(self, f, data) -> bool:
        """FML entity spawn/adjust messages; True when consumed into an entity record."""
        r = Reader(data, 1)
        if data[0] == 2:
            eid = r.u("i"); r.string(); r.take(4)
            self.ents[eid] = Entity(f, [*r.u("iii"), *r.u("BB")])
            return True
        if data[0] == 3:
            eid, *xyz = r.u("iiii")
            if (e := self.ents.get(eid)) and e.pos: e.pos[:3] = xyz; e.moved = True
            return True
        return False

    # ---- host avatar
    def _item(self, slot: int) -> bytes:
        return self.inv.get(slot, b"\xff\xff")

    def _equipment(self) -> list[bytes]:
        """S04s for host equipment that changed; kept in the host record so snapshots have them."""
        out, host = [], self._ent(self.eid, True)
        for eq, slot in [(0, 36 + self.held), *((e, s) for s, e in ARMOR.items())]:
            f = frame(0x04, pack("ih", self.eid, eq) + self._item(slot))
            if host.equip.get(eq) != f:
                host.equip[eq] = f
                if self.avatar: out.append(f)
        return out

    def _avatar_spawn(self) -> bytes:
        x, y, z, yaw, pitch = self.hpos
        held = Reader(self._item(36 + self.held)).u("h")
        return frame(0x0C, varint(self.eid) + string(self.uuid) + string(self.name[:16]) + varint(0)
                     + pack("iiiBBh", fixed(x), fixed(y), fixed(z), angle(yaw), angle(pitch), max(held, 0))
                     + b"\x00\x00\x7f")  # one flags entry: a 1.7.10 client reads an empty list as null and crashes

    def _avatar_move(self) -> list[bytes]:
        if self.hpos is None or self.join is None: return []
        if not self.avatar:
            self.avatar = True
            host = self._ent(self.eid, True)
            return [self._avatar_spawn(), *host.extras(self.eid), *(host.attach.values())]
        x, y, z, yaw, pitch = self.hpos
        return [frame(0x18, pack("iiiiBB", self.eid, fixed(x), fixed(y), fixed(z), angle(yaw), angle(pitch))),
                frame(0x19, pack("iB", self.eid, angle(yaw)))]

    def _place(self) -> bytes:
        x, y, z, yaw, pitch = self.hpos
        return frame(0x08, pack("dddff?", x, y + EYE, z, yaw, pitch, False))

    # ---- streams
    def login(self, uuid: str, name: str):
        self.reset(uuid, name)

    def client(self, pid: int, body: bytes) -> list[bytes]:
        """A host C->S play packet: moves the avatar; nothing the host sends is relayed."""
        if self.join is None:
            if pid == 0x17 and Reader(body).string() == "FML|HS": self.hs += 1
            return []
        r = Reader(body)
        if pid in (0x04, 0x05, 0x06):
            pos = self.hpos or [0.0, 0.0, 0.0, 0.0, 0.0]
            if pid != 0x05:
                x, y, stance, z = r.u("dddd")
                if y != -999 or stance != -999: pos[:3] = x, y, z  # -999: riding, x/z carry motion; the vehicle moves the avatar
            if pid != 0x04: pos[3:] = r.u("ff")
            if self.hpos is None and pid == 0x05: return []
            self.hpos = pos
            return self._avatar_move()
        if pid == 0x0A and r.u("ib")[1] == 1 and self.avatar: return [frame(0x0B, varint(self.eid) + b"\x00")]
        if pid == 0x09: self.held = r.u("h"); return self._equipment()
        return []

    def server(self, f: bytes, pid: int, body: bytes) -> list[bytes]:
        """A host S->C play frame; returns what viewers get live."""
        self.seq += 1
        if self.join is None:
            self.prelude.append(f); self.gates.append(self.hs)
            if pid == 0x01:
                r = Reader(body)
                self.eid, gm, self.dim = r.u("iBb")
                self.join = len(self.prelude) - 1
                self.prelude[-1] = frame(0x01, pack("iBb", VIEWER_EID, gm & 8 | SPECTATOR, self.dim) + body[6:])
            return []
        r = Reader(body)
        if pid in DROP:
            if pid == 0x09: self.held = r.u("b"); return self._equipment()
            if pid == 0x2F and r.u("b") == 0: s = r.u("h"); self.inv[s] = r.item(); return self._equipment()
            if pid == 0x30 and r.u("B") == 0: self.inv = {i: r.item() for i in range(r.u("h"))}; return self._equipment()
            return []
        if pid == 0x08:
            x, y, z, yaw, pitch = r.u("dddff")
            self.hpos = [x, y - EYE, z, yaw, pitch]
            out = [f] if self.place else []
            self.place = False
            return out + self._avatar_move()
        if pid == 0x07:
            dim, diff, _ = r.u("iBB")
            out = []
            if dim != self.dim:
                self._world(); self.dim = dim
                g = frame(0x07, pack("iBB", dim, diff, SPECTATOR) + body[6:])
                self.respawn, self.place = (self.seq, g), True
                out = [g, abilities()]
            elif self.avatar:
                out = [frame(0x13, pack("bi", 1, self.eid))]
            self.hpos, self.avatar = None, False
            self._equipment()
            return out
        if pid == 0x3F:
            channel, data = payload(body)
            if channel.startswith("MB|"): return []
            if channel == "FML" and data:
                if data[0] == 1: return []  # OpenGui: the host's screen
                if self._fml(f, data): return [f]
            self.payloads[self.seq] = f; self.payload_bytes += len(f)
            if not self.chunked: self.pinned.add(self.seq)
            self._cap_payloads()
            return [f]
        if pid == 0x2B:
            reason = r.u("B")
            if reason in (3, 4, 5): return []  # gamemode, credits, demo
            if reason in (1, 2, 7, 8): self.weather[1 if reason < 3 else reason] = f
            return [f]
        if pid == 0x21:
            cx, cz, full, mask = r.u("ii?H")
            c = (cx, cz); self.chunked = True
            if full and not mask:
                self._end_gen(c); self.unload = self.unload or body[8:]
            else:
                if full: self._new_gen(c)
                self._store(f, [c])
        elif pid == 0x26:
            n, size, _ = r.u("hi?"); r.take(size)
            cs = [r.u("iiHH")[:2] for _ in range(n)]
            for c in cs: self._new_gen(c)
            self._store(f, cs); self.chunked = True
        elif pid == 0x22: self._store(f, [tuple(r.u("ii"))])
        elif pid == 0x23: self._block(f, body, "b", "iBi")
        elif pid == 0x24: self._block(f, body, "a", "ihi")
        elif pid == 0x33: self._block(f, body, "s", "ihi")
        elif pid == 0x35: self._block(f, body, "t", "ihiB")
        elif pid == 0x03: self.time = f
        elif pid == 0x05: self.spawnpos = f
        elif pid == 0x02: self.chat.append(f)
        elif pid == 0x38:
            name = r.string()
            if r.u("?"): self.players[name] = f
            else: self.players.pop(name, None)
        elif 0x3B <= pid <= 0x3E: self.scores.append(f)
        elif pid == 0x40: return []
        elif pid in SPAWNS: self._spawn(f, pid, body)
        elif pid == 0x13:
            for _ in range(r.u("b")): self.ents.pop(r.u("i"), None)
        elif 0x12 <= pid <= 0x20 or pid == 0x04:
            self._entity(pid, r, f)
        return [f]

    def _entity(self, pid, r, f):
        eid = r.u("i")
        e = self._ent(eid)
        if e is None: return
        if pid in (0x15, 0x16, 0x17, 0x18) and e.pos is not None and eid != self.eid:
            if pid == 0x18: e.pos[:] = r.u("iiiBB")
            else:
                if pid != 0x16:
                    dx, dy, dz = r.u("bbb"); e.pos[0] += dx; e.pos[1] += dy; e.pos[2] += dz
                if pid != 0x15: e.pos[3:] = r.u("BB")
            e.moved = True
        elif pid == 0x1C: e.meta.update(r.metadata())
        elif pid == 0x20:
            for _ in range(r.u("i")):
                start = r.pos; key = r.string(); r.take(8)
                r.take(r.u("h") * 25)  # modifiers: uuid, amount, operation
                e.props[key] = r.b[start:r.pos]
        elif pid == 0x04 and eid != self.eid: e.equip[r.u("h")] = f
        elif pid == 0x1D: e.effects[r.u("b")] = f
        elif pid == 0x1E: e.effects.pop(r.u("b"), None)
        elif pid == 0x1B: r.take(4); e.attach[r.u("B")] = f
        elif pid == 0x19: e.head = f
        elif pid == 0x12: e.vel = f

    def _cap_payloads(self):
        if self.payload_bytes <= self.payload_cap: return
        for s in list(self.payloads):
            if self.payload_bytes <= self.payload_cap: break
            if s in self.pinned: continue
            n = len(self.payloads.pop(s)); self.payload_bytes -= n
            self.dropped[0] += 1; self.dropped[1] += n

    # ---- late join
    def gated(self) -> list[tuple[int, bytes]]:
        """The snapshot, each frame with the FML|HS messages a viewer must have sent first. The server's side of the
        Forge handshake waits for the client's replies, and a client handed JoinGame mid-handshake crashes."""
        snap, last = self.snapshot(), max(self.gates, default=0)
        return [(self.gates[i] if i < len(self.gates) else last, f) for i, f in enumerate(snap)]

    def snapshot(self) -> list[bytes]:
        """Frames that bring a freshly logged-in viewer to the host's present, after LoginSuccess."""
        out = [*self.prelude, abilities()]
        out += [f for f in (self.spawnpos, self.time) if f] + [*self.weather.values(), *self.players.values(), *self.scores]
        ordered = [((s, e[0]) for s, e in self.log.items()), iter(self.payloads.items())]
        if self.respawn: ordered.append(iter([self.respawn]))
        out += [f for _, f in heapq.merge(*ordered, key=lambda t: t[0])]
        if self.unload:  # a bulk packet still held for one chunk also reloads its unloaded neighbours
            stale = {c for e in self.log.values() for c, _ in e[1] if c not in self.chunks}
            out += [frame(0x21, pack("ii", *c) + self.unload) for c in sorted(stale)]
        attach = []
        for eid, e in self.ents.items():
            if e.spawn is None: continue
            out += [e.spawn, *e.extras(eid)]; attach += e.attach.values()
        out += attach
        if self.avatar:
            host = self._ent(self.eid, True)
            out += [self._avatar_spawn(), *host.extras(self.eid), *host.attach.values()]
        out += self.chat
        if self.hpos: out.append(self._place())
        return out

    def stats(self) -> dict:
        return {"host": self.name, "joined": self.join is not None, "dimension": self.dim, "chunks": len(self.chunks),
                "chunk_log": {"entries": len(self.log), "bytes": sum(len(e[0]) for e in self.log.values())},
                "entities": sum(e.spawn is not None for e in self.ents.values()),
                "payloads": {"entries": len(self.payloads), "bytes": self.payload_bytes, "cap": self.payload_cap,
                             "pinned": len(self.pinned & self.payloads.keys()),
                             "dropped": {"entries": self.dropped[0], "bytes": self.dropped[1]}},
                "prelude_bytes": sum(map(len, self.prelude)), "avatar": self.avatar}
