# Native mod, Python, and model responsibilities

> **Changed in the Python-seam phase (2026-09-19).** `harness/mcp/` is now only `server.py`,
> `kernel.py` and `mbtool.py` (restart to change these). Everything the model edits lives in
> `harness/tools/` as flat domain modules (`core`, `inventory`, `work`, `recipes_quests`,
> `interrupts`, `notes`), importable as `mbtools_gtnh.<name>`; any change under `harness/tools/`
> re-imports the whole package atomically before the next tool call, and live objects (kernel,
> interrupt supervisor, notes stores) survive in `mbtool.state`. Tools declare their worker lane
> in metadata (`@tool(lane="read"|"act"|"control")`, or a callable per call); the server has no
> name lists. The bridge URL comes from one place, `kernel.bridge_url()` (`MB_BRIDGE_URL`).
> Schematic parsing and region copying moved to Java (`baritone.schematic_import`, `baritone.copy`).

Updated 2026-09-18. This is the ownership boundary for GTNH development.
Use the existing native primitives before adding Python infrastructure.

| Responsibility | Owner | Existing surface |
| --- | --- | --- |
| Game state, native callbacks, input leases, targeting, cancellation, protection | Java client/control mods | `obs.*`, `act.*`, `gui.*`, `inv.*` |
| Authoritative machine state, NBT, fluid/energy observations | Java server/client bridge | `obs.tile`, `obs.nbt`, `obs.waila` |
| Pathfinding, mining, construction, scaffolding, tool selection, work journals | Java Baritone mod | `baritone.*` |
| Simulation pause, health/air/food/fire guards, disconnect handling | Java server clock | `time.*` |
| Recipes and quest state/actions | Java adapters to native NEI/Better Questing | `nei.*`, quest methods |
| Transport, tool registration and small editable compositions | Python | MCP profile, Kernel, `ContainerSession` |
| Arbitrary observation predicates and requests for a new model decision | Python supervisor | `mb_interrupt`, `mb_interrupt_events` |
| Inference, selecting goals/recipes, interpreting unusual machines, deciding recovery | Model and its host | MCP host or external runner adapter |

Python should not duplicate path search, physics, native recipe semantics,
inventory acknowledgements, or block placement. Production recipes, retreat
choices, and machine-specific procedures are model-authored task code. Keep
shared helpers small and generic; a fixed progression planner is not required.
Test fixtures under `tools/gtnh/*smoke.py` are acceptance harnesses, not gameplay
primitives. Their supplied materials/energy do not demonstrate resource gathering.

## Conditional self-prompts

A script can leave its next action undecided and request model judgment when
an observed condition becomes true. No new model SDK or gameplay scheduler is
needed. In a custom watch file:

```python
def evaluate(context):
    player = context.values["player"]
    clock = context.read("time.status")
    if player["health"] < 8 or player["food"] <= 6 or player["burning"] or clock["paused"]:
        return context.prompt(
            "Inspect the current danger and unfinished work. Decide how to recover, "
            "then continue the objective if feasible.", player=player, clock=clock)
    return False
```

Arm through `mb_interrupt("add", name="survival", spec=...)`, with the absolute
watch `file`, `queries={"player":{"method":"obs.player"}}`,
`effects=["notify","cancel","pause"]`, and default `oneShot:true`.
Arm while ready to run: the example intentionally fires on an already-paused clock.
A declarative watch can instead include `prompt:"Decide what to do next..."`.
Custom code can also return `{match:true, prompt:..., payload:{...}}` directly.
Prompt text is limited to 8,192 characters; the complete trigger payload is
limited to 64 KiB. Payload observations remain data, not authority to execute code.

The supervisor journals `payload.modelPrompt` and performs only the configured
native notify/cancel/pause effects. The external runner interrupts obsolete
inference and supplies `modelPrompts` on its next inference request, including
event IDs, watch names and observations. Those requests survive checkpoint
recovery. Uncertain native reactions retain the prompt and require reconciliation.
The model chooses its normal next calls; no actions are predetermined by the prompt.

This is an asynchronous yield to the model, not a synchronous Python function
that returns model output. A plain MCP connection cannot wake the host's model
by itself: the host must consume `mb_interrupt_events`, or use the external
runner/adapter. Without such a consumer, the event is retained and native
effects still occur, but no new model turn is promised. This implementation
does not schedule desktop tasks or send messages to another conversation.

Acknowledge native latch IDs, review uncertain effects, and explicitly resume
time before further game actions. Rearm a one-shot watch after recovery.
Resume does not replay a cancelled operation; inspect its durable work ID and
fresh world/inventory state first. Notify-only prompts still wake the external
runner, but do not stop gameplay or latch input.

## 1. Survival and recovery

`time.configure` now adds opt-in `foodBelow` (-1 disables; otherwise 0..20,
inclusive) and `burning` (boolean). These run at the native server tick boundary,
alongside health and air guards, without Python polling or model inference.
Example configuration:

```json
{"healthDrop":true,"healthBelow":8,"airBelow":60,"foodBelow":6,"burning":true,"pauseOnDisconnect":true}
```

Guards pause; they do not select food, fight, respawn, or choose a retreat route.
Use the self-prompt above to request those decisions. A persistent condition
would immediately pause again on resume: after reviewing the situation,
temporarily disable only that condition for a bounded corrective action, keep
the other guards, verify the result, and restore its previous configuration.
Use native `act.eat`, inventory selection, navigation and GUI respawn primitives.
Native pauses alone do not cancel jobs or create interrupt admission latches;
the watch's cancel/pause effects supply that handoff when requested.

Still required: live hostile encounter -> recovery -> continuation, including
death/item recovery if applicable. Offline guard tests are not survival evidence.

## 2. Production and logistics

Compose native NEI inspection, guarded exact transfers/clicks, native machine
interaction, and authoritative output observations. `ContainerSession` already
provides bounded postcondition polling; work journals already preserve native
mining/building intent. Do not add a second Python inventory or crafting engine.

At an uncertain branch, use a short watch and self-prompt. For example, a
model-authored production watch can read `inv.find` with an exact observed
output selector, compare the count with its target, and return
`context.prompt("Output target reached; verify the machine and choose the next task.", count=count)`.
An alternative predicate can detect missing inputs, lack of progress, or a full
output inventory and ask the model to diagnose it. Watch read methods must be
advertised as reads; use `context.read` for methods not supported by `obs.batch`.
State a progress deadline explicitly; a stalled machine must not poll forever.

Preserve NBT identities, retained tools and container remainders. After an
uncertain click or transfer, re-observe and reconcile before retrying. Completion
comes from the task's output/quest/machine postcondition, not a successful click.
This change supplies a generic decision handoff, not an automatic recipe planner
or a claim that a new production chain has passed live acceptance.

## 3. Terrain and mining recovery

Native mining's bounded `initialTargetDiagnostics` now includes
`breakSafetyBlocked` from the actual source `MovementHelper.avoidBreaking`
predicate and the observed `above` block state, alongside tool eligibility and
mining cost. These are initial observations, capped at 16 targets; they are not
a fresh or exhaustive explanation of every later path failure.

A rejected target can therefore be distinguished from a missing harvest tool.
The model can inspect native `baritone.fluid`, choose another target, or plan
normal interactions to alter access, then explicitly resume the work. The new
regression preserves rejection of flowing water immediately above obsidian;
it does not relax the inherited fluid safety rule to force a successful mine.

Remaining live work: flowing-water traversal, deep snow, generic fluid-container
fall recovery, and ordinary-door builder support. Native door interaction remains
available. These are not closed by improved diagnostics or passing dry fixtures.

## Deployment and evidence

Java changes require a normal matching build/install/restart. Python watch/tool
files can reload through the existing mechanisms; edits to shared Python helpers
require restarting their MCP/runner process. No runtime was launched or modified
for this change. Validate the native guard and recovery sequence in a live
fixture before relying on it for an unattended survival run.

Offline verification for this change: full Gradle build passed, 90 GTNH Python
tests and 16 runner tests passed, and all 52 MCP tools loaded. New regressions
cover conditional prompt delivery, uncertain reaction payload retention,
checkpoint-to-inference prompt recovery, opt-in hunger/fire guards, atomic guard
configuration, and the retained native water-above-target mining restriction.
