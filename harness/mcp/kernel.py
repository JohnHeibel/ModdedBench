# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Thread-safe synchronous bridge API, with one receiver routing concurrent replies.

MCP runs calls in workers; stop/step/observations can overtake a waiting action.
Event buffering is bounded. call_reply preserves the complete protocol envelope.
"""
from __future__ import annotations
import contextvars
import itertools
import json
import os
import threading
import time
from collections import deque
from concurrent.futures import Future, TimeoutError as FutureTimeout
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import urlparse
from websockets.sync.client import connect

DEFAULT_URL = os.environ.get("MB_BRIDGE_URL", "ws://127.0.0.1:47223/ws")
DEFAULT_TOKEN = os.environ.get("MB_BRIDGE_TOKEN")
cancel_scope = contextvars.ContextVar("bridge_cancel_scope", default=None)
reply_trace = contextvars.ContextVar("bridge_reply_trace", default=None)

class CancellationScope:
    """All RPCs in one MCP call, including compositions, inherit cancellation."""
    def __init__(self):
        self.lock = threading.Lock()
        self.pending = set()
        self.cancelled = False

    def add(self, kernel, rid):
        with self.lock:
            if self.cancelled:
                raise BridgeError("cancelled", "tool call cancelled")
            self.pending.add((kernel, rid))

    def remove(self, kernel, rid):
        with self.lock:
            self.pending.discard((kernel, rid))

    def cancel(self):
        with self.lock:
            self.cancelled = True
            pending = list(self.pending)
        for kernel, rid in pending:
            try:
                # Sent after the original request under the same lock; no reply wait on cancellation.
                with kernel._send_lock:
                    kernel.ws.send(json.dumps({"id": next(kernel._ids), "method": "requests.cancel", "params": {"requestId": rid}}))
            except Exception:
                pass

class BridgeError(RuntimeError):
    def __init__(self, code: str, msg: str, method: str = "?", reply: dict | None = None):
        super().__init__(f"{method}: {code}: {msg}")
        self.code, self.msg, self.method, self.reply = code, msg, method, reply

@dataclass
class Reply:
    ok: bool
    tick: int
    seq: int
    cost_ms: float
    data: Any = None
    error: dict | None = None
    raw: dict = field(default_factory=dict)

class Kernel:
    def __init__(self, url: str = DEFAULT_URL, token: str | None = DEFAULT_TOKEN, timeout: float = 60.0,
                 on_event: Callable[[dict], None] | None = None, connect_retries: int = 1, retry_delay: float = 2.0,
                 event_capacity: int = 1024):
        if timeout <= 0 or event_capacity < 1:
            raise ValueError("timeout and event_capacity must be positive")
        self.url, self.timeout, self.on_event = url, timeout, on_event
        self.events_buf = deque(maxlen=event_capacity)
        self.events_dropped = 0
        self._ids = itertools.count(1)
        self._lock = threading.RLock()
        self._send_lock = threading.Lock()
        self._events_ready = threading.Condition(self._lock)
        self._pending: dict[int, Future] = {}
        self._closed = False
        last = None
        for attempt in range(max(1, connect_retries)):
            try:
                self.ws = connect(url, open_timeout=10, max_size=16 * 1024 * 1024)
                break
            except OSError as e:
                last = e
                if attempt + 1 < connect_retries:
                    time.sleep(retry_delay)
        else:
            raise ConnectionError(f"bridge not reachable at {url}: {last}")
        self._reader = threading.Thread(target=self._receive, name="mb-replies", daemon=True)
        self._reader.start()
        # Only discover local credentials for a loopback endpoint.
        endpoint = urlparse(url)
        if token is None and endpoint.hostname in ("127.0.0.1", "localhost", "::1"):
            path = Path(os.environ.get("MB_BRIDGE_TOKEN_FILE", str(Path.home() / ".moddedbench" / f"bridge-{endpoint.port or 47223}.token")))
            if path.is_file():
                token = path.read_text().strip()
        if token:
            try:
                r = self._request({"id": 0, "auth": token}, timeout)
                if not r.ok:
                    raise BridgeError((r.error or {}).get("code", "unauthorized"), (r.error or {}).get("msg", ""), "auth", r.raw)
            except BaseException:
                self.close()
                raise

    @property
    def connected(self) -> bool:
        with self._lock:
            return not self._closed

    def _fail_pending(self, error: Exception):
        with self._lock:
            self._closed = True
            pending, self._pending = self._pending, {}
            self._events_ready.notify_all()
        for future in pending.values():
            if not future.done():
                future.set_exception(error)

    def close(self) -> None:
        self._fail_pending(ConnectionError("bridge connection closed"))
        self.ws.close()
        if threading.current_thread() is not self._reader:
            self._reader.join(timeout=2)

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()

    def _receive(self):
        try:
            while True:
                frame = json.loads(self.ws.recv())
                if "event" in frame:
                    with self._events_ready:
                        if len(self.events_buf) == self.events_buf.maxlen:
                            self.events_dropped += 1
                        self.events_buf.append(frame)
                        self._events_ready.notify_all()
                    # Callbacks must be quick and must not synchronously call this Kernel.
                    if self.on_event:
                        try:
                            self.on_event(frame)
                        except Exception:
                            pass
                    continue
                with self._lock:
                    future = self._pending.pop(frame.get("id"), None)
                if future is not None:
                    future.set_result(Reply(ok=frame.get("ok", False), tick=frame.get("tick", -1),
                        seq=frame.get("seq", -1), cost_ms=frame.get("cost_ms", 0), data=frame.get("data"),
                        error=frame.get("error"), raw=frame))
        except Exception as e:
            self._fail_pending(ConnectionError(f"bridge receive failed: {e}"))

    def _request(self, req: dict, timeout: float) -> Reply:
        future = Future()
        rid = req["id"]
        with self._lock:
            if self._closed:
                raise ConnectionError("bridge connection closed")
            self._pending[rid] = future
        scope = cancel_scope.get()
        try:
            with self._send_lock:
                if scope is not None:
                    scope.add(self, rid)
                self.ws.send(json.dumps(req, allow_nan=False))
            return future.result(timeout=timeout)
        except FutureTimeout:
            raise TimeoutError(f"no bridge reply within {timeout}s for {req.get('method', 'auth')}") from None
        finally:
            if scope is not None:
                scope.remove(self, rid)
            with self._lock:
                self._pending.pop(rid, None)

    def call_reply(self, method: str, /, timeout: float | None = None, **params) -> Reply:
        timeout = self.timeout if timeout is None else timeout
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        # The server deadline precedes the socket deadline, so timed out work is canceled there.
        params.setdefault("_timeout_ms", max(1, int(timeout * 1000) - 250))
        r = self._request({"id": next(self._ids), "method": method, "params": params}, timeout)
        trace = reply_trace.get()
        if trace is not None:
            trace.append({k: v for k, v in r.raw.items() if k != "data"} | {"method": method})
            if len(trace) > 64:
                del trace[0]
        return r

    def call(self, method: str, /, timeout: float | None = None, **params) -> Any:
        r = self.call_reply(method, timeout, **params)
        if not r.ok:
            raise BridgeError((r.error or {}).get("code", "?"), (r.error or {}).get("msg", ""), method, r.raw)
        return r.data

    def poll_events(self, max_wait: float = 0.0) -> list[dict]:
        with self._events_ready:
            if not self.events_buf and not self._closed and max_wait > 0:
                self._events_ready.wait_for(lambda: self.events_buf or self._closed, timeout=max_wait)
            out = list(self.events_buf)
            self.events_buf.clear()
            if self.events_dropped:
                out.insert(0, {"event": "transport.events_dropped", "data": {"count": self.events_dropped}})
                self.events_dropped = 0
            return out

    def events(self) -> Iterable[dict]:
        return self.poll_events()

    def subscribe(self, types: list[str] | None) -> str:
        return self.call("events.subscribe", types=types or [])

    # ---- conveniences ----

    def wait_ticks(self, n: int) -> dict:
        return self.call("act.wait_ticks", timeout=n * 0.05 + 30, n=n)

    def command(self, cmd: str) -> dict:
        return self.call("sys.command", cmd=cmd)

    def baritone(self, text: str) -> dict:
        return self.call("baritone.command", text=text)

    def player(self) -> dict:
        return self.call("obs.player")

    def world(self) -> dict:
        return self.call("obs.world")
