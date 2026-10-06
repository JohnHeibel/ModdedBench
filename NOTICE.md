# Notices

ModdedBench is licensed under the MIT License (see `LICENSE`), with the exceptions listed here.
Each source file names its licence in an `SPDX-License-Identifier` line.

## Not covered by the MIT licence

- **Modatone**, the git submodule at `mods/baritone`: a port of
  [Baritone](https://github.com/cabaletta/baritone) to Minecraft 1.7.10 / GT New Horizons. It is a
  separate repository under the GNU Lesser General Public License v3.0 or later, with its own
  `LICENSE`, `NOTICE.md` (upstream revision, the list of upstream files, the statement of
  modifications, the fastutil notice) and `UPSTREAM_SOURCES.json`. Nothing in ModdedBench imports
  a Baritone class; the port reaches the rest through `dev.modbench.api` only. The jar built from
  it, `modbench-baritone`, is LGPL-3.0-or-later and carries its licence texts.
- **Four files in `mods/core`** that are derived from Baritone's event and input hooks and stay
  LGPL-3.0-or-later (`LICENSES/LGPL-3.0.txt`, `LICENSES/GPL-3.0.txt`), as their headers say:
  `src/main/java/dev/modbench/hooks/ActionTransformer.java`, `EventTransformer.java`, and their
  tests `src/test/java/dev/modbench/hooks/ActionTransformerTest.java`, `EventTransformerTest.java`.
  The two transformers are compiled into `modbench-core`.

## Third-party files in this repository

- **Monocraft** typeface (`harness/console/fonts/Monocraft.ttf`, `Monocraft-Bold.ttf`), copyright
  (c) 2022 Idrees Hassan, SIL Open Font License 1.1: `harness/console/fonts/LICENSE-Monocraft.txt`.
  Used by the stream overlay.
- **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`), Apache
  License 2.0: `LICENSES/Apache-2.0.txt`.

## Not distributed

Minecraft, Forge, GregTech and the GT New Horizons modpack are not distributed with this repository.
