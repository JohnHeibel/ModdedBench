# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Operator side of mb_request (harness/tools/dev.py): answer the model's requests for raw materials.

    python harness/launcher/supply.py wait                      exits once a request is waiting, and prints it
    python harness/launcher/supply.py grant <id> [--only 0,2] [--note text]
    python harness/launcher/supply.py refuse <id> --note text

A grant types ``give`` on the server console (through the Docker engine, so the player needs no op and the agent
no route) and reads the server's own answer back from its log: what the result says was given is what the
server said it gave. The console runs commands while the world is paused. Branch ``dev-requests`` only.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REQUESTS = Path(os.environ.get("MODBENCH_OUTBOX") or REPO / ".runtime" / "outbox") / "requests"
SERVER = (os.environ.get("MB_COMPOSE_PROJECT") or "moddedbench") + "-server-1"
PLAYER = os.environ.get("MB_PLAYER") or "ModbenchDev"
DOCKER = shutil.which("docker") or "C:/Program Files/Docker/Docker/resources/bin/docker.exe"
GIVEN = re.compile(r"Given \[.*\] \* (\d+) to ")


def console(lines: list[str]) -> None:
    """Type lines on the server's console: attach to the container's stdin through the Docker engine."""
    head = (f"POST /containers/{SERVER}/attach?stream=1&stdin=1 HTTP/1.1\r\nHost: docker\r\nUpgrade: tcp\r\nConnection: Upgrade\r\nContent-Length: 0\r\n\r\n").encode()
    if os.name == "nt":
        pipe = open("//./pipe/docker_engine", "r+b", buffering=0); send, recv, close = pipe.write, pipe.read, pipe.close
    else:
        sock = socket.socket(socket.AF_UNIX); sock.connect("/var/run/docker.sock"); send, recv, close = sock.sendall, sock.recv, sock.close
    send(head); seen = b""
    while b"\r\n\r\n" not in seen: seen += recv(1)
    if b" 101 " not in seen.split(b"\r\n", 1)[0]: raise RuntimeError(seen.decode(errors="replace").strip())
    for line in lines: send((line + "\n").encode()); time.sleep(0.05)
    time.sleep(1); close()


def give(item: dict) -> dict:
    """Give one requested item in stacks; the answer is the server's log since the first command."""
    since = datetime.now(timezone.utc).isoformat(); left = item["count"]; lines = []
    while left > 0: lines.append(f"give {PLAYER} {item['id']} {min(left, 64)} {item['meta']}"); left -= 64
    console(lines); time.sleep(0.5 + len(lines) * 0.02)
    log = subprocess.run([DOCKER, "logs", "--since", since, SERVER], capture_output=True, text=True, encoding="utf-8", errors="replace")
    said = [re.sub(r"\x1b\[[0-9;]*m", "", line).strip() for line in (log.stdout + log.stderr).splitlines()]
    given = sum(int(m.group(1)) for line in said if (m := GIVEN.search(line)))
    out = {**item, "given": given}
    if given != item["count"]: out["server"] = [line for line in said if line and not GIVEN.search(line)][-3:]
    return out


def pending() -> list[dict]:
    if not REQUESTS.is_dir(): return []
    files = sorted((f for f in REQUESTS.glob("*.json") if not f.name.endswith(".result.json")), key=lambda f: f.stat().st_mtime)
    return [json.loads(f.read_text(encoding="utf-8")) for f in files if not (REQUESTS / f"{f.stem}.result.json").exists()]


def answer(rid: str, result: dict) -> None:
    tmp = REQUESTS / f"{rid}.result.tmp"; tmp.write_text(json.dumps({"request": rid, **result}), encoding="utf-8"); tmp.replace(REQUESTS / f"{rid}.result.json")
    print(json.dumps(result, indent=1))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter); sub = parser.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("wait"); w.add_argument("--timeout", type=float, default=0, help="seconds; 0 waits for ever")
    g = sub.add_parser("grant"); g.add_argument("id"); g.add_argument("--only", default="", help="indexes of the items to give; the rest are refused"); g.add_argument("--note", default="")
    r = sub.add_parser("refuse"); r.add_argument("id"); r.add_argument("--note", required=True)
    args = parser.parse_args(argv)
    if args.cmd == "wait":
        end = time.monotonic() + args.timeout if args.timeout else None
        while not (waiting := pending()):
            if end and time.monotonic() >= end: print("no request"); return 1
            time.sleep(2)
        print(json.dumps(waiting, indent=1)); return 0
    path = REQUESTS / f"{args.id}.json"
    if not path.is_file(): print(f"no request {args.id}", file=sys.stderr); return 2
    if (REQUESTS / f"{args.id}.result.json").exists(): print(f"request {args.id} is already answered", file=sys.stderr); return 2
    items = json.loads(path.read_text(encoding="utf-8"))["items"]
    if args.cmd == "refuse": answer(args.id, {"status": "refused", "given": [], "refused": items, "note": args.note}); return 0
    only = {int(i) for i in args.only.split(",") if i.strip()} if args.only else set(range(len(items)))
    given = [give(item) for i, item in enumerate(items) if i in only]; refused = [item for i, item in enumerate(items) if i not in only]
    complete = not refused and all(g["given"] == g["count"] for g in given)
    answer(args.id, {"status": "granted" if complete else "partial", "given": given, "refused": refused, "note": args.note}); return 0


if __name__ == "__main__":
    sys.exit(main())
