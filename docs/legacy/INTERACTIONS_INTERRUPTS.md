# Native interactions and editable interrupts

> **Changed in the Python-seam phase (2026-09-19).** The supervisor moved to the hot-reloaded
> module `harness/tools/interrupts.py` (`mbtools_gtnh.interrupts`); the running thread and event
> journal persist in `mbtool.state["interrupts"]` and armed watch specs are re-armed into the fresh
> supervisor after a module reload. Transport outages pause polling with backoff (<=10 s) and keep
> every watch armed; the supervisor follows the kernel factory, so a game restart reconnects it. A
> failed `interrupt.fire` is retried with the **same** `eventId` (5 attempts, exponential backoff)
> and, if still undelivered, the watch is re-armed carrying that `eventId` (`pendingEvent` in
> status) instead of dropping the interrupt. `mb_interrupt` runs on the control lane.

See [layer ownership and conditional self-prompts](LAYERS.md) for returning an
undecided action to the model with `context.prompt(...)` or a watch `prompt`.

The standalone client implements `act.use_block`, `use_entity`, `attack_entity`,
`use_item`, `eat`, `select_hotbar`, `combat`, and `status`. These use ordinary
Minecraft player-controller packets and the shared input arbiter. No Baritone jar
is required for these actions. Resume simulation before starting an action.

## Targeted actions

Raw `act.input` attack holds and `keys.press` on the attack binding lock block
edits to the initial crosshair block by default. They end early with
`outcome:"attack_target_changed"` when its block or metadata changes, and cannot
continue onto a machine behind it in the same tick. An initial miss permits no
block edits. `allowRetarget:true` explicitly restores continuous raw attacking.
This bounds controller targets; it does not disable a mod tool's own area effects.

- `use_block`: required `x,y,z`, `face` (0 down, 1 up, 2 north, 3 south,
  4 west, 5 east); optional block-local `hit:[x,y,z]`, `sneak`,
  `expected:{id,meta}`, and `expectedHeld` (full observed stack or null).
  Uses the native selected bounding box's face center by default, including partial
  blocks. Explicit finite hit coordinates may extend outside the unit cube (up to
  16 blocks); native reach and ray checks still apply. Native ray checks reject obstruction,
  wrong face and out-of-reach targets. Re-aiming uses the actual post-sneak stance;
  the delivered hit is the native visible point and must agree with the requested
  point within 0.03 blocks. GT tool targeting uses its native mouse-picking context
  (including expanded pipe selection boxes), while physical collision stays unchanged.
  It does not silently fall back to item use.
- `use_entity` / `attack_entity`: required transient `entityId`; pass the
  `expectedHandle` returned by `obs.entities` to reject recycled IDs. Attack range
  is at most three blocks. Player attacks require explicit `allowPlayers:true`.
  Durable world notes continue using server UUIDs from `obs.entity`.
- `use_item`: use the selected item through its native behavior. Optional
  `x,y,z,face,hit` aims and verifies a block; `fluid:true` includes fluids accepted
  by native collision rules in the ray. Omit a target to use the current view. `ticks` bounds sustained
  use; instantaneous items are sent once. Native Forge/item callbacks decide the
  result, including fluid compatibility. This is not a general fluid drain API.
- `eat`: selected edible/drinkable item, `ticks` (default maximum 400); succeeds only after
  observing held-stack or hunger changes after the settling period. Item use posts
  the native Forge air-use event with the use key held before calling the item.
  Select an item from the hotbar first,
  or compose the inventory primitives to move an observed stack there.
  Reports `nativeUseTicks` and `tickBudget`; an item whose native duration exceeds
  the remaining budget fails immediately with `native_use_duration_exceeds_budget`.
  Pack food-history penalties and invulnerability are respected, not bypassed.
  Initial refusal includes `food`, `canEat`, and `invulnerable` diagnostics.
  The native server consumption-completion packet releases held use before vanilla
  can click again and sets `serverAcknowledged:true`; eating cannot open a chest
  under the crosshair after finishing. Other interaction receipts remain unacknowledged
  unless this native completion packet was observed for their owned use.
- `select_hotbar`: `slot:0..8`, optional full observed `expected` stack.
- `combat`: stationary, bounded kill aura. `ticks` defaults to 200, `range` is at
  most 3, `intervalTicks` defaults to 10. Defaults to visible hostile living
  entities, excludes players, and stops when clear. Optional exact `types`,
  `entityId`, `expectedHandle`, `hostile:false`, `allowPlayers`,
  `stopWhenClear:false`. Compose movement separately using navigation and guards;
  `pursue:true` is explicitly unsupported. No attacks through walls or extended
  reach. Entity disappearance does not count as a confirmed kill.

All support per-operation `overrideProtection`; it is never inherited by later
actions. Receipts include `nativeReturn`, before/after observations,
`observedChanges`, elapsed ticks and partial effects. The raw native return is
not a success flag: item use can mutate NBT in place while returning false.
`serverAcknowledged:false` means client prediction alone is not proof of machine
processing, item consumption or damage. Observed changes can also include normal
concurrent simulation; they are not attributed automatically to this action. Combat
reports attack attempts separately from observed deaths. Use your own observable
postconditions for arbitrary machines. `act.stop`, preemption, death, disconnect,
world changes and request deadlines release owned controls and held use.

These primitives contain no bucket, fluid, food, tool or machine registry IDs.
Air-use and block-use callbacks are distinct; a container may implement either.
They do not assume one bucket volume, stack replacement, metadata changes, or
that every filled container can place its contents in the world. Metadata and
full NBT survive selection, stale guards and receipts. `eat` is a convenience for
the item's advertised native consumption action; arbitrary held behavior remains
available through `use_item`, `use_block`, or raw input. Item IDs in fixtures and
editable example procedures specify test/task data, not primitive dispatch rules.
Observe the whole inventory when checking item outputs: an item may mutate its
held NBT, change metadata, replace the held stack, put outputs in other slots,
or drop entities. No single held-stack postcondition covers all of those cases.
Protection checks both ordinary block and fluid rays without item-class
exceptions. Default `automation` regions permit deliberate interactions;
`all_edits` regions require the operation's explicit override. Ray-based protection
does not describe every possible remote or area effect of arbitrary mod items.

## Independent interrupt supervisor

`mb_interrupt` exposes `add`, `remove`, `reload`, `status`, and `ack`.
`mb_interrupt_events(after, limit, wait_s)` reads a durable SQLite event journal
without consuming events; multiple consumers can maintain independent cursors.
The Python supervisor polls on its own daemon scheduler while inference or MCP
actions are running. Default interval is 100 ms plus observation latency; it is
not a tick-exact guarantee. The existing native server health/air guards remain
available for quicker fixed emergency conditions.

Example `add` specification, named `unsafe_health`:

```json
{
  "queries": {
    "player": {"method": "obs.player"},
    "nearby": {"method": "obs.entities", "params": {"radius": 8}}
  },
  "condition": {
    "all": [
      {"lt": ["player.health", 8]},
      {"any": {"path": "nearby.entities", "where": {"eq": ["$.hostile", true]}}}
    ]
  },
  "effects": ["notify", "cancel", "pause"],
  "reason": "low health with nearby hostile",
  "oneShot": true
}
```

Conditions support `all`, `any`, `not`, `eq/ne/lt/lte/gt/gte`, `exists`,
`changed/increased/decreased`, and collection `any/all` or `any_of/all_of`.
Comparison operands are `[path, literalValue]`; paths start with a query alias.
Missing requested observations are faults, not false predicates. `exists` is the
explicit optional-field test. Temporal predicates need a previous sample.
`consecutive` (alias `debounce`) counts matching polls; `edge` applies after that
qualification, and `cooldown` is in wall-clock seconds. Defaults are one-shot;
choose `oneShot:false` deliberately for repeated events.

`obs.batch` runs at most 16 explicitly advertised `watchable:true` synchronous
read methods in one game-thread service pass. It returns `values`, per-alias
`errors`, `tick`, and bridge/world/dimension context. Async reads are not batchable.
Keep expensive scans out of fast watches. Up to 64 named watches are supported.

For arbitrary combinations, write an ordinary Python file:

```python
def evaluate(context):
    player = context.values["player"]
    inventory = context.read("obs.inventory", detail="counts")
    context.state["samples"] = context.state.get("samples", 0) + 1
    return {
        "match": player["health"] < 8 and not player["controlActive"],
        "payload": {"inventory": inventory, "samples": context.state["samples"]},
    }
```

Supply `file` instead of `condition`, plus any batched `queries` you want.
`context.read` accepts any advertised read primitive, including async reads;
those additional reads are not part of the atomic batch. `context.previous` and
`context.state` support temporal procedures. Custom Python is trusted harness
code, not a security sandbox. Its supported context API is read-only; reactions
are declared separately. Workers time out and disarm; Python cannot forcibly
terminate an arbitrary blocked thread, but late results cannot issue reactions.
Reload compiles new source before replacing the working callable. Removed,
replaced, timed-out and obsolete-generation evaluations cannot fire later.

## Reaction and runner contracts

Effects are independent:

- `notify`: append the receipt/event without changing time or controls.
- `cancel`: urgent barrier cancels active owned work and queued interactions.
- `pause`: request the existing coordinated server/client/GT worker pause and
  include confirmation or a partial failure in the receipt. The frozen display
  shows `Paused: interrupt:<reason>`.

Cancel/pause default to an admission latch so an obsolete model response cannot
immediately issue another action. `interrupt.ack(eventId)` clears that latch;
it never resumes time or retries a previous action. `latch:false` is available
when the runner supplies its own continuation policy. A pause without cancel
preserves the active job; it may continue after explicit acknowledgement/resume.

Every fire includes the expected bridge UUID, world UUID, dimension and world
epoch. Optional `operationScope:true` also captures the active control operation
ID. A stale reaction fails before effects. Native event IDs deduplicate retries
within the current client JVM (up to 256 retained receipts, retaining unacknowledged
latches). Already sent Minecraft packets cannot be recalled. Removal can discard
a queued reaction before dispatch, but cannot undo a reaction already sent.
Ambiguous failures are journalled and never blindly retried.
`interrupt.status` keeps routine polling small; opt into receipts with `eventId`
or `limit:1..32`. A repeating watch has at most one reaction in flight.

The journal defaults to `gtnh/.state/interrupts` (override
`MODBENCH_INTERRUPTS_DIR`); bounded retention reports cursor gaps. Watches are
process-local and must be rearmed after restart; old Python code is not silently
executed from persisted records. World/bridge changes disarm old watches.

An external asynchronous runner can call
`gtnh_interrupts.race_interrupt(supervisor, model_awaitable, cursor, cancel=...)`.
It races journal events against the response, cancels/discards a stale awaitable,
and returns triggering events for the next decision. Faults, stalled predicates,
uncertain reactions and journal gaps also wake the runner. `cancel` can terminate a
provider request. MCP alone cannot cancel the host application's model inference;
that application must use this adapter or consume the journal itself. Client
cancellation and pause work independently of that runner integration.

Live batch: `python tools/gtnh/interaction_smoke.py`. Fixtures journal original
player state and spawned entity UUIDs, restore on completion, and leave the server
paused. Results are recorded in `gtnh/.runtime/logs/interaction-smoke.json`.
Offensive combat checks use a lit, enclosed arena and a journalled player health
reserve. They establish native damage, selection and cancellation behavior;
they do not establish autonomous survival against arbitrary pack mobs.

`tools/gtnh/fluid_container_smoke.py` additionally checks three NEI-discovered
containers with different native behaviors: IC2 Universal Fluid Cell (NBT), IC2
Empty Cell (metadata output in a different slot), and Fired Clay Bucket (item
replacement). It verifies source removal on the server, inventory changes,
strict protection and deliberate use inside a default protected region.
Test procedures explicitly compose gestures; production actions do not silently
retry or dispatch according to these item identities.
