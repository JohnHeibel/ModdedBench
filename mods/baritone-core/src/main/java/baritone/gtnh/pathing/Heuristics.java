/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** Constants and vertical estimate copied from upstream ActionCosts and GoalYLevel. */
final class Heuristics {
    /** Upstream Settings#costHeuristic default. Its value is a setting, not an ActionCosts constant. */
    static final double COST_HEURISTIC = 3.563D;

    private Heuristics() {
    }

    static double yLevelCost(int goalY, int currentY) {
        if (currentY > goalY) {
            return ActionCosts.FALL_N_BLOCKS_COST[2] / 2.0D * (currentY - goalY);
        }
        if (currentY < goalY) {
            return (goalY - currentY) * ActionCosts.JUMP_ONE_BLOCK_COST;
        }
        return 0.0D;
    }

}
