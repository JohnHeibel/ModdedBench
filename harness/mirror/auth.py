# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Online-mode login for viewers, as a 1.7.10 server does it: an RSA key exchange for a shared secret, AES/CFB8 on
the stream from then on, and Mojang's session server confirming that the account named is the one connecting.
That gives the relay each viewer's real account UUID, the one its client uses for itself (quest progress is shown
by it). Needs the cryptography package.
"""
from __future__ import annotations
import asyncio, hashlib, json, os, urllib.parse, urllib.request, uuid
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from .state import disconnect
from .wire import Reader, frame, pack, split, string

SESSION = "https://sessionserver.mojang.com/session/minecraft/hasJoined"


class Online:
    """The relay's side of the exchange, with a key pair made once per run like a vanilla server's."""
    def __init__(self, verify=None):
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=1024)
        self.public = self.private.public_key().public_bytes(serialization.Encoding.DER,
                                                             serialization.PublicFormat.SubjectPublicKeyInfo)
        self.verify = verify or has_joined  # (name, digest) -> (uuid, name) | None; blocking, so run in a thread

    async def login(self, r, w, name: str):
        """After LoginStart: (reader, writer, dashed uuid, name) of the confirmed account, the streams now sealed;
        uuid None if it was not confirmed (the client has been told why)."""
        from .proxy import read_frame
        token = os.urandom(4)
        w.write(self.request("", token)); await w.drain()
        pid, body = split(await read_frame(r))
        if pid != 0x01: raise ValueError(f"login packet {pid:#x} instead of the encryption response")
        secret = self.answer(body, token)
        r = w = Sealed(r, w, secret)
        who = await asyncio.to_thread(self.verify, name, server_hash("", secret, self.public))
        if who is None:
            w.write(disconnect("Mojang could not confirm your account; restart Minecraft and try again", login=True))
            await w.drain()
            return r, w, None, name
        return (r, w, *who)

    def request(self, server_id: str, token: bytes) -> bytes:
        """Login 0x01 EncryptionRequest; 1.7.10 prefixes its byte arrays with a short."""
        return frame(0x01, string(server_id) + pack("h", len(self.public)) + self.public + pack("h", len(token)) + token)

    def answer(self, body: bytes, token: bytes) -> bytes:
        """The shared secret from the client's EncryptionResponse, once its copy of our token matches."""
        r = Reader(body)
        secret, echoed = (self.private.decrypt(r.take(r.u("h")), padding.PKCS1v15()) for _ in range(2))
        if echoed != token or len(secret) != 16: raise ValueError("encryption response does not match")
        return secret


def server_hash(server_id: str, secret: bytes, public: bytes) -> str:
    """Minecraft's digest: SHA-1 as a signed two's-complement number in hex."""
    n = int.from_bytes(hashlib.sha1(server_id.encode("iso-8859-1") + secret + public).digest(), "big", signed=True)
    return format(n, "x") if n >= 0 else "-" + format(-n, "x")


def has_joined(name: str, digest: str, timeout: float = 10.0) -> tuple[str, str] | None:
    """(dashed uuid, name) of the account that told Mojang it is joining a server with this digest, or None."""
    url = SESSION + "?" + urllib.parse.urlencode({"username": name, "serverId": digest})
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        if resp.status != 200: return None
        profile = json.loads(resp.read())
    return str(uuid.UUID(profile["id"])), profile["name"]


class Sealed:
    """A viewer's stream after the exchange: AES/CFB8 both ways, the shared secret as key and IV."""
    def __init__(self, r: asyncio.StreamReader, w: asyncio.StreamWriter, secret: bytes):
        c = Cipher(algorithms.AES(secret), modes.CFB8(secret))
        self.r, self.w, self.dec, self.enc = r, w, c.decryptor(), c.encryptor()

    async def read(self, n: int = -1) -> bytes: return self.dec.update(await self.r.read(n))
    async def readexactly(self, n: int) -> bytes: return self.dec.update(await self.r.readexactly(n))
    def write(self, data: bytes): self.w.write(self.enc.update(data))
    async def drain(self): await self.w.drain()
    def close(self): self.w.close()

