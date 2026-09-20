# Validation

What has been verified, how to re-run it, and what has not been verified.
Detailed per-milestone evidence (check counts, coordinates, screenshots) is in
`docs/legacy/VALIDATION.md`, `docs/legacy/NATIVE_RUNTIME_ACCEPTANCE.md`,
`docs/legacy/TIME_CONTROL_AUDIT.md` and `docs/legacy/EBF_AGENT_TRIAL_2026-09-18.md`.

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

Time control, GT progression under pause, construction, mining, route memory,
NEI and quest access were each accepted with their own live fixture runs
(recorded in the legacy validation docs). Those fixtures depend on the
development fixture methods (`-PdevFixtures` build, `start-server
--dev-fixtures`) and on supplied materials; they demonstrate the mechanism,
not resource gathering.

## Agent trials

- **Electric Blast Furnace line (2026-09-18).** A supervised agent assembled
  and commissioned an EBF from supplied materials in survival, repaired
  maintenance, powered it from a combustion generator, and processed two
  aluminium recipes with automatic item transport. Recovery from a stall
  needed operator diagnostics.
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
- Construction: single-plan scaffold cleanup still needs a harness-chosen
  approach; clearance-phase resume has not had a live interruption test.
- Time control: verified with a GregTech fixture and a client JVM restart while
  paused. Not verified: full-pack timer quiescence, Applied Energistics,
  GT multiblocks under pause, live OpenComputers workloads.
- Observations: tile/NBT/Waila reads are generic; interpretation of any
  specific mod's fields is the model's job and has only been exercised on a
  handful of GT machines.
- Platform: the managed runtime has only been run on Windows.
