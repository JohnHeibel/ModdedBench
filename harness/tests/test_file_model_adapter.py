# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "runner"))
import file_model_adapter as adapter


class FileAdapterTests(unittest.TestCase):
    async def request(self, directory):
        path = Path(directory) / "model-request.json"
        task = asyncio.create_task(adapter.infer({"objective":"survive"}))
        await asyncio.sleep(0)
        for _ in range(100):
            if path.is_file(): return task, json.loads(path.read_text())
            await asyncio.sleep(.005)
        self.fail("request was not published")

    def test_only_matching_atomic_response_is_consumed(self):
        async def exercise(directory):
            task, request = await self.request(directory)
            adapter._atomic_json(Path(directory) / "model-response.json",
                                 {"requestId":"wrong", "decision":{"complete":False}})
            await asyncio.sleep(.04); self.assertFalse(task.done())
            decision_file = Path(directory) / "decision.json"
            adapter._atomic_json(decision_file, {"calls":[], "complete":True})
            adapter.respond(Path(directory) / "model-request.json", decision_file)
            return request, await task
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                "MODBENCH_RUNNER_MAILBOX":directory, "MODBENCH_RUNNER_MODEL_POLL_S":"0.01"}):
            request, result = asyncio.run(exercise(directory))
            self.assertGreaterEqual(len(request["requestId"]), 40)
            self.assertEqual(result, {"calls":[], "complete":True})
            self.assertFalse((Path(directory) / "model-response.json").exists())

    def test_cancel_invalidates_late_response_and_next_request_has_fresh_id(self):
        async def exercise(directory):
            first_task, first = await self.request(directory)
            adapter.cancel()
            with self.assertRaises(asyncio.CancelledError): await first_task
            adapter._atomic_json(Path(directory) / "model-response.json",
                                 {"requestId":first["requestId"], "decision":{"complete":True}})
            second_task, second = await self.request(directory)
            await asyncio.sleep(.04); self.assertFalse(second_task.done())
            adapter._atomic_json(Path(directory) / "model-response.json",
                                 {"requestId":second["requestId"], "decision":{"complete":False}})
            return first, second, await second_task
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                "MODBENCH_RUNNER_MAILBOX":directory, "MODBENCH_RUNNER_MODEL_POLL_S":"0.01"}):
            first, second, result = asyncio.run(exercise(directory))
            self.assertNotEqual(first["requestId"], second["requestId"])
            cancelled = json.loads((Path(directory) / "model-cancelled.json").read_text())
            self.assertEqual(cancelled["requestId"], first["requestId"])
            self.assertEqual(result, {"complete":False})

    def test_timeout_is_bounded_and_journalled(self):
        async def exercise():
            with self.assertRaises(TimeoutError): await adapter.infer({})
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                "MODBENCH_RUNNER_MAILBOX":directory, "MODBENCH_RUNNER_MODEL_POLL_S":"0.01",
                "MODBENCH_RUNNER_MODEL_TIMEOUT_S":"1"}):
            asyncio.run(exercise())
            cancelled = json.loads((Path(directory) / "model-cancelled.json").read_text())
            self.assertEqual(cancelled["reason"], "timeout")


if __name__ == "__main__": unittest.main()
