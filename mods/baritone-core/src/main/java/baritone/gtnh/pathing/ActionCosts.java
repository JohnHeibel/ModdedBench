/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/**
 * Movement-time constants copied unchanged from upstream
 * {@code baritone.api.pathing.movement.ActionCosts}.
 */
public interface ActionCosts {
    double WALK_ONE_BLOCK_COST = 20 / 4.317;
    double WALK_ONE_IN_WATER_COST = 20 / 2.2;
    double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2;
    double LADDER_UP_ONE_COST = 20 / 2.35;
    double LADDER_DOWN_ONE_COST = 20 / 3.0;
    double SNEAK_ONE_BLOCK_COST = 20 / 1.3;
    double SPRINT_ONE_BLOCK_COST = 20 / 5.612;
    double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST;
    double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;
    double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST - WALK_OFF_BLOCK_COST;
    double COST_INF = 1000000;
    double[] FALL_N_BLOCKS_COST = generateFallNBlocksCost();
    double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);
    double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);
    double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST;

    static double[] generateFallNBlocksCost() {
        double[] costs = new double[257];
        for (int i = 0; i < 257; i++) {
            costs[i] = distanceToTicks(i);
        }
        return costs;
    }

    static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    static double oldFormula(double ticks) {
        return -3.92 * (99 - 49.5 * (Math.pow(0.98, ticks) + 1) - ticks);
    }

    static double distanceToTicks(double distance) {
        if (distance == 0) {
            return 0;
        }
        double tmpDistance = distance;
        int tickCount = 0;
        while (true) {
            double fallDistance = velocity(tickCount);
            if (tmpDistance <= fallDistance) {
                return tickCount + tmpDistance / fallDistance;
            }
            tmpDistance -= fallDistance;
            tickCount++;
        }
    }
}
