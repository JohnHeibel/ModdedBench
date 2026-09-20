# Validation

What has been verified, how to re-run it, and what has not been verified.
Per-run evidence files are written to the ignored `.runtime/evidence/`; the
detailed milestone records are in git history (see [HISTORY.md](HISTORY.md)).

## Offline checks (run before every commit)

```bash
./gradlew build
```

```bash
python harness/mcp/server.py --check
```

```bash
python -m unittest discover -s harness/tests -p "test_*.py"
```

The Gradle build compiles every module, runs the JUnit suites (transport,
clock, JSON, pathing, construction settings, quest access, seam scans) and
verifies that every file under `mods/baritone/src/upstream` matches the
SHA-256 pinned in `UPSTREAM_SOURCES.json`. The Python tests cover the transport
(out-of-order replies, bounded events, disconnect, cancellation), atomic tool
reload and state survival, lanes, the interrupt supervisor, world notes and
their surfacing, the launcher and the autonomous runner, all without a game.

## Live checks (need the managed runtime)

| Probe | What it verifies |
| --- | --- |
| `harness/smoke/smoke.py --mcp-reload-proof` | Both bridges, player identity, observations, aiming, finite input, cancellation, screenshots, tool hot reload with a syntax error retained safely. |
| `harness/smoke/interaction_smoke.py` | Targeted block/entity/item use, eating, hotbar selection, bounded combat, attack-hold target locking. |
| `harness/smoke/gui_smoke.py` | Container observation, slot clicks, transfers, cursor return, text fields, buttons, hit tests. |
| `harness/smoke/primitive_regression_smoke.py` | The 13 regressions from the EBF trial follow-up: grass clearing, attack-hold bounds, food budgets and acknowledgement. |

## Accepted live runs

All on Windows, GTNH 2.8.4 client plus dedicated server. Except for the agent
trials these are fixture runs: they use the development fixture methods
(`-PdevFixtures` build, `start-server --dev-fixtures`), supplied materials and
a journaled world that is restored afterwards. They demonstrate the mechanism,
not resource gathering. Only the four probes above are in this repository;
the other fixture scripts were not carried over.

| Date | What | Result |
| --- | --- | --- |
| 09-12 | Time control: frozen dimensions, deferred actions, guards, reconnect, client restart while paused; GT progression equal with and without pauses ([TIME_CONTROL.md](TIME_CONTROL.md)) | Pass |
| 09-12 | NEI: search, exact metadata and NBT, ore alternatives, fluids, GT power, heat, circuits and chances, native page capture while paused | Pass |
| 09-12 | World memory and notes: routes both ways, recording, protection modes and overrides, persistence across client and server restarts | Pass |
| 09-12 | Inventory and GUI: chests, furnace, anvil, vanilla and Tinkers crafting, GregTech ModularUI, Forestry worktable, AE2 cell workbench and crafting terminal | Pass. AE2 autocrafting requests and pattern encoding not covered |
| 09-12 | Interactions and interrupts: block and entity use, fluid containers, eating, bounded combat, notify/cancel/pause reactions, latch and acknowledge | Pass |
| 09-13 | Tile, NBT and Waila reads from the server across several mods; IC2 and Ender IO energy | Pass |
| 09-13 | Upstream engine: 416-block route both ways, slabs, stairs, carpet, ladders, rerouting | Pass. Deep snow case not passing |
| 09-13 | Mining: GT drill, exact metadata, protected target, cancel and resume; natural-world logs | Pass after the engine port (failed before it) |
| 09-13 | Construction: 96-block mixed-material house; builder-mode layers, substitutions, slabs, stairs, panes, a trapdoor, exact GT tiles | Pass. Ordinary doors unsupported |
| 09-13 | Machine plan: imported JSON plan builds an extractor, pipes and tank with distinct GT variants verified by picked item; copy and rebuild | Pass |
| 09-13 | Fluids: still-water swimming and bank arrival | Pass. Flowing-water target and water-capped obsidian fail |
| 09-13 | Farm harvest and replant, Backfill, goals, follow, cache persistence | Pass |
| 09-14 | Engine events, rendering, free-look; single-job scaffold build and clearance | Pass |
| 09-14 | Natural exploration: frontier chosen, cache persisted | Partial: the player was killed by a mob on the return trip |
| 09-18 | EBF follow-up regressions (`primitive_regression_smoke.py`) | Pass |

## Agent trials

- **Electric Blast Furnace line (2026-09-18).** A supervised agent assembled
  and commissioned an EBF from supplied materials in survival, repaired
  maintenance, powered it from a combustion generator, and processed two
  aluminium recipes with automatic item transport. Recovery from a stall
  needed operator diagnostics.
- **Bounded natural pilot (2026-09-13).** Through the file-backed runner, an
  operator-driven agent resumed a mining job after a client restart, detected
  and claimed its first quest, and crafted from an NEI recipe. A custom watch
  interrupted inference, cancelled controls and paused; acknowledge, review
  and resume worked. The run ended on a damage and fire interrupt with the
  shelter unbuilt.
- **Natural-world log mining (2026-09-13).** Failed before the upstream engine
  port (nothing collected in 1,352 ticks); passes after it (eight logs in 698
  ticks, no damage).

## Known gaps

- No unattended long-duration or resource-progression run has been completed.
  The quest-book run described in `PROMPT.md` has not been attempted yet.
- Survival: live hostile encounter, retreat, death and item recovery have not
  been demonstrated end to end. Guards pause; they do not act.
- Navigation: flowing-water traversal, deep snow, fluid-container fall
  recovery and ordinary door placement are unsupported; obsidian under flowing
  water is rejected by the inherited safety rule. Flight is not implemented.
- Construction: clearance-phase resume has not had a live interruption test.
  Tile NBT, machine configuration and multiblock formation are never part of
  a build. Placement prediction covers a small set of callbacks; everything
  else is verified after the real placement.
- Runner: only mock tests and the operator-driven file adapter; no live
  provider-backed adapter.
- Machines: the EBF trial covered one no-fluid recipe. Fluid recipes, higher
  tiers and fuel logistics are untested, as are AE2 autocrafting and pattern
  encoding.
- Time control: verified with a GregTech fixture and a client JVM restart while
  paused. Not verified: full-pack timer quiescence, Applied Energistics,
  GT multiblocks under pause, live OpenComputers workloads.
- Observations: tile/NBT/Waila reads are generic; interpretation of any
  specific mod's fields is the model's job and has only been exercised on a
  handful of GT machines.
- Platform: the managed runtime has only been run on Windows. There is no
  Linux or container launch path yet.
