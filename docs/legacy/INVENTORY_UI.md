# General inventory and UI primitives

The standalone client bridge provides native inventory and GUI operations with
Baritone absent. Higher-level crafting and machine procedures belong in reloadable
Python tools. A machine may consume items, modify NBT, route outputs, expose ghost
filters, or use an entirely different interaction channel. No primitive selects a
recipe or treats a successful click as proof that a process completed.

## Observe and select

- `obs.inventory {detail:full|compact|counts}`: player indices, hotbar/armor,
  held item, cursor, free slots and identity-separated totals.
- `obs.container {detail:summary|full|compact,probeSlot?}`: screen/window/epoch,
  container indices, backing inventory/index, real and virtual slots, enabled and
  takeable state, limits, native coordinates, buttons, text fields and custom
  widget observations. `probeSlot` reports each slot's native `acceptsProbe` and
  `spaceForProbe` for an actual source stack without picking it up. This is an
  eligibility observation, not a guarantee that a slot preserves the item.
- `inv.find {selector:{id,meta?,nbt_hash?,nbt?},scope:player|container}` finds
  actual stacks. Use `nei.search` for catalogue discovery. Inventory indices and
  container indices are different namespaces.
- `obs.tooltip {slot|inv|cursor,advanced}` calls the item's native tooltip.
- `gui.hit_test {x,y}` and `gui.hover {x,y}` support inspection at scaled GUI
  coordinates. `sys.screenshot` remains available for drawn information.

Stacks retain registry ID, metadata, quantity and complete SNBT. `nbt_hash` is a
SHA-256 fingerprint of the observed SNBT, useful for exact selection and stale
guards; it is not a cross-serialization canonical-NBT promise. Native stack/NBT
equality decides whether stacks can merge. Absent stacks/cursors may be omitted
in JSON; pass explicit `null` when guarding an expected empty slot or cursor.

Ordinary-slot eligibility rejects disabled, virtual and known phantom/ghost/ME
slots. Unknown custom slot behavior is checked through native operations and
their effects. A custom class name alone cannot establish all its semantics.

## Act and inspect the receipt

| Operation | Contract |
| --- | --- |
| `gui.click_slot` | Pickup, shift-click (`quick_move`) and clone default to native events. Swap/drop/pickup-all use the structured ordinary-slot path. Pass `windowId`, `epoch`, `slot`, `expected`, and `expectedCursor`. |
| `gui.transfer` | Move up to `count` (1–64) from an expected ordinary source to explicit `destinations`. Requires empty cursor; returns the remainder to its known source. Output/custom slots use native clicks instead. |
| `gui.return_cursor` | Return the entire observed cursor to explicit compatible destinations with enough capacity. Never silently drops it or swaps an unrelated item. |
| `gui.click_at`, `gui.drag`, `gui.scroll` | Native mouse events, modifiers, hold duration and scaled coordinates. A click hovers and permits a rendered frame/GUI update before pressing, so cached hit targets are current. |
| `gui.key`, `gui.type`, `gui.text_field` | Native key events and modifier polling. Text-field selection uses its observed `field` path. Typing triggers normal listeners and packets; it does not call a widget's setter directly. |
| `gui.button` | Native click on a unique observed vanilla button by ID, text or index. Custom widgets use their observed coordinates. |
| `gui.container_button` | Native `Container.enchantItem` packet channel for containers that implement it. No generic process acknowledgement. |
| `gui.status`, `act.stop` | Inspect current/last receipt and cancel. Shared control ownership prevents simultaneous Baritone/direct/GUI input. Releases stay with the original screen. |

`gui.close` refuses an occupied cursor unless `allowCursorDrop:true` is explicit.
Resume time before starting native GUI actions; paused actions are rejected before
clicks. Observations, screenshots and native NEI inspection remain available while
paused. An existing action can be stopped while paused.

Receipts include attempted/delivered events, changed slots, cursor before/after,
native transaction counts, server acknowledgement and immediate/settled transfer
deltas. Matching window and transaction IDs are required for acknowledgement.
Rejection or an acknowledgement timeout is an error, with a partial receipt.
Early local failures can still have native transactions pending; inspect state
again before deciding what to do. Request cancellation/transport loss can end the
RPC first; `gui.status` preserves the last GUI operation's partial effects.

`destinationPolicy:passive` requires the moved items to remain at settlement.
`consuming` allows processing/routing to change the settled quantities. A missing
item is reported as `consumed_routed_or_corrected_unproven`, not declared consumed.
Immediate identity-changing effects also stop an exact transfer with its partial
receipt. The AE2 cell workbench, for example, initializes cell NBT on insertion.
Native clicks plus an adapter-specific postcondition are appropriate there.

Custom packets may have no vanilla transaction acknowledgement. Such actions
report `client_event_delivery_only`; use their observable postcondition. The
driver never retries because a slot appears unchanged. Multiple-step procedures
are not atomic and must not be replayed wholesale after an uncertain result.

## Build higher-level procedures

The MCP profile exposes `mb_inventory`, `mb_find`, `mb_transfer`, `mb_click_slot`
and the complete `mb_gui` surface. Add `@tool` functions in
`tools/mcp/profiles/gtnh/`; changed tool modules reload on the next call and a
failed import retains the last working version.

`tools/mcp/gtnh_ui.py` supplies `ContainerSession`: it captures a screen epoch,
re-observes before guarded operations, accumulates receipts, stops on partial
quantity/screen changes, and waits for a caller-provided predicate without
repeating inputs. Serialize routines sharing one client: it does not reserve
the GUI between calls. Shared imported helper changes require re-importing the
helper or restarting MCP; tool-file reload does not unload arbitrary imports.

`tools/mcp/examples/gtnh_load_inputs.py` is an editable example. Its caller supplies
slot assignments; a machine adapter can then set a mode, press its start control,
and wait for its own output/progress condition. Recipe selection, layouts,
remainders and recovery decisions are intentionally supplied by the adapter.
The old DJ2 `procedures.Workflow` uses a different lease/action schema and should
not be imported unchanged into the GTNH profile.

## Coverage and limits

The live batch covers chest transfers, anvil renaming/output, furnace fuel,
vanilla and Tinkers crafting with consumed inputs, GregTech ModularUI slot events,
Forestry worktable inventory, and AE2 cell-workbench ghost filters/NBT changes.
The powered AE2 crafting terminal also passes search, stored quantities larger
than a stack, NBT-specific selection, withdrawal/deposit, partial-stack withdrawal,
sort buttons, native crafting, automatic replenishment and shift crafting with
server-side ingredient conservation. ME virtual slots remain separate from
ordinary inventory slots. Custom native search wrappers are observed and typed
through normal keyboard events.

This establishes editable primitives for a future model-written ME adapter.
Autocrafting request/confirmation/status screens, pattern encoding, network
configuration and every AE2 mode are **not** claimed as validated automation.
Native MUI1 window/child trees expose bounds, text, tooltips, enabled/focus state
and layers. Other widgets have bounded heuristic inspection and native event/
screenshot fallbacks; this is not exhaustive semantic coverage of all mod GUIs.

Java changes still require build/install/client restart. Generalized GUI primitives
do not yet supply machine-specific automation, recipe-route planning, or a full
autonomous-runner lifecycle.

Use [durable world notes](WORLD_NOTES.md) to attach adapter plans, discovered slot
layouts and procedure-source references to machines or planned base regions.
