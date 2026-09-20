# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""File mailbox model adapter for ``autonomous_runner.py``.

Set ``MODBENCH_RUNNER_MAILBOX`` to a dedicated state directory. The runner writes
``model-request.json``; an operator atomically publishes ``model-response.json``.
This module is also a small response-writing CLI.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import threading
import time
from pathlib import Path
from typing import Any

MAX_MESSAGE = 1 * 1024 * 1024
_lock = threading.RLock()
_active: str | None = None


def _directory() -> Path:
    raw = os.environ.get("MODBENCH_RUNNER_MAILBOX")
    if not raw: raise RuntimeError("MODBENCH_RUNNER_MAILBOX must name a dedicated mailbox directory")
    path = Path(raw).resolve(); path.mkdir(parents=True, exist_ok=True); return path


def _atomic_json(path: Path, value: Any) -> None:
    encoded = json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"
    if len(encoded.encode("utf-8")) > MAX_MESSAGE: raise ValueError("mailbox message exceeds 1 MiB")
    temporary = path.with_suffix(f".{os.getpid()}.{secrets.token_hex(6)}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(encoded); handle.flush(); os.fsync(handle.fileno())
    os.replace(temporary, path)


def _bounded_json(path: Path) -> Any:
    if path.stat().st_size > MAX_MESSAGE: raise ValueError("mailbox response exceeds 1 MiB")
    return json.loads(path.read_text(encoding="utf-8"))


def _invalidate(request_id: str, reason: str) -> None:
    directory = _directory()
    _atomic_json(directory / "model-cancelled.json",
                 {"requestId": request_id, "cancelledAt": time.time(), "reason": reason})


async def infer(request: dict[str, Any]) -> dict[str, Any]:
    """Publish one request and await only its matching response."""
    global _active
    request_id = secrets.token_urlsafe(32)
    with _lock:
        if _active is not None: raise RuntimeError("file adapter already has an active request")
        _active = request_id
    directory = _directory(); response_path = directory / "model-response.json"
    timeout = float(os.environ.get("MODBENCH_RUNNER_MODEL_TIMEOUT_S", "3600"))
    poll = float(os.environ.get("MODBENCH_RUNNER_MODEL_POLL_S", "0.1"))
    if not 0.01 <= poll <= 5 or not 1 <= timeout <= 86400:
        with _lock: _active = None
        raise ValueError("model poll must be 0.01..5 seconds and timeout 1..86400 seconds")
    _atomic_json(directory / "model-request.json",
                 {"requestId": request_id, "createdAt": time.time(), "request": request})
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            with _lock:
                if _active != request_id: raise asyncio.CancelledError()
            if response_path.is_file():
                try: envelope = _bounded_json(response_path)
                except (OSError, json.JSONDecodeError): envelope = None
                if isinstance(envelope, dict) and envelope.get("requestId") == request_id:
                    decision = envelope.get("decision")
                    if not isinstance(decision, dict): raise ValueError("matching response decision must be an object")
                    try: response_path.unlink()
                    except FileNotFoundError: pass
                    return decision
            await asyncio.sleep(poll)
        _invalidate(request_id, "timeout")
        raise TimeoutError(f"no matching model response within {timeout:g}s")
    except asyncio.CancelledError:
        _invalidate(request_id, "cancelled")
        raise
    finally:
        with _lock:
            if _active == request_id: _active = None


def cancel() -> None:
    """Invalidate the active ID; a late response with it can never satisfy a later request."""
    global _active
    with _lock:
        request_id, _active = _active, None
    if request_id is not None: _invalidate(request_id, "provider_cancel")


def respond(request_path: Path, decision_path: Path, response_path: Path | None = None) -> Path:
    request = _bounded_json(request_path)
    if not isinstance(request, dict) or not isinstance(request.get("requestId"), str):
        raise ValueError("request file has no requestId")
    decision = _bounded_json(decision_path)
    if not isinstance(decision, dict): raise ValueError("decision file must contain an object")
    target = response_path or request_path.with_name("model-response.json")
    _atomic_json(target, {"requestId": request["requestId"], "respondedAt": time.time(), "decision": decision})
    return target


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("respond")
    command.add_argument("--request", required=True, type=Path)
    command.add_argument("--decision", required=True, type=Path)
    command.add_argument("--response", type=Path)
    args = parser.parse_args(argv)
    try:
        print(respond(args.request, args.decision, args.response)); return 0
    except (OSError, ValueError) as exc:
        print(f"mailbox error: {exc}", file=os.sys.stderr); return 2


if __name__ == "__main__": raise SystemExit(main())
