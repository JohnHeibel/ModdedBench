/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** A goal with a fixed X/Z coordinate and any Y coordinate. */
public record GoalXZ(int x, int z) implements Goal {
    private static final double SQRT_2 = Math.sqrt(2.0D);

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, z - this.z);
    }

    /** Upstream diagonal-plus-straight estimate, calibrated in movement ticks. */
    public static double calculate(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        return (diagonal * SQRT_2 + straight) * Heuristics.COST_HEURISTIC;
    }
}
