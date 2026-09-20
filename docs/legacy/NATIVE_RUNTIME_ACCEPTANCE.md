# Native runtime and API acceptance

Updated 2026-09-14. This supersedes the event/render/API and natural Explore gaps
in the earlier checkpoints. The [scaffold clearance checkpoint](FOLLOWUP_CLEARANCE.md)
still describes the construction adapter. This is a source port with explicit
1.7/Forge boundaries, not binary compatibility with a modern Baritone client.

## Completed implementation

- Restored the pinned source path/goal renderer, selection renderer and event-bus
  interface. Native 1.7 tessellation, selection bounds and beacon rendering adapt
  the graphics boundary; the source path geometry, fades and goal traversal remain.
- Connected world/chunk/population/block, queued gameplay packet, movement/jump
  rotation, sprint, chat, tab completion, interaction, death and render events.
  Listeners retain registration order and snapshot dispatch semantics.
- Exposed the source process, cache, selection, event and control-manager getters
  through the Java API. Direct process activation now acquires the same control
  lease and protection rules as bridge jobs. Completion, GUI takeover, death,
  world changes and explicit stops release it. Explicit stops also cancel a
  process activated before its first tick, closing a pending-work race.
- Added native local commands for Java goals and selections, plus structured
  mining/building/follow/process/settings/cache commands. The bridge still works
  independently of Baritone.
- Enabled source free-look through native rotation/packet hooks while preserving
  visible aim as the default. Releasing camera ownership retains movement keys;
  a stale lease cannot clear a later owner's look target.
- Repaired synthetic GUI keyboard dispatch under LWJGL3ify. Its late mixin
  cancels the legacy keyboard body before the earlier synthetic getter hooks.
  The call-site adapter now dispatches synthetic events to the actual screen's
  virtual `keyTyped` callback; physical input keeps the native path. This fixes
  typing and Enter without machine-specific GUI code.
- Allowed source settings and cache administration while server time is paused.
  Settings mutation still requires an idle engine.
- Made speculative placement query the native crouching state as well as its
  projected eye position. GregTech expands pipe targeting bounds while holding
  a pipe and crouching; predicting only the eye height caused a three-tick loop
  that repeatedly cancelled valid approach paths. The query scope restores input
  immediately and reevaluates GT's own targeting rule, restoring its cached flags
  afterward. It copies no item/tool lists and sends no input or inventory packet.
- Exposed the last path calculation's type, elapsed time, path length and error
  in status. Worker cleanup is conditional on calculation identity, including
  exceptional exits, so an old worker cannot clear a newer search.

## Native boundaries

The packet hook runs **after the existing clock packet-admission gate**. The
event transformer preserves `ClockPackets.dispatch` and instruments its admitted
native dispatch; it does not bypass pause or inventory transaction handling.
Only queued client gameplay packets and client-thread outbound packets are
forwarded. Netty priority traffic is not forwarded to game-state listeners.

GTNH PlayerAPI moves the native player update body into `localOnLivingUpdate`;
sprint instrumentation targets that body. ArchaicFix can add an early world-load
return after transformation. A pending world event completes once the actual
client world changes, at the next client tick/render boundary if necessary.
Player-update/aim phases remain Forge START/END, not the exact modern packet-send
injection timing. Forge-local commands bypass the server-chat hook. Synthetic GUI
keys dispatch `keyTyped`; additional behavior in a custom `handleKeyboardInput`
override is not automatically reproduced.

## Validation

The full build passes **183 tests**, with no failures or skips. They include ASM
verification against the Forge-patched 1.7 classes, PlayerAPI body selection,
clock-transformer composition, ordered event dispatch and control ownership.
All **162 pinned source imports** verify against upstream v1.2.19
`d9cb2d91a06501c5bcba2181509d0df80361f413`. Import verification establishes
provenance, not text identity after adaptation. Eleven Python profile tests pass
and all 52 model tools load. The retained DJ2 `src` tree is unchanged.

The installed client passes all **16 native event/API checks**: direct source
goal movement, control acquisition/release/cancellation, free-look camera yaw,
render/motion/sprint events, send/receive phases, world/chunk events and paused
administration. A captured frame was visually checked for the path and selection.

The final sequential installed-client batch passes inventory **21**, machine and
pipe configuration **76**, movement geometry **65**, and autonomous beam/scaffold
clearance **7** checks. The formerly stalled five-block machine layout finishes
placement in **70 ticks**, followed by its native configuration and fluid-total
checks. The beam verifies temporary-support removal and preservation of both the
finished beam and preexisting blocks. Each fixture restores its journalled world
and player state. `final-regressions.json` records the batch and full log paths.

Natural exploration travelled **141 blocks**, selected an uncached frontier,
observed it become cached without a harness repack, and retained the observed
native block ID/metadata after save/reload. **15 of 17 checks passed**. The full
run failed: inventory gained two dropped rotten flesh and a Brutish Zombie killed
the developer player during return. This establishes bounded frontier/cache
behavior, not a successful survival round trip. The client was respawned. The
script now retains pickup evidence, checks original item totals and supports
damage-triggered pause; those test changes have not had another live run.

Evidence is stored locally under `gtnh/.runtime/evidence/`, including
`reference-events-api-smoke.json`, its PNG, and
`reference-natural-explore-cache-smoke.json`. These runtime artifacts are ignored
by Git; the reproducible scripts are checked in.

## Remaining scope

The port uses the original path search/execution and construction scheduling;
Modbench's strict blueprint contracts, explicit clearance phases, protection,
acknowledged inventory actions and lifetime wrappers remain intentional adapters.
The full upstream CLI/alias framework, `GuiClick`, multiple independent bots and
modern binary API compatibility are not implemented. Flight remains deferred.

Inherited source rules still reject mining an obsidian target with flowing water
directly above it. Deep snow and ordinary source `ItemDoor` placement retain the
previously recorded limitations; native interaction procedures remain available.
Automatic fluid-container fall recovery is still disabled pending a generic
provider. These are not closed by the event work or by passing dry fixtures.
Broader modded-fluid recovery and long survival execution remain acceptance work.
