# Routes and protected regions

`mb_memory` exposes persistent knowledge through `memory.*`; `mb_route` executes
saved routes through `baritone.route`. These operations are advertised by
`mb_methods`. Memory belongs to a saved server world and dimension, using the
server's persistent UUID plus connection address. A new world at the same address
gets separate memory. Client restart/reconnect retains saved records; using a
different address alias intentionally creates a separate scope.

## Normal base work

Protection defaults to `mode: "automation"`. Navigation and automatic excavation
must find a route that preserves the region. Walking on its floor, opening
containers, using machines, and deliberate `baritone.mine_block`,
`baritone.place_block` or direct keyboard work remain normal operations.
Future bulk mining/building must acquire an `automatedEdits` control lease too.
This distinction is explicit in the shared control API, rather than inferred
from an action's name.

```python
mb_memory("protect", {"name": "base", "min": [100, 50, 200], "max": [130, 85, 230]})
```

Use `mode: "all_edits"` when a particular area must also reject deliberate block
edits and raw synthetic attack/use. Empty-hand GUI/door activation remains
available. Strict mode conservatively blocks held-item use on a protected block
or its clicked neighbor, including tools whose effects are not yet classified.
Reserve it for deliberate locks, not routine workshop use.

```python
mb_memory("protect", {"name": "critical wall", "min": [100, 64, 200],
                     "max": [100, 67, 210], "mode": "all_edits"})
mb_call("baritone.mine_block", {"x": 100, "y": 64, "z": 205,
                               "overrideProtection": True})
```

Changing or removing any existing region requires `overrideProtection: true`
and cancels active controls. Bounds are inclusive; overlapping regions all apply.
An override on a terrain operation is scoped to that operation and its child
work. It does not weaken the saved region or carry into the next operation.
Native overrides produce local JSONL receipts. This protects against accidental
edits, not arbitrary edited code, remote players, explosions, or every possible
modded area-effect/tool/fluid consequence. New tool adapters must describe and
check their full affected area.

## Save and reuse routes

```python
mb_memory("waypoint", {"name": "workshop"})  # Current player feet
mb_memory("waypoint", {"name": "base exit", "pos": [125, 64, 215]})
mb_memory("route", {"name": "workshop to cave",
                    "points": ["workshop", "base exit", [150, 64, 215], [155, 59, 220]],
                    "radius": 2})
mb_route("workshop to cave", timeout_s=900, timeout_ticks=16000)
mb_route("workshop to cave", reverse=True, timeout_s=900, timeout_ticks=16000)
```

Routes copy waypoint coordinates when saved. Replacing a waypoint later does not
silently change a route. `replace: true` is required to replace either a route or
waypoint. `memory.status` provides summaries; `memory.get` with `{kind, name}`
returns an exact record, including all route points.

The first anchor is approached with ordinary navigation. Each subsequent leg
constrains new paths to a three-dimensional corridor around its anchor segment.
Terrain is captured again and movement is checked live. A blocked corridor fails
instead of silently taking an unrelated shortcut. This is route knowledge, not
a persistent chunk cache or replay of old keypresses. Add anchors at turns and
elevation transitions; a straight corridor cannot describe a winding cave.

Recording captures actual feet cells during play:

```python
mb_memory("record", {"action": "start", "name": "base circuit", "radius": 2})
# Travel with navigation, direct input, or human controls.
mb_memory("record", {"action": "stop"})
```

Only exactly collinear middle points are removed; turns, reversals and height
changes remain. `cancel` discards a recording. Disconnect/dimension changes and
sample overflow invalidate a recording rather than saving a misleading route.
Saved routes accept 2–4096 anchors and radius 1–16. A world can store 1024
waypoints, 128 routes and 256 regions, with a 16 MiB file limit. Writes use a
temporary file and replacement; malformed policy files fail closed for edits.

`act.stop`, input takeover, deadlines and protection changes cancel route jobs.
Status reports `nextIndex` and completed anchors. `start_index` selects the first
anchor to approach in the chosen forward/reversed order; restarting there is a
new operation with fresh terrain checks. This does not yet implement generic
Baritone process pause/resume. Raise both caller and simulation deadlines for
long travel; world pause does not stop a caller's wall-clock timeout.

## Acceptance

Run the offline Gradle/Python tests first, then use one managed client/server
session for `tools/gtnh/memory_smoke.py --restart-client` and movement/work
regressions. The memory fixture journals terrain/player state and removes only
its own named memory records. Evidence is saved under ignored
`gtnh/.runtime/evidence/memory-smoke.json`.
