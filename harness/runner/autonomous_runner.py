# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Provider-neutral, interrupt-aware GTNH autonomous runner.

Adapters are ordinary editable Python modules with an async ``infer(request)``
function. They may also define ``cancel()``.  The request and response JSON
contract is documented in ``docs/legacy/AUTONOMOUS_RUNNER.md``.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.util
import inspect
import json
import os
import subprocess
import sys
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable

REPO = Path(__file__).resolve().parents[2]
MCP = REPO / "harness" / "mcp"
if str(MCP) not in sys.path:
    sys.path.insert(0, str(MCP))
import mbtool  # noqa: E402,F401  (installs the mbtools_gtnh package)
from mbtools_gtnh.interrupts import InterruptSupervisor, race_interrupt  # noqa: E402
from kernel import Kernel, BridgeError  # noqa: E402

WAKE_KINDS = {"triggered", "reaction_error", "fault", "stalled"}


def _json_safe(value: Any) -> Any:
    json.dumps(value, allow_nan=False)
    return value


@dataclass
class RunnerState:
    run_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    objective: str = ""
    iteration: int = 0
    cursor: int = 0
    pending_interrupts: list[dict[str, Any]] = field(default_factory=list)
    last_result: Any = None
    complete: bool = False
    stop_reason: str | None = None
    deploys: int = 0
    calls: int = 0
    wakeups: int = 0
    reconnects: int = 0
    uncertain_effect: dict[str, Any] | None = None
    connection_generation: int = 0


class Journal:
    """Atomic checkpoint plus append-only, fsync'd diagnostic history."""
    def __init__(self, directory: Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.checkpoint = self.directory / "checkpoint.json"
        self.events = self.directory / "events.jsonl"

    def load(self) -> RunnerState | None:
        if not self.checkpoint.is_file():
            return None
        raw = json.loads(self.checkpoint.read_text(encoding="utf-8"))
        known = {name for name in RunnerState.__dataclass_fields__}
        return RunnerState(**{k: v for k, v in raw.items() if k in known})

    def save(self, state: RunnerState) -> None:
        data = json.dumps(asdict(state), indent=2, sort_keys=True, allow_nan=False) + "\n"
        temporary = self.checkpoint.with_suffix(f".{os.getpid()}.tmp")
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(data); handle.flush(); os.fsync(handle.fileno())
        os.replace(temporary, self.checkpoint)

    def append(self, kind: str, **data: Any) -> None:
        record = {"ts": time.time(), "kind": kind, **_json_safe(data)}
        with self.events.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, separators=(",", ":"), allow_nan=False) + "\n")
            handle.flush(); os.fsync(handle.fileno())


class ModuleAdapter:
    def __init__(self, path: Path):
        self.path = Path(path).resolve()
        self._module = None
        self._digest = None

    def _load(self):
        source = self.path.read_bytes()
        digest = hashlib.sha256(source).hexdigest()
        if digest == self._digest:
            return self._module
        name = "gtnh_runner_adapter_" + digest[:16]
        spec = importlib.util.spec_from_loader(name, loader=None, origin=str(self.path))
        module = importlib.util.module_from_spec(spec)
        module.__file__ = str(self.path)
        exec(compile(source, str(self.path), "exec"), module.__dict__)
        if not inspect.iscoroutinefunction(getattr(module, "infer", None)):
            raise TypeError("adapter must define async infer(request)")
        self._module, self._digest = module, digest
        return module

    async def infer(self, request: dict[str, Any]) -> dict[str, Any]:
        result = await self._load().infer(request)
        if not isinstance(result, dict):
            raise TypeError("adapter infer must return a JSON object")
        return _json_safe(result)

    def cancel(self) -> None:
        module = self._module
        fn = getattr(module, "cancel", None) if module else None
        if fn:
            fn()


class ManagedDeploy:
    """Bounded facade over the existing managed lifecycle CLI."""
    COMPONENTS = ("core", "baritone", "client")   # core is the coremod on both sides, so deploying it also restarts the server
    def __init__(self, runtime_dir: Path, command: Callable[[list[str]], None] | None = None,
                 launch_timeout: float = 300):
        self.runtime_dir = Path(runtime_dir).resolve()
        self.launch_timeout = launch_timeout
        self.command = command or self._command

    def _command(self, args: list[str]) -> None:
        cmd = [sys.executable, str(REPO / "harness" / "launcher" / "runtime.py"),
               "--runtime", str(self.runtime_dir), *args]
        result = subprocess.run(cmd, cwd=REPO, check=False)
        if result.returncode:
            raise RuntimeError(f"managed runtime command failed ({result.returncode}): {' '.join(args)}")

    def deploy(self, components: list[str]) -> dict[str, Any]:
        if (not isinstance(components, list) or not components or
                any(x not in self.COMPONENTS for x in components) or len(set(components)) != len(components)):
            raise ValueError("deploy components must be a unique nonempty subset of core, baritone, client")
        ordered = [x for x in self.COMPONENTS if x in components]
        server = "core" in ordered
        installed: list[str] = []
        self.command(["stop-client"])
        if server: self.command(["stop-server"])
        try:
            self.command(["build"])
            for component in ordered:
                self.command([f"install-{component}"]); installed.append(component)
            if server: self.command(["start-server"])
            self.command(["launch-client", "--timeout", str(self.launch_timeout)])
            return {"deployed": ordered, "rolledBack": []}
        except BaseException as failure:
            rollback_errors = []
            if installed:
                for stop in (["stop-client"], *([["stop-server"]] if server else [])):
                    try: self.command(stop)
                    except Exception: pass
            for component in reversed(installed):
                try: self.command([f"rollback-{component}"])
                except Exception as exc: rollback_errors.append(f"{component}: {exc}")
            if server:
                try: self.command(["start-server"])
                except Exception as exc: rollback_errors.append(f"start-server: {exc}")
            try: self.command(["launch-client", "--timeout", str(self.launch_timeout)])
            except Exception as exc: rollback_errors.append(f"relaunch: {exc}")
            if rollback_errors:
                detail = f"deploy failed: {failure}; rollback errors: {rollback_errors}"
            elif installed:
                detail = f"deploy failed and prior jars were restored: {failure}"
            else:
                detail = f"deploy failed before jar replacement: {failure}"
            raise RuntimeError(detail) from failure


class AutonomousRunner:
    def __init__(self, adapter: Any, journal: Journal, objective: str, watch_specs: dict[str, Any],
                 *, kernel_factory: Callable[[], Any] | None = None,
                 supervisor_factory: Callable[[Any], Any] | None = None,
                 deployer: ManagedDeploy | None = None, max_iterations: int = 50,
                 max_calls: int = 200, max_deploys: int = 1, max_wakeups: int = 100,
                 max_reconnects: int = 20, reconnect_delay: float = 2):
        self.adapter, self.journal, self.watch_specs = adapter, journal, watch_specs
        self.kernel_factory = kernel_factory or (lambda: Kernel(connect_retries=3, retry_delay=1))
        self.supervisor_factory = supervisor_factory or (lambda k: InterruptSupervisor(k, path=self.journal.directory / 'interrupts'))
        self.deployer = deployer
        self.max_iterations, self.max_calls, self.max_deploys = max_iterations, max_calls, max_deploys
        self.max_wakeups, self.max_reconnects = max_wakeups, max_reconnects
        self.reconnect_delay = reconnect_delay
        self._method_effects = None
        recovered = journal.load()
        if recovered:
            if objective and recovered.objective != objective:
                raise ValueError("checkpoint objective differs; use a new state directory")
            self.state = recovered
        else:
            self.state = RunnerState(objective=objective); journal.save(self.state)

    def _connect(self):
        self._method_effects = None
        kernel = self.kernel_factory()
        supervisor = self.supervisor_factory(kernel)
        for name, spec in self.watch_specs.items():
            supervisor.add(name, spec)
        self.state.connection_generation += 1
        # Supervisor cursors belong to its durable journal; retain the cursor,
        # including across bridge reconnects, while watches are always rearmed.
        self.journal.append("connected", generation=self.state.connection_generation,
                            watches=sorted(self.watch_specs))
        self.journal.save(self.state)
        return kernel, supervisor

    async def _decision(self, supervisor) -> dict[str, Any]:
        request = {"objective": self.state.objective, "iteration": self.state.iteration,
                   "lastResult": self.state.last_result,
                   "pendingInterrupts": self.state.pending_interrupts,
                   "modelPrompts": [
                       {"eventId": event.get("data", {}).get("eventId"),
                        "watch": event.get("name", ""),
                        "prompt": event["data"]["payload"]["modelPrompt"],
                        "observations": {key: value for key, value in event["data"]["payload"].items()
                                         if key != "modelPrompt"}}
                       for event in self.state.pending_interrupts
                       if event.get("kind") in ("triggered", "reaction_error")
                       and isinstance(event.get("data", {}).get("payload", {}).get("modelPrompt"), str)],
                   "uncertainEffect": self.state.uncertain_effect,
                   "limits": {"iterations": self.max_iterations, "calls": self.max_calls,
                              "deploys": self.max_deploys}}
        raced = await race_interrupt(supervisor, self.adapter.infer(request), self.state.cursor,
                                     cancel=getattr(self.adapter, "cancel", None))
        self.state.cursor = raced["cursor"]
        if raced["interrupted"]:
            self.state.wakeups += 1
            self.state.pending_interrupts.extend(raced.get("events", []))
            self.state.last_result = {"interrupted": True, "gap": raced.get("gap", False)}
            self.journal.append("model_discarded", cursor=self.state.cursor,
                                events=raced.get("events", []), gap=raced.get("gap", False))
            self.journal.save(self.state)
            if self.state.wakeups > self.max_wakeups:
                raise RuntimeError("interrupt wakeup budget exhausted")
            return {}
        decision = raced["result"]
        self.journal.append("decision", iteration=self.state.iteration, decision=decision)
        return decision

    def _apply(self, kernel, decision: dict[str, Any]) -> None:
        stop_reason = decision.get("stopReason")
        if stop_reason is not None:
            if not isinstance(stop_reason, str) or not stop_reason.strip() or len(stop_reason) > 500:
                raise ValueError("stopReason must be a nonempty string of at most 500 characters")
            if (decision.get("complete") or decision.get("resume") or decision.get("reviewed")
                    or decision.get("ack") or decision.get("calls") or "deploy" in decision):
                raise ValueError("stopReason must be a standalone safety-termination decision")
            self.state.stop_reason = stop_reason.strip()
            self.state.complete = False
            self.journal.append("stopped", reason=self.state.stop_reason)
            self.journal.save(self.state)
            return
        pending_ids = {e.get("data", {}).get("eventId") for e in self.state.pending_interrupts
                       if e.get("kind") == "triggered"}
        pending_ids.discard(None)
        ack = decision.get("ack", [])
        if not isinstance(ack, list) or any(x not in pending_ids for x in ack):
            raise ValueError("ack must contain only pending triggering event IDs")
        if len(set(ack)) != len(ack): raise ValueError("ack event IDs must be unique")
        if ack:
            status = kernel.call("interrupt.status", limit=32)
            current_context = status.get("context", {}) if isinstance(status, dict) else {}
            current_latches = status.get("latched", []) if isinstance(status, dict) else []
            receipts = status.get("receipts", []) if isinstance(status, dict) else []
            if not isinstance(current_latches, list) or not isinstance(receipts, list):
                raise ValueError("interrupt.status returned invalid latch metadata")
            receipt_by_id = {r.get("eventId"):r for r in receipts if isinstance(r, dict)}
            known_pending = {e.get("data", {}).get("eventId") for e in self.state.pending_interrupts}
            for event_id in current_latches:
                if event_id not in known_pending:
                    receipt = receipt_by_id.get(event_id, {"eventId":event_id,"context":current_context,"latched":True})
                    self.state.pending_interrupts.append({"kind":"triggered","name":"recovered_current_latch",
                                                          "data":{"eventId":event_id,"receipt":receipt}})
            for event_id in ack:
                event = next(e for e in self.state.pending_interrupts
                             if e.get("data", {}).get("eventId") == event_id)
                receipt = event.get("data", {}).get("receipt", {})
                old_context = receipt.get("context", {}) if isinstance(receipt, dict) else {}
                # Receipt storage is JVM-local. Only a verified bridge identity
                # change proves that the old native receipt cannot still exist;
                # a dimension/world-epoch change in the same JVM does not.
                context_changed = (isinstance(old_context, dict) and isinstance(current_context, dict)
                                   and isinstance(old_context.get("bridgeId"), str)
                                   and isinstance(current_context.get("bridgeId"), str)
                                   and old_context["bridgeId"] != current_context["bridgeId"])
                if context_changed:
                    if not decision.get("reviewed"):
                        raise ValueError("stale prior-JVM interrupt requires explicit ack and reviewed:true")
                    resolution = "stale_after_verified_context_change"
                else:
                    # Same/unknown context must be acknowledged by the native JVM.
                    # Never reinterpret an arbitrary unknown-event error as stale.
                    kernel.call("interrupt.ack", eventId=event_id)
                    resolution = "native_ack"
                self.state.pending_interrupts = [e for e in self.state.pending_interrupts
                    if e.get("data", {}).get("eventId") != event_id]
                self.journal.append("interrupt_resolved", eventId=event_id, resolution=resolution,
                                    currentContext=current_context)
                self.journal.save(self.state)
        if decision.get("reviewed"):
            remaining_ids = {e.get("data", {}).get("eventId") for e in self.state.pending_interrupts
                             if e.get("kind") == "triggered"}
            remaining_ids.discard(None)
            if remaining_ids:
                raise ValueError("all triggering event IDs must be acknowledged before reviewed")
            self.state.pending_interrupts = []
            self.state.uncertain_effect = None
            self.journal.append("reviewed", iteration=self.state.iteration)
            self.journal.save(self.state)
        if decision.get("resume"):
            if self.state.pending_interrupts or self.state.uncertain_effect:
                raise ValueError("all pending interrupts and uncertain effects must be reviewed before resume")
            self.state.uncertain_effect = {"method":"time.resume","params":{}}
            self.journal.append("resume_started")
            self.journal.save(self.state)
            result = kernel.call("time.resume")
            self.state.uncertain_effect = None
            self.journal.append("resume_finished", result=result)
            self.journal.save(self.state)
        calls = decision.get("calls", [])
        if not isinstance(calls, list): raise ValueError("calls must be a list")
        if (self.state.pending_interrupts or self.state.uncertain_effect) and calls:
            if self._method_effects is None:
                advertised = kernel.call("sys.methods")
                advertised = advertised.get("methods", advertised) if isinstance(advertised, dict) else advertised
                if isinstance(advertised, list):
                    self._method_effects = {row.get("name"): row.get("effect") for row in advertised if isinstance(row, dict)}
                elif isinstance(advertised, dict):
                    self._method_effects = {name: meta.get("effect") for name, meta in advertised.items() if isinstance(meta, dict)}
                else: raise ValueError("sys.methods did not return advertised method metadata")
            if any(self._method_effects.get(call.get("method")) != "read" for call in calls if isinstance(call, dict)):
                raise ValueError("only advertised read calls are allowed before interrupt/effect review")
        if self.state.calls + len(calls) > self.max_calls: raise RuntimeError("call budget exhausted")
        results = []
        for call in calls:
            if not isinstance(call, dict) or not isinstance(call.get("method"), str) or not isinstance(call.get("params", {}), dict):
                raise ValueError("each call requires method and object params")
            previous_uncertain = self.state.uncertain_effect
            self.state.uncertain_effect = previous_uncertain or {"method": call["method"], "params": call.get("params", {})}
            self.state.calls += 1
            self.journal.append("call_started", call=self.state.uncertain_effect)
            self.journal.save(self.state)
            failed = False
            try:
                result = kernel.call(call["method"], **call.get("params", {}))
            except BridgeError as error:
                failed = True
                result = {"ok":False,"method":call["method"],"error":error.code,"message":error.msg,"reply":error.reply}
                if error.code == 'timeout': previous_uncertain = self.state.uncertain_effect
            results.append(result)
            self.state.uncertain_effect = previous_uncertain
            self.state.last_result = results
            self.journal.append("call_finished", method=call["method"], result=result)
            self.journal.save(self.state)
            if failed:
                # A failed prerequisite ends this batch. The model can inspect
                # its structured native receipt before deciding how to recover.
                return
        if "deploy" in decision:
            if not self.deployer: raise RuntimeError("deployment is disabled")
            if self.state.deploys >= self.max_deploys: raise RuntimeError("deploy budget exhausted")
            self.state.deploys += 1
            self.state.uncertain_effect = {"deploy":decision["deploy"]}
            self.journal.append("deploy_started", deploy=decision["deploy"], deploys=self.state.deploys)
            self.journal.save(self.state)
            try:
                result = self.deployer.deploy(decision["deploy"].get("components"))
                self.state.uncertain_effect = None
            except RuntimeError as error:
                result = {"ok":False,"error":str(error),"reviewRequired":True}
            results.append({"deploy": result})
            # The old bridge and process-local watches are gone; force reconnect.
            self.state.last_result = results
            self.state.iteration += 1
            self.journal.save(self.state)
            raise ConnectionError("managed deployment completed; reconnect required")
        self.state.last_result = results
        complete = bool(decision.get("complete", False))
        if complete and (self.state.pending_interrupts or self.state.uncertain_effect):
            raise ValueError("objective completion requires all pending interrupts and uncertain effects reviewed")
        self.state.complete = complete

    async def run(self) -> RunnerState:
        if self.state.complete: return self.state
        kernel = supervisor = None
        try:
            while self.state.iteration < self.max_iterations and not self.state.complete and not self.state.stop_reason:
                if kernel is None:
                    try: kernel, supervisor = self._connect()
                    except Exception as exc:
                        self.state.reconnects += 1; self.journal.save(self.state)
                        self.journal.append("connect_failed", error=str(exc), reconnects=self.state.reconnects)
                        if self.state.reconnects > self.max_reconnects: raise RuntimeError("reconnect budget exhausted") from exc
                        await asyncio.sleep(self.reconnect_delay); continue
                try:
                    decision = await self._decision(supervisor)
                    if not decision: continue
                    self._apply(kernel, decision)
                    self.state.iteration += 1; self.journal.save(self.state)
                except (ConnectionError, TimeoutError, OSError) as exc:
                    self.state.reconnects += 1; self.journal.save(self.state)
                    self.journal.append("disconnected", error=str(exc))
                    if self.state.reconnects > self.max_reconnects:
                        raise RuntimeError("reconnect budget exhausted") from exc
                    try: supervisor.close()
                    except Exception: pass
                    try: kernel.close()
                    except Exception: pass
                    kernel = supervisor = None
            if not self.state.complete and not self.state.stop_reason and self.state.iteration >= self.max_iterations:
                raise RuntimeError("iteration budget exhausted")
            return self.state
        finally:
            if supervisor:
                try: supervisor.close()
                except Exception: pass
            if kernel:
                try: kernel.close()
                except Exception: pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adapter", required=True, type=Path)
    parser.add_argument("--objective", required=True)
    parser.add_argument("--watches", required=True, type=Path, help="JSON object mapping watch names to specs")
    parser.add_argument("--state", type=Path, default=REPO / ".state" / "runner")
    parser.add_argument("--runtime", type=Path, default=REPO / ".runtime")
    parser.add_argument("--max-iterations", type=int, default=50)
    parser.add_argument("--max-calls", type=int, default=200)
    parser.add_argument("--max-deploys", type=int, default=1)
    parser.add_argument("--max-wakeups", type=int, default=100)
    parser.add_argument("--max-reconnects", type=int, default=20)
    parser.add_argument("--enable-deploy", action="store_true")
    args = parser.parse_args(argv)
    if min(args.max_iterations, args.max_calls, args.max_wakeups, args.max_reconnects) < 1 or args.max_deploys < 0:
        parser.error("budgets must be positive (max-deploys may be zero)")
    watches = json.loads(args.watches.read_text(encoding="utf-8"))
    # The bundled file adapter uses this automatically; provider adapters may
    # ignore it. An explicit environment override remains available for split volumes.
    os.environ.setdefault("MODBENCH_RUNNER_MAILBOX", str(args.state.resolve()))
    deployer = ManagedDeploy(args.runtime) if args.enable_deploy else None
    runner = AutonomousRunner(ModuleAdapter(args.adapter), Journal(args.state), args.objective, watches,
                              deployer=deployer, max_iterations=args.max_iterations,
                              max_calls=args.max_calls, max_deploys=args.max_deploys,
                              max_wakeups=args.max_wakeups, max_reconnects=args.max_reconnects)
    try:
        state = asyncio.run(runner.run())
        print(json.dumps(asdict(state), indent=2)); return 0
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print(f"runner error: {exc}", file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
