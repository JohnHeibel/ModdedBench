# SPDX-License-Identifier: LGPL-3.0-or-later
"""Operator-only snapshots of a contained run: the world and the agent's notes, taken together, kept on the host.

python harness/launcher/backup.py once              one snapshot now
python harness/launcher/backup.py loop --every 30   one every 30 minutes until interrupted (the console runs this with a run)
python harness/launcher/backup.py restore <folder> --reason "..."   put a snapshot back, logged

A snapshot holds the world with the operator hold (no ticks, so no saves in flight; it is the
last autosave, at most 45 s of game time old), streams the server's data folder without the
pack's own files, copies the notes databases through SQLite's backup API, and releases the hold.
Snapshots land in .runtime/snapshots, which no container mounts: the agent cannot see, make or
restore them, and nothing in its brief mentions them. Restoring is an operator decision for
infrastructure faults only (a corrupted world, a lost disk, a harness bug that damaged state),
never to undo the agent's own mistakes; every restore is appended to .runtime/snapshots/restores.jsonl,
which stays on the host. The newest KEEP snapshots and the first of each day are kept.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOCKER = shutil.which("docker") or "C:/Program Files/Docker/Docker/resources/bin/docker.exe"
PROJECT = os.environ.get("MB_COMPOSE_PROJECT", "moddedbench")  # a test stack is `-p mbtest`
COMPOSE = [DOCKER, "compose", "-p", PROJECT, "-f", str(REPO / "docker" / "compose.yaml"), "--env-file", str(REPO / "docker" / ".env")]
OUT, KEEP = REPO / ".runtime" / "snapshots", 48
WORLD = "tar -C /data --exclude=./mods --exclude=./libraries --exclude=./logs --exclude=./crash-reports --exclude='./*.jar' -czf - ."
NOTES = ("import sqlite3,pathlib,tarfile,sys,tempfile\n"
         "with tempfile.TemporaryDirectory() as t:\n"
         "    for p in pathlib.Path('.state/notes').glob('*.sqlite3'):\n"
         "        s,d=sqlite3.connect(p),sqlite3.connect(pathlib.Path(t)/p.name);s.backup(d);d.close();s.close()\n"
         "    with tarfile.open(fileobj=sys.stdout.buffer,mode='w|gz') as tar: tar.add(t,arcname='notes')\n")


def sh(*args, to: Path | None = None) -> bool:
    """Run inside a container; with `to`, stream stdout to a file and drop the file on failure."""
    if to is None: return subprocess.run([*COMPOSE, "exec", "-T", *args], capture_output=True).returncode == 0
    with to.open("wb") as out: ok = subprocess.run([*COMPOSE, "exec", "-T", *args], stdout=out, stderr=subprocess.DEVNULL).returncode == 0
    if not ok or to.stat().st_size == 0: to.unlink(missing_ok=True); return False
    return True


def snapshot() -> Path | None:
    folder = OUT / time.strftime("%Y%m%d-%H%M%S"); folder.mkdir(parents=True)
    held = sh("server", "test", "-f", "/data/modbench-hold")  # an operator pause already in force stays in force
    if not held: sh("server", "touch", "/data/modbench-hold"); time.sleep(5)  # the hold lands on the next tick; let chunk IO drain
    try:
        world = sh("server", "sh", "-c", WORLD, to=folder / "world.tar.gz")
        notes = sh("agent", "python3", "-c", NOTES, to=folder / "notes.tar.gz")
    finally:
        if not held: sh("server", "rm", "-f", "/data/modbench-hold")
    if not world:
        shutil.rmtree(folder); print("no snapshot: the server container is not running", file=sys.stderr); return None
    print(f"{folder.name}: world {(folder / 'world.tar.gz').stat().st_size >> 20} MiB, notes {'ok' if notes else 'MISSING (agent container down?)'}")
    return folder


def restore(folder: Path, reason: str) -> None:
    """Stop the server and agent, replace the world and the notes with the snapshot's, log it. The stack stays stopped."""
    folder = folder if folder.is_absolute() else OUT / folder
    if not (folder / "world.tar.gz").is_file(): raise SystemExit(f"{folder} holds no world.tar.gz")
    run = lambda *a: subprocess.run([DOCKER, "run", "--rm", *a], check=True)
    subprocess.run([*COMPOSE, "stop", "server", "agent"], check=True)
    # Everything the snapshot holds is removed first, so chunks generated after it do not survive beside it.
    run("-v", f"{PROJECT}_server-data:/data", "-v", f"{folder}:/b:ro", "alpine", "sh", "-c",
        "cd /data && find . -mindepth 1 -maxdepth 1 ! -name mods ! -name libraries ! -name logs ! -name crash-reports ! -name '*.jar' -exec rm -rf {} + && tar -xzf /b/world.tar.gz")
    notes = (folder / "notes.tar.gz").is_file()
    if notes:
        run("-v", f"{PROJECT}_agent-work:/work", "-v", f"{folder}:/b:ro", "alpine", "sh", "-c",
            "d=/work/modbench/.state; mkdir -p $d && rm -rf $d/notes && tar -xzf /b/notes.tar.gz -C $d && chown -R $(stat -c %u:%g /work/modbench) $d/notes")
    entry = {"at": time.strftime("%Y-%m-%dT%H:%M:%S"), "snapshot": folder.name, "project": PROJECT, "notes": notes, "reason": reason}
    with (OUT / "restores.jsonl").open("a", encoding="utf-8") as log: log.write(json.dumps(entry) + "\n")
    print(f"restored {folder.name}{'' if notes else ' (world only: the snapshot has no notes)'}; logged; start the stack when ready")


def prune() -> None:
    folders = sorted(p for p in OUT.iterdir() if p.is_dir())
    daily = {p.name[:8]: p for p in reversed(folders)}.values()  # the first snapshot of each day
    for p in folders[:-KEEP]:
        if p not in daily: shutil.rmtree(p)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=["once", "loop", "restore"])
    parser.add_argument("folder", nargs="?", help="restore: the snapshot folder (name or path)")
    parser.add_argument("--every", type=float, default=30, help="minutes between snapshots in loop mode")
    parser.add_argument("--reason", default="", help="restore: the infrastructure fault that makes it necessary (required)")
    args = parser.parse_args()
    if args.mode == "restore":
        if not args.folder or not args.reason.strip(): parser.error("restore needs a snapshot folder and --reason")
        restore(Path(args.folder), args.reason.strip()); sys.exit()
    while True:
        if snapshot(): prune()
        if args.mode == "once": break
        time.sleep(args.every * 60)
