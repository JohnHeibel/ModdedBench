// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.utils.pathing.MutableMoveResult;

/**
 * One kind of step the route search may take from a cell. The search asks every move, at every cell it opens, where the
 * step ends and what it costs (apply); the route that wins is then walked by the Movement each of its steps makes
 * (apply0). The walker's own moves are the enum baritone.pathing.movement.Moves; MoveRegistry adds to them.
 *
 * A move that always ends at the same offset says so (xOffset, yOffset, zOffset). One that ends where the world lets it
 * (a jump across a gap, a fall, a glide) is dynamic in those axes: its offsets then only say which way it goes, and
 * which chunk must be loaded to ask it.
 */
public interface Move {
    int xOffset();
    int yOffset();
    int zOffset();
    boolean dynamicXZ();
    boolean dynamicY();
    /**
     * The step from this cell: where it ends and its cost in ticks, written into result. A step that cannot be taken
     * here costs ActionCosts.COST_INF. Called on the search thread, for every cell opened: it reads the world only
     * through context, and gives up early where it plainly does not apply.
     */
    void apply(CalculationContext context,int x,int y,int z,MutableMoveResult result);
    /**
     * The movement that walks this step from src, or null where it cannot be taken. Its getDest() is where apply said the
     * step ends, and its calculateCost(context) is what apply said it costs.
     */
    Movement apply0(CalculationContext context,BetterBlockPos src);
}
