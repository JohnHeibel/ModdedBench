/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** A legal edge in a world snapshot. Cost is measured in the same units as a goal heuristic. */
public record Move(BlockPos destination, double cost, String kind) {
}
