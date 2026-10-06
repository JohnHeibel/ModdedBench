# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Read-only spectator mirror: python -m harness.mirror (see docs/MIRROR.md)."""
from __future__ import annotations
import argparse, asyncio
from pathlib import Path
from .proxy import Hub
from .state import Mirror

REPO = Path(__file__).resolve().parents[2]


def address(s: str) -> tuple[str, int]:
    host, _, port = s.rpartition(":")
    return host or "127.0.0.1", int(port)


def main():
    p = argparse.ArgumentParser(prog="python -m harness.mirror", description=__doc__)
    p.add_argument("--listen", type=address, default=("127.0.0.1", 25576), help="where the agent's client connects")
    p.add_argument("--upstream", type=address, default=("127.0.0.1", 25575), help="the game server")
    p.add_argument("--viewers", type=address, default=("127.0.0.1", 25580), help="where viewers connect (offline mode: no auth)")
    p.add_argument("--max-viewers", type=int, default=8)
    p.add_argument("--stats", type=Path, default=REPO / ".runtime" / "mirror" / "stats.json")
    p.add_argument("--capture", type=Path, help="append every host frame to this file (includes private MB| payloads)")
    p.add_argument("--replay", type=Path, help="serve viewers from a capture instead of a live host pipe")
    p.add_argument("--payload-cap", type=int, default=8 << 20, help="bytes of mod payloads kept for late viewers")
    a = p.parse_args()
    if a.capture: a.capture.parent.mkdir(parents=True, exist_ok=True)
    hub = Hub(a.upstream, a.stats, a.capture, a.max_viewers, mirror=Mirror(payload_cap=a.payload_cap))
    try:
        asyncio.run(hub.run(a.listen, a.viewers, a.replay))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
