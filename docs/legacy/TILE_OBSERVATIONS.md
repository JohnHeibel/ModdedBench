# Authoritative machine observations

The standalone client exposes `obs.tile`, `obs.nbt`, `obs.waila` (alias
`obs.hwyla`) and mixed `obs.batch` through the normal MCP `mb_obs` tool. No
development fixture permission, GUI opening, Baritone or Java modification is
needed to read a machine's native stored configuration and tanks. Python
adapters can interpret these observations and compose the existing interaction
primitives for a new machine.

```python
tile = mb_obs("tile", {"pos": [x, y, z], "detail": "full", "hwyla": True})
field = mb_obs("nbt", {"handle": tile["nbt"]["handle"], "path": "SomeModKey"})
```

Use native observed keys, item IDs, metadata, NBT and NEI identities; the bridge
does not assign meanings to arbitrary mod NBT fields or assume vanilla machines.
These reads do not modify NBT or configure a machine. Configuration still uses
ordinary clicks, tools and GUI actions, followed by fresh observations.

## Tile response

- `src: "server"`, block `id`, `meta`, `pos`, `dimension`, `hasTile`, `tileClass`.
- `provenance`: persistent world ID, dimension, server bridge ID, server tick,
  world time, total time and block position. Client tile synchronization is not
  used as the authoritative source.
- `tile`: bounded JSON projection of the tile's native `writeToNBT` result.
  `nbt.handle` names its immutable snapshot; `nbt.truncated` reports elision or
  pagination. A serialization failure is `nbt.error`, never an empty tag.
- `interfaces`, `support.inventory`, `support.fluids`: native interface discovery.
- `inventory`: nullable stack array in native slot order; `inventoryInfo.offset`
  identifies its first slot. IDs, damage/metadata, counts and SNBT are preserved.
  `inventoryOffset`/`inventoryLimit` page up to 256 slots. Side-accessible slot
  indices are reported for `ISidedInventory`; no insertion simulation is called.
- `fluids.views`: the seven native `IFluidHandler.getTankInfo` results, including
  `UNKNOWN`. Each view has tanks, native capacity, explicit `empty`, canonical
  fluid ID, amount in mB, optional fluid NBT, or an error. `tanks` is an alias for
  a successful UNKNOWN view. **Do not add side views together:** multiple views
  may describe the same storage. Missing, truncated or failed reads do not mean
  an empty machine. The API never calls fill/drain to probe a machine.
- `energy`: optional native GregTech EU, IC2 energy-storage and RF interface
  adapters, including per-getter errors. Unsupported energy systems are not
  invented as zero; use native NBT/Waila or an additional adapter. RF side views
  can overlap too.
- `hwyla`: native Waila head/body/tail and combined text lines, provider names
  and errors from both sides. The server runs Waila's registered NBT providers
  with its required position envelope; the client runs registered stack/text
  providers with a separate accessor and native tagged tooltip lists. It does
  not replace the HUD's target. Text follows the player's Waila settings and
  sneak state. Waila markup is retained; `lines` removes ordinary color codes.

Only loaded blocks within 128 blocks of the connected player, in their current
dimension, can be queried. `pos: [x,y,z]` and individual `x,y,z` are equivalent;
omitting position uses the block crosshair. A successful air/non-tile read has
`hasTile: false`; unloaded, out-of-range and wrong-dimension targets are errors.
Waila currently observes blocks. Existing `obs.entities`/`obs.entity` cover
entity state and identity separately.

## NBT drill-down

`obs.nbt` accepts a handle, `path`, `offset`, `limit`, `budget` and `depth`.
Dotted/slash paths traverse compounds and numeric list/array indices. Use an
array of exact key/index segments for keys containing dots, slashes or empty
strings. Its response reports the native root type, value, truncation, source
provenance and `snapshot: true`. Integer values, including native longs, remain
integers. Lists and byte/int arrays can be paged without mutating their source.

`offset` and `limit` apply to the selected root collection; compounds use sorted
keys. Nested elision is explicit. Default projection budget is 4 KiB (16 KiB for
`detail: full`), configurable to 64 KiB; depth is 0–32 and page size 1–256. Raise
the budget or drill to a smaller subtree when truncated. Stack/fluid SNBT over
8,192 characters is explicitly elided, with the tile snapshot as the drill-down
path where the mod serializes it.

Snapshots are unique even when time is frozen, player/world/dimension scoped,
and copied before caching. They expire after ten minutes, server restart or
cache eviction (64 entries, 8 MiB total, 1 MiB serialized per tag). Store useful
values in durable notes; handles are not durable references. A handle always
reads the captured state, even after wrenching or block removal. Re-read
`obs.tile` for current state.

## Batches, interrupts and pause

```python
mb_obs("batch", {"queries": {
    "source": {"method": "obs.tile", "params": {"pos": source, "hwyla": False}},
    "sink": {"method": "obs.tile", "params": {"pos": sink, "hwyla": False}},
    "player": {"method": "obs.player"}
}})
```

Up to 16 reads return `values`, per-alias `errors` and guarded world `context`.
Live server reads execute together at a single server maintenance boundary;
their provenance tick matches `serverTick`. Historical `obs.nbt` values retain
their original tick. Local reads share the earlier client `tick`. A mixed batch
does not claim atomicity between the client and server. Connection/world changes
while a response is pending fail the request instead of relabeling stale data.

All four observation methods are advertised as watchable. Declarative
conditions and editable Python `evaluate(context)` predicates can use them;
custom predicates can also call `context.read("obs.nbt", ...)`. Set
`hwyla: false` for inexpensive structured polling. Existing interrupt effects
can notify, cancel controls and/or request a coordinated pause.

These packets use the existing maintenance channel and main game threads,
including when paused. They do not resume simulation or step ticks. Bounded,
ordered UTF-8 reply frames support responses up to 1 MiB; oversized requests or
responses fail explicitly with advice to narrow the batch or page the data.

## Port references and acceptance

The design follows retained DJ2 `bridge/TileMethods.java`, `Nbt.java`,
`Caps.java` and `HwylaBridge.java`. Forge 1.12 capabilities became 1.7 native
interfaces; the integrated-server task hop became an authenticated player
connection to the dedicated server. Native Waila 1.8.15 supplies the exact
provider signatures, ordering and position envelope.

Run `tools/gtnh/tile_observation_smoke.py` for the multi-mod observation batch
and `tools/gtnh/machine_schematic_smoke.py` for the machine/pipe workflow. Both
restore their journalled fixture and leave time paused. The machine test uses
normal agent observations for all decisions and postconditions; its development
inspector is an independent final assertion only. See `VALIDATION.md` for the
latest actual results and limitations.

`tools/gtnh/tile_energy_smoke.py` additionally checks the native IC2 BatBox and
Ender IO capacitor-bank energy interfaces and Waila providers.
