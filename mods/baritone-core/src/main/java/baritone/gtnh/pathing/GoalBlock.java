/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** A goal at one exact block coordinate. */
public record GoalBlock(int x, int y, int z) implements Goal {
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, y - this.y, z - this.z);
    }

    /** Upstream GoalBlock formula: vertical movement plus diagonal-plus-straight X/Z. */
    public static double calculate(double xDiff, int yDiff, double zDiff) {
        return Heuristics.yLevelCost(0, yDiff) + GoalXZ.calculate(xDiff, zDiff);
    }
}
