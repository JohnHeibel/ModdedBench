# Autonomous runner

Conditional script self-prompts are exposed in each request's `modelPrompts`
list, alongside the full pending interrupt events. See [layer ownership](LAYERS.md).

`tools/gtnh/autonomous_runner.py` is provider-neutral orchestration for an
external model or operator adapter, the durable interrupt journal, bridge calls
and the managed Java deployment lifecycle. It does not provide inference: no
model SDK or credential is bundled. Supply an editable
Python adapter:

```python
async def infer(request):
    # Call the provider here and parse its result into the response below.
    return {"calls": [{"method": "obs.player", "params": {}}]}

def cancel():
    # Optional: cancel an in-flight provider request.
    pass
```

For an operator-driven pilot without provider credentials, use the included file
adapter. The runner automatically points its mailbox at `--state`:

```powershell
python tools/gtnh/autonomous_runner.py `
  --adapter tools/gtnh/file_model_adapter.py --state C:\pilot\state `
  --watches survival-watches.json --objective "bounded natural survival pilot"
```

The runner atomically writes `model-request.json` with a fresh unguessable
`requestId`. Write a decision object to a temporary JSON file, then atomically
publish a matching response with:

```powershell
python tools/gtnh/file_model_adapter.py respond `
  --request C:\pilot\state\model-request.json --decision decision.json
```

Only a matching response is consumed. Interrupt/provider cancellation publishes
`model-cancelled.json` and invalidates the outstanding ID; a late response cannot
satisfy the next request. Message size is limited to 1 MiB, polling defaults to
100 ms, and the response deadline defaults to one hour. Override bounded values
with `MODBENCH_RUNNER_MODEL_POLL_S` and `MODBENCH_RUNNER_MODEL_TIMEOUT_S`.
Programmatic users can set `MODBENCH_RUNNER_MAILBOX` explicitly.

The adapter is compiled directly from source and reloaded when its content hash
changes, so same-size/same-timestamp edits cannot reuse stale bytecode. Each request contains the
objective, iteration, previous call results, pending interrupt events, any
uncertain recovered effect and budgets.
It returns a JSON object with `calls`, optional `complete`, and optionally:

```json
{"ack":["native-event-id"],"reviewed":true,"resume":true,"calls":[]}
```

An interrupt wins a race with inference, including a response completed in the
same event-loop slice. The obsolete response is discarded and `cancel()` is
called. A later accepted decision must acknowledge every latched triggering event
before resuming time or issuing actions. `reviewed:true` clears reviewed fault,
stall, uncertain-reaction and gap records only after every triggering ID was
acknowledged. Acknowledgement does not itself resume time.

`complete:true` means the objective was achieved and is rejected while an
interrupt or uncertain effect remains. To end a run for safety or an external
blocker while preserving its pause, latch, and unfinished objective, return a
nonempty `stopReason` instead. The checkpoint then has `complete:false` and the
reason, and the run loop ends without acknowledging or resuming anything.

Checkpoints are atomically replaced at `STATE/checkpoint.json`; `events.jsonl` is
an append-only fsync'd history. Restart with the same objective and state directory
to recover. Interrupt watches are process-local and are recreated after every
bridge reconnect. Their SQLite cursor remains in the checkpoint so old events are
not replayed. Call, deploy, wakeup and reconnect usage is also checkpointed, so a
process restart cannot reset a budget. A changed objective requires a new state
directory.

Native interrupt receipt IDs exist only for one client JVM. After reconnect, the
runner compares a pending receipt's bridge identity with `interrupt.status`.
It resolves an old-JVM ID locally only when `bridgeId` demonstrably changed and
the next decision explicitly includes both that ID in `ack` and `reviewed:true`.
An unknown ID in the same or unverifiable context remains an error. Any latch
reported by the current JVM is added to pending work and must receive its own
native acknowledgement. Each resolved acknowledgement and reviewed state is
checkpointed before resume.

Every bridge-call and deployment budget unit is reserved in the checkpoint before
dispatch. A lost response cannot reset a budget. If the runner dies between call
writes, recovery reports `uncertainEffect`; it will not issue another interaction
or resume until the model returns `reviewed:true`. Advertised read methods remain
available so the model can observe and reconcile the possible partial effect.
Resume follows the same rule: its dispatch is checkpointed as an uncertain
effect, and only a returned receipt clears it.

Run with a JSON object of normal `gtnh_interrupts` watch specifications:

```powershell
python tools/gtnh/autonomous_runner.py --adapter my_provider.py `
  --watches survival-watches.json --objective "bounded natural survival pilot" `
  --max-iterations 40 --max-calls 160
```

`--enable-deploy` permits at most `--max-deploys` lifecycle requests. A decision
uses `{"deploy":{"components":["control","baritone","client"]}}`. Components
are restricted to that allowlist. The runner calls the existing managed CLI to
stop the client, build, install and launch/join it. Any failure after installation
stops the replacement if possible, restores installed components in reverse order,
and relaunches the prior set. It then reconnects and rearms watches. Build failures
occur before component replacement, so no installed files need rollback; because
the managed flow has already stopped the client, it still relaunches the previous
installation before reconnecting. The server is never restarted.

The runner is deliberately bounded and does not grant a model filesystem access.
An external coding agent may edit source and the adapter may then request the
managed deploy operation. Mock tests establish orchestration contracts only; they
do not establish live provider inference or a Minecraft survival result.

## Relationship to Baritone and the DJ2 bridge

The runner does not replace Baritone's process scheduler. Original
`BuilderProcess` continuously recomputes mismatches, exhausts reachable work and
assembles composite work goals. The GTNH process retains the search core's
`GoalComposite` behavior, while its bounded approach generation, paging, exact
metadata checks and durable journal are new. The DJ2 bridge provides the design
precedent for bounded attempts, retained build IDs, pause/resume from a fresh
world diff, cleanup, and independent verification. Model calls can invoke the
GTNH equivalents directly (`baritone.build_preview`, `baritone.build`, and
`baritone.resume` with `jobId`) through the generic `calls` list.

Active Java execution stops on a client JVM restart, but the GTNH native work
journal preserves mining/build job IDs. Native resume re-observes the world before
continuing rather than replaying old placement or mining instructions. The
external runner still records every dispatch as an `uncertainEffect` before
sending it, because a process can die before receiving the returned durable job
ID. After recovery, the model first inspects native work status and the world,
reassociates a surviving journal when possible, and then explicitly resumes it.
This external recovery protocol is new GTNH runner behavior informed by DJ2's
within-JVM lifecycle. It does not imply that a DJ2 execution object or active Java
process survives a JVM restart.
