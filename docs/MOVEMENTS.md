# Adding a kind of movement

The walker (the Baritone port in `mods/baritone`) gets from one cell to another by a route search over *moves*: walk a
cell, step up, drop, jump a gap, climb, swim. Every job that moves the body (goto, follow, mine, build, click, fight)
plans with that one search, so a move added to it is a move all of them may take.

This page is for adding a move the walker does not have: gliding on wings, a jetpack, a grappling hook, a teleporter.
It is four things: one class, one line, one test, one course case. `ElytraGlide` is the worked example of all four.

| | Where | Example |
|---|---|---|
| The class | `mods/baritone/src/main/java/baritone/gtnh/moves/` | `ElytraGlide.java` |
| The line | `BaritoneMod.init` | `MoveRegistry.register("elytra_glide", ElytraGlide.SOURCE)` |
| The test | `mods/baritone/src/test/java/baritone/gtnh/moves/` | `ElytraGlideTest.java` |
| The course case | `harness/smoke/` | `glide_course.py` |

Nothing under `src/upstream` changes. The smallest whole move in the repo is `Leap` / `Leaping` in
`MoveRegistryTest.java` (test sources, package `baritone.gtnh.pathing`): read it before the glide.

A move that wears or uses an item the way a player does, through the keys a player has, is play. What the rules call
flight is a client change that lets the body do what the game would not. The upstream settings whose names begin
`elytra` are from a newer game's feature that is not ported: they do nothing here, and `ElytraGlide` reads none of them.

## The three parts of the class

**A source** (`MoveRegistry.Source`) answers, on the game thread, *which moves does this body have right now?* It is
asked whenever the walker takes stock of the body (`CalculationInputs.capture`): before a search, and on most ticks of a
walk, so it has to be cheap. It looks at the player (what is worn, held, charged, fuelled) and returns moves that carry those
facts as plain values, or an empty list. This is the only place that reads the player. The elytra's source returns
nothing without unbroken wings on, and otherwise one glide per direction and pitch, each carrying how many ticks the
wings will last.

**A move** (`Move`) is the search's side. At every cell the search opens it is asked `apply(context, x, y, z, result)`:
where does this step end from here, and what does it cost in ticks? It runs on the search thread, thousands of times a
search, so:

- it reads the world only through `context` (`MovementHelper.fullyPassable`, `canWalkOn`, `context.isLoaded`), never the
  live world or the player;
- it gives up in its first lines where it plainly does not apply (the glide: no drop ahead), leaving `result` alone;
- it only promises what the body can do every time. The glide refuses a flight with anything within a cell of its line,
  a touchdown on anything but level ground it can stand on, a landing fast enough to hurt, and a chunk it cannot see.

`apply0(context, src)` makes the `Movement` that plays the step; its destination and cost must be what `apply` said.

**A movement** (`Movement`) is the body's side, ticked on the game thread while its step of the route is being walked.
It may only do what a player can: hold keys (`state.setInput`) and look somewhere (`state.setTarget`). The keys are
cleared before every tick, so a key that is held is set again each tick. Seven reach the game: forward, back, left,
right, jump, sneak, sprint (`InputOverrideHandler.flush`); the two clicks go to the walker's own break and place
helpers, which need a block under the crosshair. An item used in the air, or a mod's own key (a mode toggle), is not
among them: press it before the job with `act.press_key`, or add it in `flush`, which is an upstream file. It says:

- `updateState`: what to press this tick, and `SUCCESS` once the feet are in the destination cell. `UNREACHABLE` hands
  the route back to be planned again from where the body is, which is the right answer to finding itself somewhere it
  did not expect; the second time the same step says so, that step is closed to the job (`Snags`);
- `calculateValidPositions`: every cell the feet may be in while this step is under way. A body in a later step's
  cells is taken to be on that step, and one more than three cells from all of them (or more than two for long) ends
  the route, so a long step gives its whole corridor;
- `calculateCost(context)`: what the step costs now. The executor asks every tick and drops the route when it becomes
  impossible, which is how a route stops using wings that were taken off: the new context no longer has the move;
- `safeToCancel`: false while stopping would be worse than going on (in the air).

If the search needs to predict what the game will do (a flight, a throw), write that arithmetic once as a pure function
and use it on both sides. The glide's `fall` and `fly` are the game's own per-tick formula; the search flies the plan
with them, and the movement flies every pitch it could hold ahead with them each tick and holds the one that lands
nearest the plan. A plan that is slightly wrong is then corrected in the air rather than trusted.

## The test and the course case

`baritone.Planning` (test sources) is a world of cells and the route search over it without a game:
`Planning.context(terrain, moves...)`, `Planning.path(context, start, goal)`. A move's test says where the step is
offered and where it is not, that a route across the obstacle is made of it, and that without the move the same
movement costs `COST_INF`. Anything measured in the game (a physics formula) is pinned by a test that carries the
measured numbers.

The search can only be as right as the game lets it be, so each move also has a case in the game: build the obstacle
in a test arena, give the body the item, send it across with a goal job (`mb_process("goal", ...)`), and check it
arrives unhurt; then take the item away and check the walker does what it did before.

```
./gradlew --offline :baritone:test
bash harness/smoke/mbtest.sh harness/smoke/glide_course.py
```

The course is run from the host: it needs Docker and the throwaway `mbtest` stack, whose server carries the
development fixtures that build the arena and hand out the item (`docker/compose.test.yaml` says how to bring it up).
A run in progress has neither, and may not use fixtures. There, the unit test is the proof of the search's side, and
the body's side is proved on a real obstacle: wear the item, send a goal job across, and read what happened. Two things
help with that:

- the `movementTrace` setting (`nav.settings`) keeps the last ticks of what the movement asked for and what the body
  did, and `nav.status` with `trace: true` returns them;
- physics is measured by stepping the world a tick at a time and reading `obs.player` after each, which is what
  `glide_course.py --measure` does and where `ElytraGlideTest`'s numbers came from.

## What every move can rely on, and must keep

- A step starts and ends in a cell where the body rests without help: standing on something, on a ladder, in water.
  The walker's own moves assume the cell they start from is one of those.
- Cost is ticks. A step that cannot be taken costs `ActionCosts.COST_INF`; a cost of zero or less is an error.
- A step may end anywhere (`dynamicXZ`, `dynamicY`); its offsets then only say which way it goes.
- The executor gives a step its cost plus `movementTimeoutTicks` before it gives up on it.
- A cell is not a whole block and its middle is not always free: an open door's leaf, a ladder, a chest take part of
  theirs. `BlockShapes.room` measures, from the game's own collision boxes, where across its way a body has room in
  a cell, and `BlockShapes.aim` is the point in it to head for; the walker's own steps steer there
  (`MovementHelper.moveInto`). A move that steers a body into a cell does the same instead of heading for the middle.
- Guards (health, threats, time) pause the world; they never fly the body. A movement must be able to carry on from
  whatever tick it was paused in.

## Not there yet: resting in the air

A glide starts and ends on the ground, so it fits the rule above. Hovering does not: a jetpack that holds height or
creative flight makes *any* open cell a place the body can stop, and a route for such a body would be made of cells
with nothing under them. The search itself does not mind (a node is just a cell), but these places decide "the body
can be here" by looking for ground, each in its own way:

| Where | What it assumes |
|---|---|
| `BaritoneNavigation.goTo` | a job starts on the ground, on a ladder or in water |
| `PathingBehavior.pathStart` | the route starts at the support under the feet |
| the walker's own moves (`Moves`) | the cell they start from is stood in; from a cell in mid-air their costs mean nothing |
| `GoalRoom.refusal` | a goal with nothing under it is refused before the search (`goal_not_standable`) |
| `TerrainGrid.standable`, `ForgeSnapshot.liveStandable`, `WorkAccess`, and the jobs' own `onGround` tests (mining, building, clicking, placing) | a place to work from is a cell with a floor |
| `PathExecutor.snipsnapifpossible`, `shouldPause` | a route is only joined or held on the ground |
| `Movement.update` | sets `capabilities.isFlying = false` every tick |
| the end of a route | keys are released; a body that needs a key held to stay up falls |

The intended shape is one question asked in one place, *can this body rest in this cell?*, answered by the same
sources that supply moves: the walker says "with a floor, a ladder or water", a hovering body adds "anywhere with room".
The places above then ask it instead of looking for ground, and the walker's own moves are only asked from cells the
walking answer covers: that gate goes where the moves are looped over, in `AStarPathFinder.calculate0` and
`Path.runBackwards`. The answer is taken on the game thread beside the moves (`CalculationInputs.capture`), because the
search may not read the player.

Two more things such a body needs. A move sees one cell, so a charge or a fuel tank can only limit a single step (the
glide's airtime); a budget that runs down across steps is carried on the search's nodes, as breath is under water
(`airAfter` in `AStarPathFinder`). And a change to those places has to leave walking as it was: `ReferencePathingTest`
and `harness/smoke/movement_course.py` are the tests that say so.

That is a change to the walker's core and is not built; a flight that takes off and lands (glide, rocket jump, launch
pad, teleporter) needs none of it.
