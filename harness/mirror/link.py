# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The forwarder -> relay link: authenticated, one way, message framed.

The relay speaks first, with MAGIC and a random nonce; the forwarder answers HMAC-SHA256(token, nonce) and from
then on only sends. The forwarder never reads anything after the nonce, so the relay has no way to reach the game.

Message: u8 kind, u32 length, payload.
  HEARTBEAT  nothing
  SNAPSHOT   header {session, dim, host, prelude, respawn} then entries: the respawn frames that move a viewer
             into dim, then the gated snapshot, whose first `prelude` entries run up to JoinGame
  LIVE       viewer frames, concatenated
  END        header {session, dim, world} then entries: the frames that empty a viewer's world (the first `world`
             of them are world-scoped, which a dimension change does anyway), then the tab list's
  INVENTORY  header {title} then the agent's inventory as 45 wire item slots, laid out as a 5-row chest
Header: u32 length, JSON. Entry: varint gate (FML|HS replies the viewer must have sent first), then a frame.
"""
from __future__ import annotations
import hashlib, hmac, json, os, struct
from .wire import Reader, varint

MAGIC = b"MBRELAY1\n"
NONCE = 32
HEARTBEAT, SNAPSHOT, LIVE, END, INVENTORY = 0, 1, 2, 3, 4
HEAD = struct.Struct(">BI")
MAX = 256 << 20  # bytes in one message


def token() -> bytes:
    t = os.environ.get("MB_RELAY_TOKEN", "")
    if len(t) < 16: raise SystemExit("MB_RELAY_TOKEN must hold a shared secret of at least 16 characters")
    return t.encode()


def proof(secret: bytes, nonce: bytes) -> bytes:
    return hmac.new(secret, nonce, hashlib.sha256).digest()


def message(kind: int, payload: bytes = b"") -> bytes:
    return HEAD.pack(kind, len(payload)) + payload


def headed(meta: dict, entries: list[tuple[int, bytes]]) -> bytes:
    j = json.dumps(meta).encode()
    return struct.pack(">I", len(j)) + j + b"".join(varint(g) + f for g, f in entries)


def header(payload: bytes) -> tuple[dict, int]:
    n = struct.unpack_from(">I", payload)[0]
    return json.loads(payload[4:4 + n]), 4 + n


def unheaded(payload: bytes) -> tuple[dict, list[tuple[int, bytes]]]:
    meta, at = header(payload)
    r, out = Reader(payload, at), []
    while r.pos < len(payload):
        gate = r.varint(); start = r.pos
        r.take(r.varint())
        out.append((gate, payload[start:r.pos]))
    return meta, out
