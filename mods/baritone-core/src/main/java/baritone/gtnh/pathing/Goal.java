/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** A destination predicate and an admissible cost estimate for A*. */
public interface Goal {
    boolean isInGoal(int x, int y, int z);

    double heuristic(int x, int y, int z);
}
