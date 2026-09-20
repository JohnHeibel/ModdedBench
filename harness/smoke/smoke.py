# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Live first-milestone acceptance checks for the GTNH client/server bridges.

Run only after the managed GTNH client and dedicated server are connected:
    python harness/smoke/smoke.py
"""
from __future__ import annotations

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import math
from pathlib import Path
import sys
import tempfile
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from kernel import BridgeError, Kernel, bridge_url  # noqa: E402

CLIENT_URL = bridge_url()
SERVER_URL = bridge_url("server")


def fail(result: dict, name: str, exc: BaseException | str) -> None:
    result["failures"].append({"check": name, "error": str(exc)})


def require(result: dict, name: str, condition: bool, detail: Any = "") -> bool:
    if not condition:
        fail(result, name, detail or "assertion failed")
        return False
    result["checks"].append(name)
    return True


def call(result: dict, name: str, kernel: Kernel, method: str, *, timeout: float = 12, **params: Any) -> Any | None:
    try:
        return kernel.call(method, timeout=timeout, **params)
    except (BridgeError, ConnectionError, TimeoutError, ValueError) as exc:
        fail(result, name, exc)
        return None


def method_names(caps: dict) -> set[str]:
    return {entry["name"] for entry in caps.get("methods", []) if isinstance(entry, dict) and "name" in entry}


def reload_proof(result: dict) -> None:
    """Exercise the profile loader in isolation, never touching profile source files."""
    import mbtool
    import server

    srv = server.Server()
    try:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "temporary.py"
            source.write_text('from mbtool import tool\n@tool(rung=0)\ndef mb_temp() -> str:\n """temp"""\n return "v1"\n')
            srv.tools_dir = directory
            require(result, "mcp_reload_v1", not any(m.startswith("ERROR") for m in srv.check_reload(force=True)))
            module = srv.modules.get(str(source)).module
            require(result, "mcp_reload_v1_value", module.mb_temp() == "v1")
            source.write_text('from mbtool import tool\n@tool(rung=0)\ndef mb_temp() -> str:\n """temp"""\n return "v2"\n')
            require(result, "mcp_reload_v2", not any(m.startswith("ERROR") for m in srv.check_reload(force=True)))
            module = srv.modules.get(str(source)).module
            require(result, "mcp_reload_v2_value", module.mb_temp() == "v2")
            source.write_text("not valid Python\n")
            errors = srv.check_reload(force=True)
            require(result, "mcp_reload_last_good", any(m.startswith("ERROR") for m in errors)
                    and srv.modules.get(str(source)).module.mb_temp() == "v2")
    finally:
        srv._workers.shutdown(wait=False, cancel_futures=True)
        srv._control_workers.shutdown(wait=False, cancel_futures=True)
        mbtool.drop_kernel()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, default=ROOT / ".runtime" / "evidence")
    parser.add_argument("--mcp-reload-proof", action="store_true", help="also prove isolated GTNH MCP hot reload")
    parser.add_argument("--expect-baritone", action="store_true", help="require the optional standalone navigation mod")
    args = parser.parse_args()
    result: dict[str, Any] = {"ok": False, "checks": [], "failures": [], "needs_attention": [], "evidence": {}}
    client = server = None
    try:
        client, server = Kernel(url=CLIENT_URL, timeout=15), Kernel(url=SERVER_URL, timeout=15)
        client_caps = call(result, "client_capabilities", client, "sys.capabilities")
        server_caps = call(result, "server_capabilities", server, "sys.capabilities")
        if not isinstance(client_caps, dict) or not isinstance(server_caps, dict):
            return finish(result)
        require(result, "client_caps", client_caps.get("side") == "client" and isinstance(client_caps.get("baritone"), bool),
                {"side": client_caps.get("side"), "baritone": client_caps.get("baritone")})
        if args.expect_baritone:
            require(result, "baritone_available", client_caps.get("baritone") is True and client_caps.get("inputOwnership") is True)
        require(result, "server_caps", server_caps.get("side") == "server" and server_caps.get("baritone") is False,
                {"side": server_caps.get("side"), "baritone": server_caps.get("baritone")})
        require(result, "client_methods", {"obs.world", "obs.player", "act.look", "act.input", "act.stop", "sys.screenshot"}
                <= method_names(client_caps), "missing client method")
        require(result, "server_methods", {"obs.world", "obs.players"} <= method_names(server_caps), "missing server method")

        world = call(result, "client_obs_world", client, "obs.world")
        player = call(result, "client_obs_player", client, "obs.player")
        players = call(result, "server_obs_players", server, "obs.players")
        server_world = call(result, "server_obs_world", server, "obs.world")
        if not all((isinstance(world, dict), isinstance(player, dict), isinstance(players, list), isinstance(server_world, dict))):
            return finish(result)
        client_dimension = player.get("dimension")
        matching = [p for p in players if isinstance(p, dict) and p.get("name") == player.get("name")
                    and p.get("entityId") == player.get("entityId") and p.get("dimension") == client_dimension]
        server_dimension = next((d for d in server_world.get("dimensions", []) if isinstance(d, dict)
                                 and d.get("dimension") == client_dimension), None)
        result["identity"] = {
            "client": {"name": player.get("name"), "uuid": player.get("uuid"), "uuidScope": player.get("uuidScope"),
                       "entityId": player.get("entityId"), "dimension": client_dimension},
            "server_players": [{"name": p.get("name"), "uuid": p.get("uuid"), "uuidScope": p.get("uuidScope"),
                                "entityId": p.get("entityId"), "dimension": p.get("dimension")} for p in players if isinstance(p, dict)],
        }
        result["world_time"] = {"dimension": client_dimension, "client": world.get("time"),
                                "server": server_dimension.get("time") if server_dimension else None}
        require(result, "dedicated_client", world.get("integratedServer") is False and world.get("inWorld") is True, world)
        require(result, "uuid_scopes", player.get("uuidScope") == "client_profile"
                and all(p.get("uuidScope") == "server" for p in players if isinstance(p, dict)), result["identity"])
        require(result, "same_player_entity", len(matching) == 1, result["identity"])

        inventory = call(result, "obs_inventory", client, "obs.inventory")
        container = call(result, "obs_container", client, "obs.container")
        gui = call(result, "obs_gui", client, "obs.gui")
        position = player.get("pos") or []
        block = call(result, "obs_block", client, "obs.block", x=math.floor(position[0]), y=math.floor(position[1]) - 1,
                     z=math.floor(position[2])) if len(position) == 3 else None
        require(result, "observation_shapes", isinstance(inventory, dict) and isinstance(container, dict)
                and isinstance(gui, dict) and isinstance(block, dict), "inventory/container/gui/block response missing")
        if gui and gui.get("class") is not None:
            result["needs_attention"].append({"gui": gui.get("class"), "reason": "close the current GUI manually before action checks"})
        else:
            yaw, pitch = player["yaw"], player["pitch"]
            changed = call(result, "act_look_change", client, "act.look", yaw=yaw + 1.0, pitch=pitch)
            restored = call(result, "act_look_restore", client, "act.look", yaw=yaw, pitch=pitch)
            require(result, "look_changed_and_restored", isinstance(changed, dict) and abs(changed.get("yaw", yaw) - yaw) > .2
                    and isinstance(restored, dict) and abs(restored.get("yaw", yaw) - yaw) < .2, "yaw response mismatch")
            before_state = call(result, "player_before_sneak", client, "obs.player")
            before = before_state.get("pos") if isinstance(before_state, dict) else None
            short = call(result, "act_sneak_short", client, "act.input", keys=["sneak"], ticks=2, timeout=12)
            after = short.get("player", {}).get("pos") if isinstance(short, dict) else None
            require(result, "sneak_no_move", isinstance(before, list) and isinstance(after, list)
                    and sum((a - b) ** 2 for a, b in zip(after, before)) < .01,
                    {"before": before, "after": after})
            with ThreadPoolExecutor(max_workers=1) as pool:
                action = pool.submit(client.call, "act.input", timeout=25, keys=["sneak"], ticks=200)
                active = None
                until = time.monotonic() + 8
                while time.monotonic() < until:
                    active = call(result, "control_poll", client, "obs.player")
                    if isinstance(active, dict) and active.get("controlActive"):
                        break
                    time.sleep(.1)
                require(result, "control_active", isinstance(active, dict) and active.get("controlActive") is True, active)
                stopped = call(result, "act_stop", client, "act.stop", timeout=12)
                try:
                    action.result(timeout=12)
                    fail(result, "long_sneak_cancel", "action completed instead of being cancelled")
                except BridgeError as exc:
                    result["evidence"]["long_sneak_action_error"] = {"code": exc.code, "message": exc.msg}
                    require(result, "long_sneak_cancel", exc.code == "cancelled", exc.code)
                except Exception as exc:
                    fail(result, "long_sneak_cancel", exc)
            state = call(result, "control_released", client, "obs.player")
            require(result, "control_released", isinstance(stopped, dict) and stopped.get("stopped") is True
                    and isinstance(state, dict) and state.get("controlActive") is False, state)

        shot = call(result, "screenshot", client, "sys.screenshot", timeout=20)
        if isinstance(shot, dict):
            try:
                args.evidence_dir.mkdir(parents=True, exist_ok=True)
                path = args.evidence_dir / ("gtnh-smoke-" + time.strftime("%Y%m%d-%H%M%S") + ".png")
                path.write_bytes(base64.b64decode(shot["png"], validate=True))
                result["evidence"]["screenshot"] = {"path": str(path), "width": shot.get("width"), "height": shot.get("height")}
                result["checks"].append("screenshot_saved")
            except (KeyError, TypeError, ValueError) as exc:
                fail(result, "screenshot_saved", exc)
        if args.mcp_reload_proof:
            reload_proof(result)
    except (ConnectionError, TimeoutError, BridgeError, ValueError) as exc:
        fail(result, "connect_or_protocol", exc)
    finally:
        if client: client.close()
        if server: server.close()
    return finish(result)


def finish(result: dict) -> int:
    result["complete"] = not result["needs_attention"]
    result["ok"] = not result["failures"] and result["complete"]
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
