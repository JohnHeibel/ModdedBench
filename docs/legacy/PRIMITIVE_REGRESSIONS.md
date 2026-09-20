# EBF primitive follow-up

The fixes live in the Java mods. Python only runs the regression scenario.

- **Tallgrass:** `MiningTools` now treats positive infinite native break strength
  as a one-tick break. NaN, zero and negative strengths remain ineligible.
- **Attack holds:** `act.input` and attack-key `keys.press` lock edits to the
  initial block and metadata. Controller callbacks cannot continue into another
  block, even in the same tick. A changed target releases input early.
  `allowRetarget:true` explicitly restores continuous attacking. A mod tool's
  own area effects remain native behavior.
- **Eating:** native air-use events run with the use key held. Receipts expose
  the native duration, budget, hunger and invulnerability. Over-budget food fails
  immediately; the default budget is 400 ticks. Server consumption completion
  releases use before another right click and provides an acknowledgment.
  Consumption checks allow the normal settling period for updates.

Replay of the player's food history gave baked potato a native use duration of
32,767 ticks through Spice of Life. That penalty is respected, not bypassed.
The interaction fixture also incorrectly enabled invulnerability, which vanilla
rejects for eating; it now uses survival capabilities. The old trial's generic
error did not contain enough state to establish its exact refusal cause.

## Verification

`gtnh/gradlew.bat build` passes all **189 Java tests**. The build also explicitly
declares the control-jar dependencies needed by the Forge test loader.

`python tools/gtnh/primitive_regression_smoke.py` passes **13 live checks** in
GTNH 2.8.4. These verify immediate food-budget rejection, server inventory and
hunger changes, native completion acknowledgment, no accidental chest activation,
released controls, one-tick tallgrass estimates, preservation of the block behind
a long attack, explicit retargeting, and source-builder grass clearing/placement.

The live checks used a disposable world copy with journaled fixtures. Evidence:
`.runtime/evidence/primitive-regressions.json`. The EBF and the user's exploration
state in the original world were not used as test targets.

The fixture script requires a survival player and a server started with
`--dev-fixtures`; normal runtime startup leaves privileged fixtures disabled.
