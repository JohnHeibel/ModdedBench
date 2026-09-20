# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Container entry point for the pinned GTNH server: unpack once, pin the jars, exec Java."""
import json, os, shutil, sys, types
from pathlib import Path

sys.path.insert(0, "/opt/modbench")
# The launcher takes its port constants from the bridge client, which needs websockets; this image never opens a bridge.
sys.modules["kernel"] = types.SimpleNamespace(bridge_url=lambda side="client": f"ws://127.0.0.1:{47224 if side == 'server' else 47223}/ws")
import runtime

HOME, DATA = Path("/opt/modbench"), Path("/data")
if not (DATA / runtime.MARKER).is_file():
    name = next(a["file"] for a in json.loads((HOME / "pack.lock.json").read_text())["archives"] if a["side"] == "server")
    runtime.verify_pack_archive(Path("/pack") / name, "server", HOME / "pack.lock.json")
    runtime.extract_zip(Path("/pack") / name, DATA)
    runtime.save_json(DATA / runtime.MARKER, {"managedBy": "modbench", "kind": "server"})
# The port is published to the host's loopback only; inside the container the server must listen on every interface.
runtime.set_server_properties(DATA, os.environ.get("EULA", "").lower() == "true", server_ip="")
for jar in ("modbench-server.jar", "modbench-core.jar"):
    shutil.copyfile(HOME / jar, DATA / "mods" / jar)
os.chdir(DATA)
os.execvp("java", ["java", "-Xms1G", f"-Xmx{int(os.environ.get('SERVER_MEMORY_MIB', '6144'))}M", "-Dfml.readTimeout=180",
                   "-Dmodbench.port=47224", "@java9args.txt", "-jar", "lwjgl3ify-forgePatches.jar", "nogui"])
