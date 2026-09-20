# Autonomous runner

`harness/runner/autonomous_runner.py` is a provider-neutral loop for running a
model without an interactive MCP host. It owns the bridge connection, the
interrupt watches, budgets, a crash-safe checkpoint and (optionally) the
managed Java deploy cycle. It does not do inference: no model SDK or
credential is bundled. You supply an adapter.

The tests in `harness/tests` mock the bridge and cover orchestration only. No
live provider-backed run has been completed.

## Adapter

An adapter is an ordinary Python file, reloaded when its content hash changes:

```python
async def infer(request):
    # Call the provider here and turn its answer into a decision.
    return {"calls": [{"method": "obs.player", "params": {}}]}

def cancel():      # optional: abort an in-flight provider request
    pass
```

```bash
python harness/runner/autonomous_runner.py --adapter my_provider.py --watches survival-watches.json --objective "bounded survival pilot" --max-iterations 40 --max-calls 160
```

`--watches` is a JSON object of watch specs in the `mb_interrupt` format (see
[TOOLS.md](TOOLS.md)). Other options: `--state` (default `.state/runner`),
`--runtime`, `--max-deploys` (1), `--max-wakeups` (100), `--max-reconnects`
(20), `--enable-deploy`.

## Request and decision

Each `request` contains `objective`, `iteration`, `lastResult`,
`pendingInterrupts`, `modelPrompts` (the prompt, watch name, event id and
observations of every pending watch that asked for a model decision),
`uncertainEffect` and `limits`.

A decision is a JSON object:

| Field | Meaning |
| --- | --- |
| `calls` | List of `{method, params}` raw bridge calls, run in order. A failed call ends the batch and its error is returned in `lastResult`; a bridge `timeout` stays an uncertain effect. |
| `ack` | Event ids of pending triggering interrupts to acknowledge (`interrupt.ack`). Acknowledging never resumes time. |
| `reviewed` | `true` clears reviewed faults, stalls, gaps and the uncertain effect. Rejected until every triggering id has been acknowledged. |
| `resume` | `true` calls `time.resume`. Rejected while any interrupt or uncertain effect is pending. |
| `complete` | `true` means the objective is achieved. Rejected while an interrupt or uncertain effect remains. |
| `stopReason` | Nonempty string (at most 500 characters), on its own: end the run for safety or an external blocker, keeping the pause, latches and unfinished objective. |
| `deploy` | `{"components": [...]}`, see below. |

While an interrupt or uncertain effect is pending, only methods the bridge
advertises as reads are accepted in `calls`, so the model can observe and
reconcile before acting.

## Interrupts

An interrupt wins a race with inference, including a response that completes
in the same event-loop slice: the response is discarded, `cancel()` is called,
and the next request carries the triggering events. Faults, stalled
predicates, uncertain reactions and journal gaps also wake the runner.

Native interrupt receipts live in one client JVM. After a reconnect the runner
compares the receipt's `bridgeId` with `interrupt.status`. An id from an old
JVM is resolved locally only when the bridge id demonstrably changed and the
decision includes both that id in `ack` and `reviewed: true`. Latches reported
by the current JVM are added to the pending list and need their own
acknowledgement.

## Durability

`STATE/checkpoint.json` is replaced atomically; `STATE/events.jsonl` is an
append-only, fsynced history. Restart with the same objective and state
directory to recover; a different objective needs a new directory. Call,
deploy, wakeup and reconnect usage is checkpointed, so a restart cannot reset
a budget. Watches are re-armed after every reconnect and the interrupt journal
cursor is kept, so old events are not replayed.

Every bridge call, resume and deploy is recorded in the checkpoint as an
`uncertainEffect` before dispatch, and cleared only by the returned receipt.
If the runner dies in between, recovery reports the uncertain effect and
refuses further interactions until the model returns `reviewed: true`. For
long work this is the expected path: inspect `nav.work_status` and the world,
find the surviving `jobId`, then `nav.resume` it.

## Coding agents (Codex, Claude Code)

A chat-style coding agent talks to the MCP server directly and needs no
adapter, but MCP cannot push: once its turn ends, nothing wakes it. Two pieces
cover that. Inside a turn the agent calls `mb_wait(after, timeout_s)` instead
of finishing; it blocks until a watch triggers, faults, stalls or loses its
context, and returns the waking events and a cursor. The host's MCP tool
timeout must exceed `timeout_s` (Codex: `tool_timeout_sec` under
`[mcp_servers.<name>]`, default 60). When a turn ends anyway,
`harness/runner/codex_loop.py` starts the next one:

```bash
python harness/runner/codex_loop.py --max-turns 50 -- -m <model> -s workspace-write
```

The first turn is `codex exec --json` with `PROMPT.md` on stdin; the thread id
from the `thread.started` event is saved in `.state/codex-loop.json` and every
later turn is `codex exec resume <id>` with a short continue prompt. The loop
ends on a `MISSION COMPLETE` line in a turn's last message, on
`.state/STOP`, after `--max-turns`, or after three failed turns in a row (30 s
back-off). Output is appended to `.state/codex-loop.log`. Claude Code needs
only `mb_wait`; the same loop shape works with `claude -p` and `--resume`.
The autonomous runner above remains the provider-neutral path for API models.

## File adapter (operator in the loop)

`harness/runner/file_model_adapter.py` needs no provider. The runner points its
mailbox at `--state` (`MODBENCH_RUNNER_MAILBOX`) and atomically writes
`model-request.json` with a fresh `requestId`. Answer with:

```bash
python harness/runner/file_model_adapter.py respond --request <state>/model-request.json --decision decision.json
```

Only a response with the matching id is consumed. Cancellation publishes
`model-cancelled.json` and invalidates the id, so a late response cannot
satisfy the next request. Messages are limited to 1 MiB; polling is 100 ms and
the response deadline one hour (`MODBENCH_RUNNER_MODEL_POLL_S`,
`MODBENCH_RUNNER_MODEL_TIMEOUT_S`).

## Managed deploy

With `--enable-deploy`, a decision may request
`{"deploy": {"components": ["core", "baritone", "client"]}}` (any unique
subset), at most `--max-deploys` times. The runner drives
`harness/launcher/runtime.py`: stop the client (and the server when `core` is
included, because the core jar is installed on both sides), build, install,
start, `launch-client`, then reconnect and re-arm watches. A build failure
happens before any jar is replaced. A failure after installation rolls the
installed components back in reverse order and relaunches the previous set.

The runner does not give the model filesystem access. An external coding
agent edits the source; the adapter then requests the deploy.

`codex_mcp.mjs` in the same directory is a separate helper: a persistent stdio
connection to the MCP server for a host that cannot register it.
