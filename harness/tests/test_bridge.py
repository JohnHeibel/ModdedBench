# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Offline regressions at transport, MCP stdio, and composition boundaries. No game required."""
from __future__ import annotations
import asyncio
import importlib.util
import json
import queue
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

MCP = Path(__file__).resolve().parents[1] / "mcp"
sys.path[:0] = [str(MCP)]
import kernel as transport
import mbtool
spec = importlib.util.spec_from_file_location("mb_test_server", MCP / "server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)

class Socket:
    def __init__(self):
        self.frames = queue.Queue()
        self.sent = []
        self.lock = threading.Lock()
        self.slow = None
        self.cancelled_requests = []
    def put(self, frame):
        self.frames.put(json.dumps(frame))
    def send(self, text):
        req = json.loads(text)
        with self.lock:
            self.sent.append(req)
        method = req.get("method", "auth")
        if method == "act.wait_ticks":
            self.slow = req
            threading.Timer(.3, lambda: self.reply(req, {"waited": 6})).start()
        elif method == "obs.player":
            self.put({"id": req["id"], "ok": False, "tick": 4, "seq": 7, "error": {"code": "not_in_world", "msg": "fixture"}})
        elif method == "requests.cancel":
            self.cancelled_requests.append(req["params"]["requestId"])
            self.put({"id": req["params"]["requestId"], "ok": False, "error": {"code": "cancelled", "msg": "fixture cancelled"}})
        elif method == "sys.ping":
            self.reply(req, {"cancelled_requests": self.cancelled_requests[:]})
        else:
            self.reply(req, {"method": method})
    def reply(self, req, data):
        self.put({"id": req["id"], "ok": True, "tick": 4, "seq": 7, "cost_ms": 1.25, "data": data})
    def recv(self):
        frame = self.frames.get(timeout=3)
        if frame is None:
            raise ConnectionError("closed")
        return frame
    def close(self):
        self.frames.put(None)

class TransportTests(unittest.TestCase):
    def setUp(self):
        self.socket = Socket()
        self.patch = patch.object(transport, "connect", return_value=self.socket)
        self.patch.start()
        self.k = transport.Kernel(token="", event_capacity=3)
    def tearDown(self):
        self.k.close()
        self.patch.stop()
    def test_concurrent_out_of_order_replies(self):
        with ThreadPoolExecutor(2) as pool:
            slow = pool.submit(self.k.call, "act.wait_ticks")
            while self.socket.slow is None:
                time.sleep(.001)
            fast = pool.submit(self.k.call_reply, "act.stop")
            self.assertEqual(fast.result(.2).data["method"], "act.stop")
            self.assertFalse(slow.done())
            self.assertEqual(slow.result(1), {"waited": 6})
    def test_method_is_a_valid_rpc_parameter(self):
        self.k.call("actions.start", method="act.input", params={"forward": True, "ticks": 10})
        req = self.socket.sent[-1]
        self.assertEqual(req["method"], "actions.start")
        self.assertEqual(req["params"]["method"], "act.input")

    def test_events_bounded_and_loss_reported(self):
        for i in range(8):
            self.socket.put({"event": "test", "seq": i})
        self.k.call("sys.ping")  # receiver has processed all earlier frames
        events = self.k.events()
        self.assertEqual(events[0]["data"]["count"], 5)
        self.assertEqual([e["seq"] for e in events[1:]], [5, 6, 7])
    def test_error_preserves_envelope(self):
        with self.assertRaises(transport.BridgeError) as ex:
            self.k.call("obs.player")
        self.assertEqual(ex.exception.reply["seq"], 7)
        self.assertEqual(ex.exception.code, "not_in_world")
    def test_disconnect_wakes_pending(self):
        with ThreadPoolExecutor(1) as pool:
            pending = pool.submit(self.k.call, "act.wait_ticks")
            while self.socket.slow is None:
                time.sleep(.001)
            self.k.close()
            with self.assertRaises(ConnectionError):
                pending.result(.2)
    def test_scope_cancels_server_request_and_future_compositions(self):
        scope = transport.CancellationScope()
        def call():
            token = transport.cancel_scope.set(scope)
            try:
                return self.k.call("act.wait_ticks")
            finally:
                transport.cancel_scope.reset(token)
        with ThreadPoolExecutor(1) as pool:
            pending = pool.submit(call)
            while self.socket.slow is None:
                time.sleep(.001)
            scope.cancel()
            with self.assertRaises(transport.BridgeError) as ex:
                pending.result(.2)
            self.assertEqual(ex.exception.code, "cancelled")
        token = transport.cancel_scope.set(scope)
        try:
            with self.assertRaises(transport.BridgeError):
                self.k.call("sys.ping")
        finally:
            transport.cancel_scope.reset(token)


class ReloadTests(unittest.TestCase):
    def test_reload_conflict_is_atomic(self):
        srv = server.Server()
        srv.check_reload(force=True)
        original = srv._tool_manager._tools["mb_act"]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "conflict.py"
            path.write_text('from mbtool import tool\n@tool(name="mb_act")\ndef steal() -> str:\n    return "wrong"\n')
            tm = server.ToolModule(str(path))
            result = srv._load(tm)
            self.assertTrue(result.startswith("ERROR"))
            self.assertIs(srv._tool_manager._tools["mb_act"], original)
            self.assertTrue(tm.error)

if __name__ == "__main__":
    if "--fixture-server" in sys.argv:
        transport.connect = lambda *a, **kw: Socket()
        srv = server.Server()
        mbtool.set_kernel_factory(lambda: transport.Kernel(token=""))
        srv.check_reload(force=True)
        srv.run(transport="stdio")
    else:
        unittest.main()
