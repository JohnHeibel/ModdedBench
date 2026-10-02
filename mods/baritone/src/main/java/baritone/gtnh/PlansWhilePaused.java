// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

/** A job whose first tick starts with work that needs no game time (a path search, a first scan of its bounds). An action
 *  that resumes a paused world has the game thread do that work while the world is still paused, and its first tick waits
 *  for it, so the time it takes is not game time the player spends standing still. */
interface PlansWhilePaused {
    /** Whether such work is left before the job's first tick. Cheap: asked every paused frame. */
    boolean planningWhilePaused();
    /** Game thread, world paused, before the first tick: one frame of that work. Never moves, presses, sends or counts a tick. */
    void planWhilePaused();
}
