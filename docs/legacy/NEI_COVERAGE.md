# Native NEI inspection

Modbench queries the installed GTNH NEI catalogue, parser and registered recipe
handlers. Item discovery uses the running catalogue, not a jar scan or a static
item-name table. The validated environment is GTNH 2.8.4 with NEI 2.8.44-GTNH and
GregTech 5.09.51.482.

## Model workflow

1. Use `mb_nei_status`, then `mb_search(query=...)` or exact `mod`, `ore`, `id`,
   `meta` filters. Native query syntax honors the pack's configured search providers.
   Follow `nextOffset`; retain the returned `id`, `meta` and SNBT `nbt` together.
2. Use `mb_item` for native item tooltips, fluid contents and ItemBlock placement
   hints (`placement.blockId`, `placement.initialBlockMeta`). GregTech's native
   prototype registry maps machine/pipe item variants to their distinct world
   block metadata. Keep the item variant for material selection and verification;
   orientation and configuration still require native callbacks/interactions.
   Use `mb_fluids` for
   canonical fluid names; pass a returned ID to `mb_recipes(fluid=...)`.
3. Query `mb_recipes` for production or `mode="uses"` for consumption. The default
   `limit=0` returns every matching category and its count, plus known GT base EU/t
   ranges, with no arbitrarily selected recipe. NEI order is not progression order.
   Reuse the complete `handlerKey` for an exact category: many different GT machine
   categories share one handler ID. Set `handler` to that key and `limit=5` for
   compact comparable options, paging through all variants with `nextOffset`.
   Pick using observed machines, power, ingredients and research. The harness does
   not declare a route craftable or choose progression on the model's behalf.
4. Fetch `detail="full", handler=handlerKey, index=nativeIndex, limit=1` for the
   exact chosen option. Compact previews omit NBT and abbreviate alternatives;
   they are not actionable item identities. Read inputs, result, other stacks,
   catalysts and alternatives. Every position
   preserves variant counts and supports `alternatives_offset/alternatives_limit`;
   follow `nextAlternativesOffset` instead of assuming the first variant is unique.
5. Open `mb_recipe_view` with the same target/mode and returned handlerKey/index.
   It returns the actual native recipe page as an image, with the visible indices
   and logical GUI dimensions. It cancels movement and changes the screen.
6. Use `mb_recipe_inspect(x,y,scroll=0)` for native item/handler tooltip text,
   available hotkeys and an image. Coordinates are logical GUI pixels, not image
   pixels; scale using the returned dimensions. `scroll=-1` moves down and `+1`
   up using native mouse-wheel handling, including custom diagram handlers.
   Normal `mb_gui` key/click operations remain available. Close the GUI before
   moving again.

`mb_recipe_handlers` inventories all registered crafting and usage categories,
including informational displays. Registration alone does not prove that a given
item has a recipe in a category. Recipe and view lookup retain NEI's native matching
and restrictions; they do not manufacture missing matches.

## Representation and boundaries

| Display/data | Access |
| --- | --- |
| Vanilla and modded crafting, processing and uses | Native handler query; input/result/other positioned stacks; exact category and recipe paging |
| Metadata and NBT variants, including bee genomes | Exact item identities and native item tooltips; preserve SNBT across calls |
| Ore-dictionary substitutions | Full alternative counts and paginated alternatives at every position |
| Machine catalysts and retained tools/configuration circuits | Native catalyst list, positions and exact stack counts; a GT input count of zero is retained rather than consumed |
| Fluids | Canonical Forge fluid registry, container/display-stack decoding, native recipe queries, exact GT fluid quantities |
| GT processing and random outputs | Base duration/EU per tick, resolved output probabilities out of 10000, inputs/outputs, fluid inputs/outputs |
| GT special requirements | Raw metadata with native key descriptions, specialValue, specialItems, NEI descriptions, enabled/fakeRecipe/nbtSensitive/needsEmptyOutput flags, native page and tooltips |
| Thaumcraft aspects, research and infusion | Native crafting/usage handler layout, actual native screen, handler tooltip callbacks |
| Forestry and genetics | Exact genome NBT, breeding/produce handlers, native tree/mutation graphics and tooltips |
| Custom diagrams, distributions, mob/loot information | Native handler layout where supplied, actual diagrams and native scrolling/hover inspection |

GT power/time values are base recipe values before machine overclocking. Some GT
entries are informational `fakeRecipe` records, not executable recipes. Native
handler layouts do not by themselves specify every machine or progression rule.
A custom object in specialItems is explicitly marked `structured:false` if it
cannot be represented as an item, fluid, scalar or collection.

There is no universal structured API for everything a handler draws. `drawExtras`
can render arbitrary text, research symbols, bars or graphics. Each result reports
its structured coverage; an empty ingredient list or tooltip is not proof that a
recipe has no requirements. Use the native view. Modifier-dependent behavior and
arbitrary third-party widgets are not exhaustively validated.

Native recipe pages use NEI's own page manager and GUI updates. This matters for
oversized layouts and diagrams without ingredient-derived recipe IDs. While time
is paused, only the recipe GUI presentation lifecycle is serviced: NEI inspection
does not release a world/player/Forge simulation tick. These tools also work in
real time; opening a page does not automatically pause server production.

Search work is sliced on the client thread with cancellation/deadline checks and
bounded queues/caches. A catalogue reload invalidates cached searches and recipes.
Recipe handler work itself uses NEI's native dispatcher; some mod callbacks can
still be expensive. Tool failure is surfaced as a bridge error, not an empty match.

## Validation

Run `python tools/gtnh/nei_smoke.py` with a connected GTNH client/server. It performs
read-only catalogue/recipe queries and native GUI operations, with the server paused.
Evidence and screenshots are saved under `gtnh/.runtime/evidence/nei/`. It uses real
handler data and checks that server dimension times do not advance. GUI screenshots
are captured automatically and representative pages are visually reviewed; image
capture alone is not an automated proof of the correctness of every drawn label.

The recorded catalogue has 56,053 item entries and 335 registered categories.
This is inventory coverage, not a claim that every recipe in all 335 categories
has passed a visual or semantic test. See [validation](VALIDATION.md) for the actual
representative cases and current acceptance counts.

Implementation is pinned to the installed native APIs; upgrading NEI needs a new
live acceptance run. Primary API references:
[GuiRecipe](https://github.com/GTNewHorizons/NotEnoughItems/blob/2.8.44-GTNH/src/main/java/codechicken/nei/recipe/GuiRecipe.java)
and [RecipePageManager](https://github.com/GTNewHorizons/NotEnoughItems/blob/2.8.44-GTNH/src/main/java/codechicken/nei/recipe/RecipePageManager.java).
