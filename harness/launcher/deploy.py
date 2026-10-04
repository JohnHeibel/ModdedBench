# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The one path by which a contained agent changes the game client.

``request`` runs where the agent is: it copies the jars the agent built into the outbox and waits for
the verdict. ``serve`` runs on the host next to the client: it accepts only the three managed client
jars, keeps every request (jars, source patch, commit) under ``.runtime/deploys`` as the audit trail,
swaps the jars, restarts the client and rolls back if it does not join. The server is never touched.
"""
from __future__ import annotations

import argparse, json, os, shutil, subprocess, sys, time, uuid, zipfile
from pathlib import Path
from types import SimpleNamespace

import runtime
from runtime import REPO, RuntimeError_, load_json, save_json

COMPONENTS = ("core", "baritone", "client")
MAX_JAR = 64 << 20
JOIN_S, STOP_S = 420, 60  # what the supervisor gives a launched client to join the world, and a running one to shut down
WORST_S = 2 * (STOP_S + JOIN_S) + 120  # stop, launch, and when that fails stop, roll back and launch again: a request waits this long for its verdict


def outbox() -> Path:
    return Path(os.environ.get("MODBENCH_OUTBOX", REPO / ".runtime" / "outbox"))


def end_task(wait: float = 120) -> dict | None:
    """A client restart cuts a background task's bridge session mid-job: wait up to wait seconds for it, then cancel it."""
    sys.path.insert(0, str(REPO / "harness" / "mcp"))
    import mbtool  # noqa: F401  (installs the tool package)
    from mbtools_gtnh import tasks
    ended = tasks.end_all("deploy", wait)
    if ended: print(f"background task {ended['task']} ended: {ended['state']} ({ended.get('ended')})", file=sys.stderr)
    return ended


def request(args: argparse.Namespace) -> int:
    box = outbox(); box.mkdir(parents=True, exist_ok=True)
    if (box / "request.json").exists() or (box / "request.taken").exists(): raise RuntimeError_("a deploy request is already waiting")
    end_task()
    ident = uuid.uuid4().hex
    for kind in args.components:
        shutil.copy2(runtime.artifact(kind), box / f"modbench-{kind}.jar")
    git = lambda *a: subprocess.run(["git", *a], cwd=REPO, capture_output=True, text=True).stdout
    base = git("rev-parse", "-q", "--verify", "modbench-base").strip()
    (box / "source.patch").write_text(git("diff", base, "--", "mods", "build.gradle") if base else "", encoding="utf-8")
    save_json(box / "request.json", {"id": ident, "components": args.components, "commit": git("rev-parse", "HEAD").strip(), "reason": args.reason})
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        try: result = load_json(box / "result.json")
        except (OSError, ValueError): result = {}  # read while the supervisor writes it: the next look is a second away
        if result.get("id") == ident:
            print(json.dumps(result)); return 0 if result.get("ok") else 1
        time.sleep(1)
    raise RuntimeError_("no verdict from the deploy supervisor; is `deploy.py serve` running on the host?")


def accept(req: dict, box: Path, archive: Path) -> dict[str, Path]:
    """Copy one request out of the agent's reach, then validate the copy."""
    kinds = req.get("components")
    if not isinstance(kinds, list) or not kinds or len(set(kinds)) != len(kinds) or any(k not in COMPONENTS for k in kinds):
        raise RuntimeError_("components must be a unique nonempty subset of core, baritone, client")
    archive.mkdir(parents=True)
    jars = {}
    for kind in kinds:
        jars[kind] = archive / f"modbench-{kind}.jar"
        shutil.copyfile(box / jars[kind].name, jars[kind])
        if jars[kind].stat().st_size > MAX_JAR or not zipfile.is_zipfile(jars[kind]):
            raise RuntimeError_(f"{jars[kind].name} is not a jar under {MAX_JAR >> 20} MiB")
    if (box / "source.patch").is_file(): shutil.copyfile(box / "source.patch", archive / "source.patch")
    save_json(archive / "request.json", req)
    return jars


def deploy(jars: dict[str, Path], root: Path, timeout: float) -> dict:
    cfg = runtime.load_config(root)
    launch = SimpleNamespace(runtime=str(root), username="", timeout=timeout, installed_as_is=True)
    stop = SimpleNamespace(runtime=str(root), timeout=STOP_S)
    installed = []
    try:
        if runtime.client_instance_is_running(runtime.instance_dir(cfg)): runtime.stop_client(stop)  # a client that will not stop is a failed deploy like any other: relaunched as it was
        for kind in COMPONENTS:
            if kind in jars: runtime.install_jar(kind, cfg, root, "client", jars[kind]); installed.append(kind)
        runtime.launch_client(launch)
        return {"ok": True, "deployed": installed}
    except Exception as failure:
        try:
            if runtime.client_instance_is_running(runtime.instance_dir(cfg)): runtime.stop_client(stop)
            for kind in reversed(installed): runtime.rollback_jar(kind, root, "client")
            runtime.launch_client(launch)
            return {"ok": False, "error": str(failure), "rolledBack": installed}
        except Exception as second:
            return {"ok": False, "error": str(failure), "rollbackError": str(second)}


def claim(box: Path) -> bool:
    """Take the waiting request for this supervisor alone: the rename succeeds for one process, so no request is deployed twice."""
    try:
        if (box / "request.json").is_file(): os.replace(box / "request.json", box / "request.taken"); return True
    except OSError: pass
    return False


def serve(args: argparse.Namespace) -> int:
    root = Path(args.runtime).resolve(); box = outbox(); box.mkdir(parents=True, exist_ok=True)
    only = runtime.only_one("deploy-supervisor", root)  # two supervisors would stop and start one client against each other
    if not only: raise RuntimeError_("another deploy supervisor is already serving this runtime")
    print(f"[ModdedBench] deploy supervisor watching {box}", flush=True)
    taken = box / "request.taken"
    if taken.is_file() and not (box / "request.json").exists(): os.replace(taken, box / "request.json")  # a supervisor died mid-deploy: its request is served again
    while True:
        if claim(box):
            archive = root / "deploys" / time.strftime("%Y%m%d-%H%M%S")
            try: req = load_json(taken)
            except (OSError, ValueError): req = {}
            if not isinstance(req, dict): req = {}
            try:
                result = deploy(accept(req, box, archive), root, args.timeout)
            except Exception as exc:  # a malformed request must not stop the supervisor
                result = {"ok": False, "error": str(exc)}
            result["id"] = req.get("id")
            if archive.is_dir(): save_json(archive / "result.json", result)
            save_json(box / "result.json", result); taken.unlink(missing_ok=True)
            print(json.dumps(result), flush=True)
            if args.once: return 0 if result["ok"] else 1
        time.sleep(1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("request"); p.add_argument("components", nargs="+", choices=COMPONENTS); p.add_argument("--reason", default=""); p.add_argument("--timeout", type=float, default=WORST_S); p.set_defaults(func=request)
    p = sub.add_parser("serve"); p.add_argument("--runtime", default=str(runtime.RUNTIME)); p.add_argument("--timeout", type=float, default=JOIN_S); p.add_argument("--once", action="store_true"); p.set_defaults(func=serve)
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except RuntimeError_ as exc:
        print(f"error: {exc}", file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
