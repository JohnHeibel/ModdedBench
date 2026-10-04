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

Nothing under `src/upstream` changes.

## The three parts of the class

**A source** (`MoveRegistry.Source`) answers, at the start of every search and on the game thread, *which moves does this
body have right now?* It looks at the player (what is worn, held, charged, fuelled) and returns moves that carry those
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
It may only do what a player can: hold keys (`state.setInput`) and look somewhere (`state.setTarget`). It says:

- `updateState`: what to press this tick, and `SUCCESS` once the feet are in the destination cell. `UNREACHABLE` hands
  the route back to be planned again from where the body is, which is the right answer to finding itself somewhere it
  did not expect;
- `calculateValidPositions`: every cell the feet may be in while this step is under way. The executor ends a route
  whose body is in none of them, so a long step gives its whole corridor;
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
in a test arena, give the body the item, send it across with `nav.goto`, and check it arrives unhurt; then take the
item away and check the walker does what it did before.

```
./gradlew --offline :baritone:test
bash harness/smoke/mbtest.sh harness/smoke/glide_course.py
```

## What every move can rely on, and must keep

- A step starts and ends in a cell where the body rests without help: standing on something, on a ladder, in water.
  The walker's own moves assume the cell they start from is one of those.
- Cost is ticks. A step that cannot be taken costs `ActionCosts.COST_INF`; a cost of zero or less is an error.
- A step may end anywhere (`dynamicXZ`, `dynamicY`); its offsets then only say which way it goes.
- The executor gives a step its cost plus `movementTimeoutTicks` before it gives up on it.
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
| `TerrainGrid.standable`, `ForgeSnapshot.liveStandable`, `WorkAccess` | a place to work from is a cell with a floor |
| `PathExecutor.snipsnapifpossible`, `shouldPause` | a route is only joined or held on the ground |
| `Movement.update` | sets `capabilities.isFlying = false` every tick |
| the end of a route | keys are released; a body that needs a key held to stay up falls |

The intended shape is one question asked in one place, *can this body rest in this cell?*, answered by the same
sources that supply moves: the walker says "with a floor, a ladder or water", a hovering body adds "anywhere with room".
The seven places above then ask it instead of looking for ground, and the walker's own moves are only asked from cells
the walking answer covers. That is a change to the walker's core and is not built; a flight that takes off and lands
(glide, rocket jump, launch pad, teleporter) needs none of it.
