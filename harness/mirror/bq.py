# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""BetterQuesting's sync (3.7.15-GTNH), kept for late viewers and turned into each viewer's view of the agent's book.

BQ talks on the Forge channel BQ_NET_CHAN, discriminator 0. A message is one gzipped NBT compound with a string ID,
sent in parts {size, index, end, data} of up to 20480 bytes, each part a short length and gzipped NBT. A client
joins one message at a time, in order. The server sends a player only that player's progress, keyed by its UUID,
and the quest book shows the progress of whichever UUID the name cache (name_sync) gives the viewer's own name,
else the viewer's account UUID. So one name_sync that gives the agent's UUID the viewer's name shows the viewer the
agent's book, and nothing else needs rewriting.
"""
from __future__ import annotations
import gzip, struct
from .wire import frame, pack, string

CHANNEL, PART = "BQ_NET_CHAN", 20480
NAMES = b"\x08\x00\x02ID\x00\x18betterquesting:name_sync"  # a name_sync's ID tag, as NBT writes it


# ---- NBT, as much as BQ needs
def read(b: bytes) -> dict:
    """An uncompressed root compound as Python values: compounds are dicts, lists and arrays are lists or bytes."""
    pos = 0

    def take(n):
        nonlocal pos
        v = b[pos:pos + n]; pos += n
        if len(v) < n: raise ValueError("truncated NBT")
        return v

    def u(fmt):
        s = struct.Struct(">" + fmt)
        return s.unpack(take(s.size))[0]

    def value(t):
        if 1 <= t <= 6: return u("bhiqfd"[t - 1])
        if t == 7: return take(u("i"))
        if t == 8: return take(u("H")).decode("utf-8", "replace")
        if t == 9:
            et, n = u("b"), u("i")
            return [value(et) for _ in range(n)]
        if t == 10:
            d = {}
            while (tt := u("b")) != 0:
                k = take(u("H")).decode("utf-8", "replace"); d[k] = value(tt)
            return d
        if t == 11: return list(struct.unpack(f">{(n := u('i'))}i", take(4 * n)))
        raise ValueError(f"NBT tag {t}")

    if u("b") != 10: raise ValueError("NBT root is not a compound")
    take(u("H"))
    return value(10)


def write(d: dict) -> bytes:
    """A root compound from Python values: bool is a byte, int an int, str a string, bytes a byte array."""
    def tag(v):
        return 1 if isinstance(v, bool) else 3 if isinstance(v, int) else 8 if isinstance(v, str) else \
            7 if isinstance(v, bytes) else 9 if isinstance(v, list) else 10

    def value(v):
        t = tag(v)
        if t == 1: return pack("b", v)
        if t == 3: return pack("i", v)
        if t == 8: b = v.encode(); return pack("H", len(b)) + b
        if t == 7: return pack("i", len(v)) + v
        if t == 9: return pack("bi", tag(v[0]) if v else 0, len(v)) + b"".join(map(value, v))
        return b"".join(pack("b", tag(x)) + value(k) + value(x) for k, x in v.items()) + b"\x00"

    return b"\x0a\x00\x00" + value(d)


# ---- messages and their parts
def frames(msg: dict) -> list[bytes]:
    """msg as the S3F frames a BQ server would send."""
    data, out = gzip.compress(write(msg)), []
    for at in range(0, len(data), PART):
        part = gzip.compress(write({"size": len(data), "index": at, "end": at + PART >= len(data), "data": data[at:at + PART]}))
        body = b"\x00" + pack("h", len(part)) + part
        out.append(frame(0x3F, string(CHANNEL) + pack("H", len(body)) + body))
    return out


def named(uuid: str, name: str) -> list[bytes]:
    """The name_sync that makes a viewer called name read uuid's progress as its own (never as an operator, which
    with edit mode on would open the editors)."""
    return frames({"ID": "betterquesting:name_sync", "merge": True, "data": [{"uuid": uuid, "name": name, "isOP": False}]})


class Joiner:
    """Puts one stream's parts back together, the way the client does."""
    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes) -> bytes | None:
        """data: a BQ_NET_CHAN payload. The whole message, uncompressed, when this part ends one."""
        if data[:1] != b"\x00": return None
        part = read(gzip.decompress(data[3:3 + struct.unpack_from(">h", data, 1)[0]]))
        if part["index"] == 0: self.buf = bytearray()
        self.buf[part["index"]:part["index"] + len(part["data"])] = part["data"]
        if not part["end"]: return None
        whole, self.buf = bytes(self.buf), bytearray()
        return gzip.decompress(whole)


class Book:
    """The agent's BQ sync as a late viewer needs it, in order: a reset (main_sync) clears what came before; a
    message without merge replaces earlier ones with its ID; quest progress keeps only each quest's latest; other
    merges are all kept. Notifications are one-off pop-ups and are not kept."""
    KEEP_ALL = {"bq_standard:choice_reward"}

    def __init__(self):
        self.joiner, self.parts = Joiner(), []
        self.msgs = {}  # first part's seq -> ((kind, ID, quests), [(seq, frame)])

    def feed(self, seq: int, f: bytes, data: bytes):
        self.parts.append((seq, f))
        try:
            whole = self.joiner.feed(data)
        except Exception:  # noqa: BLE001 - one unreadable message is kept as it came, never dropped
            whole = b""
        if whole is None: return
        parts, self.parts = self.parts, []
        kind, mid, quests = self._key(whole) if whole else ("keep", "", None)
        if kind == "drop": return
        if kind == "reset": self.msgs.clear()
        for s in [s for s, ((k, m, q), _) in self.msgs.items()
                  if (kind == "progress" and k == "progress" and q <= quests)
                  or (kind == "replace" and (m == mid or (k == "progress" and mid.endswith("quest_sync"))))]:
            del self.msgs[s]
        self.msgs[parts[0][0]] = ((kind, mid, quests), parts)

    def _key(self, whole: bytes) -> tuple[str, str, frozenset | None]:
        """(what the message does to earlier ones, its ID, the quests whose progress it carries)."""
        m = read(whole)
        mid = m.get("ID", "")
        if mid == "betterquesting:notification": return "drop", mid, None
        if mid == "betterquesting:main_sync" and m.get("reset"): return "reset", mid, None
        if mid == "betterquesting:quest_sync" and m.get("merge"):
            return "progress", mid, frozenset((q.get("questIDHigh"), q.get("questIDLow")) for q in m.get("data", []))
        if mid in self.KEEP_ALL or m.get("merge"): return "keep", mid, None
        return "replace", mid, None

    def entries(self) -> list[tuple[int, bytes]]:
        return sorted(p for _, parts in self.msgs.values() for p in parts)

    def stats(self) -> dict:
        return {"messages": len(self.msgs), "bytes": sum(len(f) for _, parts in self.msgs.values() for _, f in parts)}
