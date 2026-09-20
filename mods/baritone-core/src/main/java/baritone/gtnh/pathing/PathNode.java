/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** A node in the path, containing the cost and steps to get to it. */
final class PathNode {
    final BlockPos position;
    final double estimatedCostToGoal;
    double cost;
    double combinedCost;
    PathNode previous;
    String previousMoveKind;
    int heapPosition;

    PathNode(BlockPos position, Goal goal) {
        this.position = position;
        this.previous = null;
        this.previousMoveKind = null;
        this.cost = AStarPathFinder.COST_INF;
        this.estimatedCostToGoal = goal.heuristic(position.x(), position.y(), position.z());
        if (!Double.isFinite(estimatedCostToGoal)) {
            throw new IllegalArgumentException("Goal calculated a non-finite heuristic at " + position);
        }
        this.heapPosition = -1;
    }

    boolean isOpen() {
        return heapPosition != -1;
    }
}
