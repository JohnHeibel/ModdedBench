# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Prepare and supervise an isolated GTNH development runtime.

This module invokes Prism's CLI without reading or managing account credentials. It owns only the
``Modbench-GTNH-Dev`` Prism instance it marks, and only the managed mod jars it
installs.  Run it from the repository with ``python harness/launcher/runtime.py -h``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

REPO = Path(__file__).resolve().parents[2]
RUNTIME = REPO / ".runtime"
INSTANCE_NAME = "Modbench-GTNH-Dev"
MARKER = ".modbench-gtnh.json"
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))
from kernel import bridge_url  # noqa: E402
from urllib.parse import urlparse  # noqa: E402
CLIENT_PORT, SERVER_PORT = urlparse(bridge_url()).port, urlparse(bridge_url("server")).port  # one source: MB_BRIDGE_URL
CLIENT_COMPONENTS = {"client", "core", "baritone"}   # jars the managed Prism instance carries
SERVER_COMPONENTS = {"server", "core"}              # jars the managed dedicated server carries; core is the coremod both need
MAIN_MENU_SCREENS = {
    "net.minecraft.client.gui.GuiMainMenu",
    "lumien.custommainmenu.gui.GuiCustom",
}


class RuntimeError_(RuntimeError):
    pass


def config_path(runtime: Path = RUNTIME) -> Path:
    return runtime / "config.json"


def default_config() -> dict[str, Any]:
    """Return candidates, never silently select a machine-specific path."""
    local_value = os.environ.get("LOCALAPPDATA", "")
    roaming_value = os.environ.get("APPDATA", "")
    local = Path(local_value) if local_value else None
    roaming = Path(roaming_value) if roaming_value else None
    return {
        "java": str(Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java.exe") if os.environ.get("JAVA_HOME") else "",
        "javaCandidates": [str(Path("C:/Program Files/Java/jdk-25/bin/java.exe")), "java"],
        "prism": "",
        "prismCandidates": [str(local / "Programs/PrismLauncher/prismlauncher.exe")] if local else [],
        "prismData": str(roaming / "PrismLauncher") if roaming else "",
        "clientZip": "",
        "serverZip": "",
        "username": "ModbenchDev",
        "memoryMiB": 6144,
        "serverMemoryMiB": 4096,
    }


def load_config(runtime: Path = RUNTIME) -> dict[str, Any]:
    path = config_path(runtime)
    if not path.is_file():
        raise RuntimeError_(f"missing {path}; run prepare with --client-zip and --server-zip")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RuntimeError_(f"invalid JSON in {path}: {exc}") from exc


def save_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_pack_archive(path: Path, side: str, lock_path: Path | None = None) -> None:
    """Require the checked-in pack lock before accepting a large archive."""
    lock_path = lock_path or REPO / "pack.lock.json"
    try:
        archives = json.loads(lock_path.read_text(encoding="utf-8"))["archives"]
        expected = next(item for item in archives if item.get("side") == side)
    except (OSError, KeyError, StopIteration, json.JSONDecodeError) as exc:
        raise RuntimeError_(f"invalid or missing pack lock: {lock_path}") from exc
    if path.name != expected.get("file") or path.stat().st_size != expected.get("bytes"):
        raise RuntimeError_(f"{side} archive does not match the pinned GTNH 2.8.4 archive")
    if sha256_file(path) != expected.get("sha256"):
        raise RuntimeError_(f"{side} archive SHA-256 does not match {lock_path}")


def resolve_executable(value: str, candidates: list[str], label: str) -> str:
    if value:
        found = shutil.which(value) if not Path(value).is_file() else value
        if found:
            return str(Path(found))
        raise RuntimeError_(f"configured {label} does not exist or is not executable: {value}")
    for candidate in candidates:
        if not candidate:
            continue
        found = shutil.which(candidate) if not Path(candidate).is_file() else candidate
        if found:
            return str(Path(found))
    raise RuntimeError_(f"no {label} found; set its explicit path in {config_path()}")


def require_java17(java: str) -> str:
    """The Java 17-25 pack dies at once on Java 8 with a misleading class-not-found; refuse it up front."""
    try:
        banner = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=20).stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        raise RuntimeError_(f"cannot run {java}: {e}") from None
    found = re.search(r'version "(\d+)', banner)
    if not found or int(found.group(1)) < 17:
        raise RuntimeError_(f"{java} is not Java 17 or newer; pass prepare --java <path to a JDK 17-25 java>")
    return java


def safe_zip_members(archive: zipfile.ZipFile, strip_root: bool = False) -> list[tuple[zipfile.ZipInfo, PurePosixPath]]:
    """Validate ZIP paths before extraction and optionally strip one common root."""
    files = [item for item in archive.infolist() if not item.is_dir()]
    if any((item.external_attr >> 16) & 0o170000 == 0o120000 for item in files):
        raise RuntimeError_("archive contains a symbolic link")
    # ZIP historically permits backslashes. Treat them as hostile instead of
    # letting Windows reinterpret them after PurePosixPath has approved them.
    if any("\\" in item.filename or ":" in item.filename for item in files):
        raise RuntimeError_("archive contains a Windows-style or drive path")
    names = [PurePosixPath(item.filename) for item in files]
    if any(path.is_absolute() or ".." in path.parts or not path.parts for path in names):
        raise RuntimeError_("archive contains an unsafe path")
    roots = {path.parts[0] for path in names}
    if strip_root:
        if len(roots) != 1:
            raise RuntimeError_("client archive must contain exactly one top-level directory")
        result = [(item, PurePosixPath(*path.parts[1:])) for item, path in zip(files, names)]
        if any(not dest.parts for _, dest in result):
            raise RuntimeError_("client archive contains a top-level file")
        return result
    return list(zip(files, names))


def extract_zip(archive_path: Path, target: Path, *, strip_root: bool = False) -> None:
    if target.exists() and any(target.iterdir()):
        raise RuntimeError_(f"refusing to extract into non-empty directory: {target}")
    target.mkdir(parents=True, exist_ok=True)
    target_resolved = target.resolve()
    with zipfile.ZipFile(archive_path) as archive:
        members = safe_zip_members(archive, strip_root)
        for info, relative in members:
            destination = target.joinpath(*relative.parts)
            try:
                destination.resolve().relative_to(target_resolved)
            except ValueError as exc:
                raise RuntimeError_(f"archive member escapes extraction target: {info.filename}") from exc
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, destination.open("wb") as out:
                shutil.copyfileobj(source, out)


def instance_dir(cfg: dict[str, Any]) -> Path:
    data = cfg.get("prismData")
    if not data:
        raise RuntimeError_("prismData must be set explicitly in config")
    return Path(data) / "instances" / INSTANCE_NAME


def assert_managed_instance(path: Path, *, allow_new: bool = False) -> None:
    marker = path / MARKER
    if path.exists() and not marker.is_file() and not (allow_new and not any(path.iterdir())):
        raise RuntimeError_(f"refusing to modify existing unmarked Prism instance: {path}")


def write_instance_config(path: Path, java: str, memory: int, window: str = "") -> None:
    # Prism's MultiMC-compatible instance format. Keep every pack-provided
    # component/metadata line rather than replacing instance.cfg wholesale.
    values = {
        "InstanceType": "OneSix",
        "name": INSTANCE_NAME,
        "OverrideJavaLocation": "true",
        # Qt INI treats backslashes as escapes, so launcher paths must use
        # forward slashes even on Windows.
        "JavaPath": Path(java).as_posix(),
        "OverrideMemory": "true",
        "MinMemAlloc": str(max(1024, memory // 2)),
        "MaxMemAlloc": str(memory),
        "OverrideConsole": "true",
        "ShowConsole": "false",
    }
    if window:  # "1920x1080"; Prism otherwise opens 854x480, which is too small to record or to read GUIs from
        width, _, height = window.lower().partition("x")
        if not (width.isdigit() and height.isdigit()): raise RuntimeError_("window must look like 1920x1080")
        values |= {"OverrideWindow": "true", "LaunchMaximized": "false", "MinecraftWinWidth": width, "MinecraftWinHeight": height}
    cfg = path / "instance.cfg"
    original = cfg.read_text(encoding="utf-8", errors="replace").splitlines() if cfg.is_file() else []
    seen: set[str] = set()
    rendered = []
    for line in original:
        key = line.split("=", 1)[0]
        if key in values and "=" in line:
            rendered.append(f"{key}={values[key]}")
            seen.add(key)
        else:
            rendered.append(line)
    rendered.extend(f"{key}={value}" for key, value in values.items() if key not in seen)
    cfg.write_text("\n".join(rendered) + "\n", encoding="utf-8")


def client_game_dir(instance: Path) -> Path:
    """Find the pack's game directory, never assume it is the instance root."""
    cfg = instance / "instance.cfg"
    if cfg.is_file():
        for line in cfg.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("GameDir=") and line.partition("=")[2]:
                candidate = Path(line.partition("=")[2])
                if not candidate.is_absolute():
                    candidate = instance / candidate
                try:
                    candidate.resolve().relative_to(instance.resolve())
                except ValueError as exc:
                    raise RuntimeError_("instance GameDir must stay inside the managed instance") from exc
                return candidate
    for name in (".minecraft", "minecraft"):
        candidate = instance / name
        if candidate.is_dir():
            return candidate
    return instance / ".minecraft"


def set_client_options(game: Path) -> None:
    """Preserve user options while preparing unattended local development."""
    game.mkdir(parents=True, exist_ok=True)
    options = game / "options.txt"
    lines = options.read_text(encoding="utf-8", errors="replace").splitlines() if options.is_file() else []
    key, value, seen = "pauseOnLostFocus", "false", False
    output = []
    for line in lines:
        if line.partition(":")[0] == key:
            output.append(f"{key}:{value}")
            seen = True
        else:
            output.append(line)
    if not seen:
        output.append(f"{key}:{value}")
    options.write_text("\n".join(output) + "\n", encoding="utf-8")
    # GTNH's DreamCoreMod replaces Minecraft's normal shutdown with a native
    # confirmation unless this pack setting is disabled.
    dream = game / "config" / "DreamCoreMod.properties"
    dream.parent.mkdir(parents=True, exist_ok=True)
    lines = dream.read_text(encoding="utf-8", errors="replace").splitlines() if dream.is_file() else []
    key, value, seen = "showConfirmExitWindow", "false", False
    output = []
    for line in lines:
        if line.partition("=")[0] == key:
            output.append(f"{key}={value}")
            seen = True
        else:
            output.append(line)
    if not seen:
        output.append(f"{key}={value}")
    dream.write_text("\n".join(output) + "\n", encoding="utf-8")


def prepare(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve()
    cfg = default_config()
    old = load_config(runtime) if config_path(runtime).is_file() else {}
    cfg.update(old)
    for key, value in (("clientZip", args.client_zip), ("serverZip", args.server_zip), ("prism", args.prism),
                       ("prismData", args.prism_data), ("java", args.java), ("username", args.username)):
        if value:
            cfg[key] = str(Path(value).resolve()) if key.endswith("Zip") or key in ("prism", "prismData", "java") else value
    if args.memory:
        cfg["memoryMiB"] = args.memory
    if args.server_memory:
        cfg["serverMemoryMiB"] = args.server_memory
    if args.window:
        cfg["window"] = args.window
    for key in ("clientZip", "serverZip"):
        if not cfg.get(key) or not Path(cfg[key]).is_file():
            raise RuntimeError_(f"{key} must name an existing ZIP")
    verify_pack_archive(Path(cfg["clientZip"]), "client")
    verify_pack_archive(Path(cfg["serverZip"]), "server")
    java = require_java17(resolve_executable(cfg.get("java", ""), cfg.get("javaCandidates", []), "Java executable"))
    cfg["java"] = java
    prism = resolve_executable(cfg.get("prism", ""), cfg.get("prismCandidates", []), "Prism Launcher executable")
    cfg["prism"] = prism
    server = runtime / "server"
    if not server.exists():
        extract_zip(Path(cfg["serverZip"]), server)
    elif not (server / MARKER).is_file():
        raise RuntimeError_(f"refusing to use unmarked server directory: {server}")
    save_json(server / MARKER, {"managedBy": "modbench", "kind": "server"})
    client = instance_dir(cfg)
    assert_managed_instance(client, allow_new=True)
    if not (client / MARKER).is_file():
        extract_zip(Path(cfg["clientZip"]), client, strip_root=True)
        save_json(client / MARKER, {"managedBy": "modbench", "kind": "prism-instance"})
    write_instance_config(client, java, int(cfg["memoryMiB"]), cfg.get("window", ""))
    set_client_options(client_game_dir(client))
    save_json(config_path(runtime), cfg)
    print(json.dumps({"runtime": str(runtime), "server": str(server), "instance": str(client)}, indent=2))


def artifact(kind: str) -> Path:
    path = REPO / "mods" / kind / "build" / "libs" / f"modbench-{kind}-0.1.0.jar"
    if not path.is_file():
        raise RuntimeError_(f"build artifact missing: {path}; run gradlew.bat :{kind}:build")
    return path


def bridge_is_live(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.25):
            return True
    except OSError:
        return False


def port_is_in_use(port: int) -> bool:
    return bridge_is_live(port)


def process_identity(pid: int) -> str | None:
    """A process start identity prevents a recycled PID from looking managed."""
    if pid <= 0:
        return None
    if os.name == "nt":
        script = f"$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue; if($p){{$p.StartTime.ToUniversalTime().ToFileTimeUtc()}}"
        try:
            result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True, timeout=3)
            return result.stdout.strip() or None
        except (OSError, subprocess.TimeoutExpired):
            return None
    try:
        return str(Path(f"/proc/{pid}/stat").read_text().split()[21])
    except (OSError, IndexError):
        return None


def recorded_process_is_running(record: dict[str, Any]) -> bool:
    pid = record.get("pid")
    identity = record.get("identity")
    if not isinstance(pid, int) or not identity:
        return False
    return process_identity(pid) == identity


def client_instance_is_running(instance: Path) -> bool:
    """Check a managed-instance lock/process without exposing process arguments."""
    if any((instance / name).exists() for name in ("instance.lock", ".instance.lock")):
        return True
    if os.name != "nt":
        return False
    # Print a boolean only. Command lines never reach our logs or stdout.
    script = (
        "$needle=[Environment]::GetEnvironmentVariable('MODBENCH_INSTANCE').Replace('\\','/');"
        "$hit=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |"
        "Where-Object {$_.Name -in @('java.exe','javaw.exe','java','javaw') -and $_.CommandLine -and $_.CommandLine.Replace('\\','/').IndexOf($needle,[StringComparison]::OrdinalIgnoreCase) -ge 0} |"
        "Select-Object -First 1; if ($hit) {'true'} else {'false'}"
    )
    env = os.environ.copy(); env["MODBENCH_INSTANCE"] = str(instance.resolve())
    try:
        result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script], env=env,
                                capture_output=True, text=True, timeout=5, check=False)
        if result.returncode != 0:
            return True
        answer = result.stdout.strip().lower()
        return answer != "false"
    except (OSError, subprocess.TimeoutExpired):
        # An unavailable inspector is not evidence that the game is stopped.
        return True


def component_sides(kind: str, only: str = "") -> list[str]:
    """Which managed installations carry this jar: client, server, or both for the coremod. ``only`` narrows it to
    one side, for a server that is pinned elsewhere (a container) while the client keeps changing."""
    sides = [side for side, members in (("client", CLIENT_COMPONENTS), ("server", SERVER_COMPONENTS)) if kind in members and only in ("", side)]
    if not sides:
        raise RuntimeError_(f"unknown managed component: {kind}")
    return sides


def assert_component_stopped(kind: str, runtime: Path, cfg: dict[str, Any], only: str = "") -> None:
    for side in component_sides(kind, only):
        port = CLIENT_PORT if side == "client" else SERVER_PORT
        if bridge_is_live(port):
            raise RuntimeError_(f"refusing to replace {kind} jar while its bridge port {port} is occupied")
        if side == "client":
            instance = instance_dir(cfg)
            assert_managed_instance(instance)
            if client_instance_is_running(instance):
                raise RuntimeError_("refusing to replace client jar while the managed Prism instance is running")
        elif recorded_process_is_running(load_json(runtime / "server-process.json")):
            raise RuntimeError_("refusing to replace server jar while the managed server process is running")


def managed_mods_dir(side: str, runtime: Path, cfg: dict[str, Any]) -> Path:
    if side == "client":
        instance = instance_dir(cfg)
        assert_managed_instance(instance)
        return client_game_dir(instance) / "mods"
    server = runtime / "server"
    if not (server / MARKER).is_file():
        raise RuntimeError_(f"refusing to modify unmarked server directory: {server}")
    return server / "mods"


def managed_jar_target(kind: str, side: str, runtime: Path, cfg: dict[str, Any]) -> Path:
    return managed_mods_dir(side, runtime, cfg) / f"modbench-{kind}.jar"


def backup_path(kind: str, side: str, runtime: Path) -> Path:
    return runtime / "backups" / side / f"modbench-{kind}.previous.jar"


def install_jar(kind: str, cfg: dict[str, Any], runtime: Path, only: str = "", source: Path | None = None) -> list[Path]:
    """Copy the built jar into every managed side that carries it, keeping each side's previous jar for rollback."""
    assert_component_stopped(kind, runtime, cfg, only)
    source = source or artifact(kind)
    installed = load_json(runtime / "installed.json")
    targets = []
    for side in component_sides(kind, only):
        target = managed_jar_target(kind, side, runtime, cfg)
        target.parent.mkdir(parents=True, exist_ok=True)
        backup = backup_path(kind, side, runtime)
        temp = target.with_suffix(".jar.new")
        shutil.copy2(source, temp)
        if target.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup_new = backup.with_suffix(".jar.new")
            shutil.copy2(target, backup_new)
            os.replace(backup_new, backup)
        os.replace(temp, target)
        legacy = target.with_name("modbench-control.jar")  # the coremod's name before it became core; two coremods crash FML
        if kind == "core" and legacy.is_file():
            backup.parent.mkdir(parents=True, exist_ok=True)
            os.replace(legacy, backup.with_name("modbench-control.legacy.jar"))
        installed.setdefault(kind, {})[side] = {"target": str(target), "backup": str(backup), "source": str(source)}
        targets.append(target)
    save_json(runtime / "installed.json", installed)
    return targets


def rollback_jar(kind: str, runtime: Path, only: str = "") -> list[Path]:
    """Restore the previous jar on every side; nothing moves unless every side has a valid backup."""
    sides = component_sides(kind, only)
    cfg = load_config(runtime) if "client" in sides else {}
    assert_component_stopped(kind, runtime, cfg, only)
    installed = load_json(runtime / "installed.json").get(kind, {})
    moves = []
    for side in sides:
        details = installed.get(side, {}) if isinstance(installed, dict) else {}
        target, backup = Path(details.get("target", "")), Path(details.get("backup", ""))
        if (not details.get("target") or not backup.is_file() or target.resolve() != managed_jar_target(kind, side, runtime, cfg).resolve()
                or backup.resolve() != backup_path(kind, side, runtime).resolve()):
            raise RuntimeError_(f"no managed {kind} backup is available for the {side}")
        moves.append((backup, target))
    for backup, target in moves:
        os.replace(backup, target)
    return [target for _, target in moves]


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def set_server_properties(server: Path, accept_eula: bool, server_ip: str = "127.0.0.1") -> None:
    eula = server / "eula.txt"
    if not eula.is_file() or "eula=true" not in eula.read_text(encoding="utf-8", errors="replace").lower():
        if not accept_eula:
            raise RuntimeError_("server EULA is not accepted; re-run start-server with --accept-eula")
        eula.write_text("# Accepted explicitly by modbench runtime\neula=true\n", encoding="utf-8")
    properties = server / "server.properties"
    lines = properties.read_text(encoding="utf-8", errors="replace").splitlines() if properties.is_file() else []
    wanted = {"server-ip": server_ip, "server-port": "25575", "online-mode": "false", "white-list": "false", "enable-rcon": "false"}
    seen: set[str] = set()
    out = []
    for line in lines:
        key = line.split("=", 1)[0]
        if key in wanted:
            out.append(f"{key}={wanted[key]}")
            seen.add(key)
        else:
            out.append(line)
    out.extend(f"{key}={value}" for key, value in wanted.items() if key not in seen)
    properties.write_text("\n".join(out) + "\n", encoding="utf-8")


def start_server(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime); server = runtime / "server"
    if not (server / MARKER).is_file(): raise RuntimeError_("run prepare first")
    pid_file = runtime / "server-process.json"
    record = load_json(pid_file)
    if recorded_process_is_running(record): raise RuntimeError_("managed server is already running")
    if bridge_is_live(SERVER_PORT): raise RuntimeError_("server bridge port is already occupied")
    if port_is_in_use(25575): raise RuntimeError_("Minecraft server port 25575 is already occupied")
    set_server_properties(server, args.accept_eula)
    java = require_java17(resolve_executable(cfg.get("java", ""), cfg.get("javaCandidates", []), "Java executable"))
    launcher = server / "lwjgl3ify-forgePatches.jar"; args_file = server / "java9args.txt"
    if not launcher.is_file() or not args_file.is_file(): raise RuntimeError_("server pack lacks java9args.txt or lwjgl3ify-forgePatches.jar")
    memory = int(cfg.get("serverMemoryMiB", 4096))
    if memory < 1024: raise RuntimeError_("serverMemoryMiB must be at least 1024")
    command = [java, "-Xms1G", f"-Xmx{memory}M", "-Dfml.readTimeout=180", f"-Dmodbench.port={SERVER_PORT}", f"-Dmodbench.tokenFile={Path.home() / '.moddedbench' / f'bridge-{SERVER_PORT}.token'}", "@java9args.txt", "-jar", launcher.name, "nogui"]
    if getattr(args, "dev_fixtures", False):
        command.insert(1, "-Dmodbench.devFixtures=true")
    log = (runtime / "logs" / "server.log"); log.parent.mkdir(parents=True, exist_ok=True)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
    with log.open("ab") as output:
        proc = subprocess.Popen(command, cwd=server, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    identity = process_identity(proc.pid)
    if not identity:
        raise RuntimeError_(f"the server process exited at once or could not be identified; see {log}")
    save_json(pid_file, {"pid": proc.pid, "identity": identity, "started": time.time(), "log": str(log)})
    print(json.dumps({"pid": proc.pid, "log": str(log)}))


def stop_server(args: argparse.Namespace) -> None:
    """Request authenticated bridge shutdown; never terminate an arbitrary PID."""
    runtime = Path(args.runtime).resolve()
    record = load_json(runtime / "server-process.json")
    if not recorded_process_is_running(record):
        raise RuntimeError_("no live managed server process record")
    try:
        sys.path.insert(0, str(REPO / "harness" / "mcp"))
        from kernel import Kernel  # optional dependency, only needed for the bridge protocol
        with Kernel(url=f"ws://127.0.0.1:{SERVER_PORT}/ws", timeout=10) as kernel:
            kernel.call("sys.shutdown", timeout=10)
    except Exception as exc:
        raise RuntimeError_(f"could not request authenticated server shutdown: {exc}; server was not terminated") from exc
    deadline = time.monotonic() + args.timeout
    while recorded_process_is_running(record) and time.monotonic() < deadline: time.sleep(0.25)
    if recorded_process_is_running(record): raise RuntimeError_("shutdown requested but server process is still live; no forced termination was performed")
    print("managed server process exited")


def stop_client(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime); instance = instance_dir(cfg); assert_managed_instance(instance)
    if not client_instance_is_running(instance):
        raise RuntimeError_("managed client is not running")
    try:
        sys.path.insert(0, str(REPO / "harness" / "mcp"))
        from kernel import Kernel
        with Kernel(url=f"ws://127.0.0.1:{CLIENT_PORT}/ws", timeout=10) as kernel:
            kernel.call("sys.shutdown", timeout=10)
    except Exception as exc:
        raise RuntimeError_(f"could not request authenticated client shutdown: {exc}; client was not terminated") from exc
    deadline = time.monotonic() + args.timeout
    while client_instance_is_running(instance) and time.monotonic() < deadline: time.sleep(0.25)
    if client_instance_is_running(instance): raise RuntimeError_("shutdown requested but client is still live; no forced termination was performed")
    print("managed client process exited")


def build(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime)
    java = resolve_executable(cfg.get("java", ""), cfg.get("javaCandidates", []), "Java executable")
    gradlew = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.is_file(): raise RuntimeError_(f"missing Gradle wrapper: {gradlew}")
    log = runtime / "logs" / "build.log"; log.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy(); env["JAVA_HOME"] = str(Path(java).resolve().parents[1])
    with log.open("ab") as output:
        result = subprocess.run([str(gradlew), "build"], cwd=REPO, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode: raise RuntimeError_(f"GTNH build failed; see {log}")
    print("GTNH build completed")


def open_client_kernel():
    sys.path.insert(0, str(REPO / "harness" / "mcp"))
    from kernel import Kernel
    return Kernel(url=f"ws://127.0.0.1:{CLIENT_PORT}/ws", timeout=10)


def wait_for_client_join(timeout: float) -> dict[str, Any]:
    """Wait for the title screen, then make the bridge-owned FML connection."""
    deadline = time.monotonic() + timeout
    kernel = None
    connected = False
    last_error = "bridge has not accepted a request yet"
    while time.monotonic() < deadline:
        try:
            if kernel is None:
                kernel = open_client_kernel()
            gui = kernel.call("obs.gui")
            screen = str(gui.get("class") or "") if isinstance(gui, dict) else ""
            if "GuiDisconnected" in screen or "GuiError" in screen:
                try: kernel.close()
                except Exception: pass
                raise RuntimeError_(f"client reached {screen}; inspect the client screen/log, then retry")
            if not connected:
                if screen not in MAIN_MENU_SCREENS:
                    time.sleep(1)
                    continue
                kernel.call("sys.connect", host="127.0.0.1", port=25575)
                connected = True
            world = kernel.call("obs.world")
            if isinstance(world, dict) and world.get("inWorld") is True:
                player = kernel.call("obs.player")
                if isinstance(player, dict) and player.get("name") and player.get("uuid"):
                    result = {"joined": True, "name": player["name"], "uuid": player["uuid"],
                              "uuidScope": player.get("uuidScope", "client_profile")}
                    try: kernel.close()
                    except Exception: pass
                    return result
            time.sleep(1)
        except RuntimeError_:
            raise
        except Exception as exc:
            # The bridge starts late in full-pack startup. Retrying does not
            # affect the launched process, and authenticated RPC begins only
            # after it is available.
            last_error = f"{type(exc).__name__}: {exc}"
            if kernel is not None:
                try: kernel.close()
                except Exception: pass
                kernel = None
            time.sleep(2)
    phase = "after sys.connect" if connected else "while waiting for the main menu bridge"
    if kernel is not None:
        try: kernel.close()
        except Exception: pass
    raise RuntimeError_(f"client did not join within {timeout:g}s ({phase}); last bridge error: {last_error}; process remains running")


def managed_client_hashes(cfg: dict[str, Any]) -> tuple[dict[str, str], list[str]]:
    """Hash only the fixed managed targets; client and core are mandatory."""
    mods = client_game_dir(instance_dir(cfg)) / "mods"
    hashes, missing = {}, []
    for kind in ("client", "core"):
        target = mods / f"modbench-{kind}.jar"
        if not target.is_file():
            missing.append(kind)
        else:
            hashes[kind] = sha256_file(target)
    target = mods / "modbench-baritone.jar"
    if target.exists():
        if not target.is_file():
            missing.append("baritone")
        else:
            hashes["baritone"] = sha256_file(target)
    return hashes, missing


def valid_client_hash_set(value: Any) -> dict[str, str] | None:
    if not isinstance(value, dict) or set(value) - CLIENT_COMPONENTS or not {"client", "core"} <= set(value):
        return None
    if not all(isinstance(digest, str) and len(digest) == 64 and all(c in "0123456789abcdef" for c in digest)
               for digest in value.values()):
        return None
    return value


def record_joined_client_build(cfg: dict[str, Any], runtime: Path) -> None:
    """Remember a bounded set of successful managed launches, never source paths."""
    hashes, missing = managed_client_hashes(cfg)
    if missing:
        return
    previous = load_json(runtime / "client-build-history.json").get("sets", [])
    known = [candidate for candidate in previous if valid_client_hash_set(candidate) is not None]
    retained = [candidate for candidate in known if candidate != hashes]
    save_json(runtime / "client-build-history.json", {"sets": [hashes, *retained[:4]]})


def verify_client_build(cfg: dict[str, Any], runtime: Path = RUNTIME) -> None:
    """Require a complete current build, or one complete managed set that joined before."""
    hashes, missing = managed_client_hashes(cfg)
    stale = [f"install-{kind}" for kind in missing]
    local = {}
    for kind in hashes:
        try:
            local[kind] = sha256_file(artifact(kind))
            if local[kind] != hashes[kind]:
                stale.append(f"install-{kind}")
        except RuntimeError_:
            stale.append(f"install-{kind}")
    if not missing and hashes == local:
        return
    history = load_json(runtime / "client-build-history.json").get("sets", [])
    if not missing and any(valid_client_hash_set(candidate) == hashes for candidate in history):
        return
    if stale:
        raise RuntimeError_("client modules do not match the local build or a previously joined managed set; run "
                            + ", ".join(sorted(set(stale))) + " before launch")
    raise RuntimeError_("client modules are an unrecognized mix; install matching components or roll back to a previously joined managed set")


def launch_client(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime); path = instance_dir(cfg); assert_managed_instance(path)
    if not getattr(args, "installed_as_is", False):  # a supervised deploy installs jars this checkout did not build
        verify_client_build(cfg, runtime)
    prism = resolve_executable(cfg.get("prism", ""), cfg.get("prismCandidates", []), "Prism Launcher executable")
    username = args.username or cfg.get("username", "ModbenchDev")
    if not username:
        raise RuntimeError_("username must not be empty")
    # Do not use Prism's --server: that route can bypass FML's setup in this
    # legacy pack. The authenticated bridge connects after the title screen.
    subprocess.Popen([prism, "--dir", str(cfg["prismData"]), "-l", INSTANCE_NAME, "--offline", username], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0)
    joined = wait_for_client_join(args.timeout)
    record_joined_client_build(cfg, runtime)
    print(json.dumps(joined))


def provision_client(args: argparse.Namespace) -> None:
    """Use Prism's existing selected account for the one-time pack download."""
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime); path = instance_dir(cfg); assert_managed_instance(path)
    prism = resolve_executable(cfg.get("prism", ""), cfg.get("prismCandidates", []), "Prism Launcher executable")
    # Deliberately omit --offline and --server: Prism chooses its already
    # logged-in account and downloads the vanilla/Forge/LWJGL dependencies.
    subprocess.Popen([prism, "--dir", str(cfg["prismData"]), "-l", INSTANCE_NAME], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0)
    print("Prism provisioning launch requested")


def status(args: argparse.Namespace) -> None:
    runtime = Path(args.runtime).resolve(); cfg = load_config(runtime)
    record = load_json(runtime / "server-process.json")
    print(json.dumps({"runtime": str(runtime), "clientInstance": str(instance_dir(cfg)), "clientBridgeListener": bridge_is_live(CLIENT_PORT), "serverBridgeListener": bridge_is_live(SERVER_PORT), "serverProcessRecordedRunning": recorded_process_is_running(record), "serverRecord": record}, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog="prepare writes a Prism instance on disk but cannot safely restart an already-running Prism Launcher; restart Prism manually if it does not discover the instance. Run provision-client once with an existing Prism account when its cache lacks game libraries/assets, then use launch-client for the offline development identity.",
    )
    parser.add_argument("--runtime", default=str(RUNTIME))
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare"); p.add_argument("--client-zip"); p.add_argument("--server-zip"); p.add_argument("--prism"); p.add_argument("--prism-data"); p.add_argument("--java"); p.add_argument("--username"); p.add_argument("--memory", type=int); p.add_argument("--server-memory", type=int); p.add_argument("--window", help="client window size, e.g. 1920x1080"); p.set_defaults(func=prepare)
    sub.add_parser("status").set_defaults(func=status)
    p = sub.add_parser("start-server"); p.add_argument("--accept-eula", action="store_true"); p.add_argument("--dev-fixtures", action="store_true", help="enable privileged, journaled development fixtures for smoke tests"); p.set_defaults(func=start_server)
    p = sub.add_parser("stop-server"); p.add_argument("--timeout", type=float, default=30); p.set_defaults(func=stop_server)
    p = sub.add_parser("stop-client"); p.add_argument("--timeout", type=float, default=30); p.set_defaults(func=stop_client)
    sub.add_parser("build").set_defaults(func=build)
    for kind in ("client", "core", "baritone", "server"):
        p = sub.add_parser(f"install-{kind}"); p.add_argument("--side", choices=("client", "server"), default="", help="only this side, e.g. client while the server is a pinned container"); p.set_defaults(func=lambda a, k=kind: print(*install_jar(k, load_config(Path(a.runtime).resolve()), Path(a.runtime).resolve(), a.side), sep="\n"))
        p = sub.add_parser(f"rollback-{kind}"); p.add_argument("--side", choices=("client", "server"), default=""); p.set_defaults(func=lambda a, k=kind: print(*rollback_jar(k, Path(a.runtime).resolve(), a.side), sep="\n"))
    p = sub.add_parser("launch-client"); p.add_argument("--username"); p.add_argument("--timeout", type=float, default=300); p.add_argument("--installed-as-is", action="store_true", help="skip the check that the installed jars match this checkout's build (a supervised deploy installed them)"); p.set_defaults(func=launch_client)
    sub.add_parser("provision-client").set_defaults(func=provision_client)
    args = parser.parse_args(argv)
    try:
        args.func(args); return 0
    except RuntimeError_ as exc:
        print(f"error: {exc}", file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
