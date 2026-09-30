# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Minecraft 1.7.10 (protocol 5, no compression) wire format: frames, varints, strings, items, metadata."""
from __future__ import annotations
import hashlib, struct, uuid

def varint(n: int) -> bytes:
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n: return bytes(out)


def string(s: str) -> bytes:
    b = s.encode()
    return varint(len(b)) + b


def pack(fmt: str, *v) -> bytes:
    return struct.pack(">" + fmt, *v)


def frame(pid: int, body: bytes = b"") -> bytes:
    payload = varint(pid) + body
    return varint(len(payload)) + payload


def split(frame_: bytes) -> tuple[int, bytes]:
    """(packet id, body) of a whole frame."""
    r = Reader(frame_)
    n = r.varint()
    if n != len(frame_) - r.pos: raise ValueError("frame length mismatch")
    pid = r.varint()
    return pid, frame_[r.pos:]


def offline_uuid(name: str) -> str:
    """Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name), as the server derives it in offline mode."""
    return str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:" + name.encode()).digest(), version=3))


class Reader:
    def __init__(self, b: bytes, pos: int = 0):
        self.b, self.pos = b, pos

    def take(self, n: int) -> bytes:
        if n < 0 or self.pos + n > len(self.b): raise ValueError("truncated")
        v = self.b[self.pos:self.pos + n]; self.pos += n
        return v

    def u(self, fmt: str):
        s = struct.Struct(">" + fmt)
        v = s.unpack(self.take(s.size))
        return v[0] if len(v) == 1 else v

    def varint(self) -> int:
        n = 0
        for i in range(5):
            b = self.take(1)[0]
            n |= (b & 0x7F) << 7 * i
            if not b & 0x80: return n - (1 << 32) if n & 0x80000000 else n
        raise ValueError("varint too long")

    def string(self) -> str:
        return self.take(self.varint()).decode()

    def item(self) -> bytes:
        """One wire item slot, raw: short id (<0 empty), byte count, short damage, short nbt length + gzip bytes."""
        start = self.pos
        if self.u("h") >= 0:
            self.take(3)
            n = self.u("h")
            if n > 0: self.take(n)
        return self.b[start:self.pos]

    def metadata(self) -> dict[int, bytes]:
        """Watched-object list up to and including 0x7F, as {index: raw entry}."""
        out = {}
        while (key := self.u("B")) != 0x7F:
            start, kind = self.pos - 1, key >> 5
            if kind in (0, 1, 2, 3, 6): self.take((1, 2, 4, 4, 0, 0, 12)[kind])
            elif kind == 4: self.string()
            elif kind == 5: self.item()
            else: raise ValueError(f"metadata type {kind}")
            out[key & 0x1F] = self.b[start:self.pos]
        return out

    def rest(self) -> bytes:
        v = self.b[self.pos:]; self.pos = len(self.b)
        return v


def payload(body: bytes) -> tuple[str, bytes]:
    """S3F custom payload: channel, then Forge's varShort length (15 bits, or 23 with a high-bit continuation byte)."""
    r = Reader(body)
    channel = r.string()
    n = r.u("H")
    if n & 0x8000: n = (n & 0x7FFF) | r.u("B") << 15
    return channel, r.take(n)


class Splitter:
    """Incremental frame splitter for one direction of a stream."""
    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self.buf += data
        out, pos, buf = [], 0, self.buf
        while True:
            n = shift = 0; i = pos; whole = False
            while i < len(buf):
                b = buf[i]; i += 1
                n |= (b & 0x7F) << shift; shift += 7
                if not b & 0x80: whole = True; break
                if shift >= 21: raise ValueError("frame length varint too long")
            if not whole or i + n > len(buf): break
            if n == 0: raise ValueError("empty frame")
            out.append(bytes(buf[pos:i + n])); pos = i + n
        del buf[:pos]
        return out
