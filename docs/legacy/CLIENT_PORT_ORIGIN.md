# Inventory and GUI port origin

The generalized UI port reuses this repository's DJ2 Modbench implementation
under the existing LGPL-3.0-or-later license. In particular:

- `UiWidgets` derives from the widget inspection in
  `src/main/java/baritone/moddedbench/bridge/InvMethods.java`.
- The `clock-hooks` module's `GuiInputTransformer` adapts the DJ2 input call-site
  transformer (`src/launch/java/baritone/launch/LwjglInputTransformer.java`),
  with 1.7.10 names, lwjgl3ify input owners and native keyboard events.
- `GuiOperations`, `UiInput` and `UiHooks` bring forward the DJ2 event-delivery,
  modifier, cursor-recovery and exact-count-transfer approach using the standalone
  shared control owner, GTNH container identities and transaction receipts.

The GTNH client has no dependency on Baritone. Native Minecraft/Forge containers
and mod GUI handlers remain responsible for their normal behavior and packets.
The optional ModularUI/AE2 observations use the installed mods' native APIs.
See [inventory contracts and validation limits](../INVENTORY_UI.md).
