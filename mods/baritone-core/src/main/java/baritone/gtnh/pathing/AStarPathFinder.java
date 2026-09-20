/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Game-independent mechanical adaptation of Baritone's A* search loop. */
public final class AStarPathFinder {
    static final double COST_INF = ActionCosts.COST_INF;
    private static final double[] COEFFICIENTS = {1.5D, 2D, 2.5D, 3D, 4D, 5D, 10D};
    private static final double MIN_DIST_PATH = 5.0D;
    private static final double MIN_IMPROVEMENT = 0.01D;
    private static final int TIME_CHECK_INTERVAL = 1 << 6;

    private final WorldView world;
    private final BlockPos start;
    private final Goal goal;

    public AStarPathFinder(WorldView world, BlockPos start, Goal goal) {
        this.world = Objects.requireNonNull(world, "world");
        this.start = Objects.requireNonNull(start, "start");
        this.goal = Objects.requireNonNull(goal, "goal");
    }

    /**
     * Searches one immutable world snapshot. A node-budget stop is reported as TIMEOUT; its path, if any,
     * is still the same upstream best-so-far partial path.
     */
    public SearchResult calculate(long primaryTimeoutMs, long failureTimeoutMs, int maxNodes,
                                  BooleanSupplier cancelled) {
        if (primaryTimeoutMs < 0 || failureTimeoutMs < 0) {
            throw new IllegalArgumentException("Timeouts must be non-negative");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
        Objects.requireNonNull(cancelled, "cancelled");
        long startTime = System.nanoTime();
        if (cancelled.getAsBoolean()) return empty(SearchResult.Status.CANCELLED,0,startTime);
        if (!world.isLoaded(start.x(),start.y(),start.z())) return empty(SearchResult.Status.UNREACHABLE,0,startTime);
        Map<BlockPos, PathNode> nodes = new HashMap<>();
        PathNode startNode = nodeAt(nodes, start);
        startNode.cost = 0.0D;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);

        PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }

        long primaryDeadline = deadline(startTime, primaryTimeoutMs);
        long failureDeadline = deadline(startTime, failureTimeoutMs);
        boolean failing = true;
        boolean timedOut = false;
        int considered = 0;

        while (!openSet.isEmpty() && !cancelled.getAsBoolean()) {
            if (considered >= maxNodes) {
                timedOut = true;
                break;
            }
            if ((considered & (TIME_CHECK_INTERVAL - 1)) == 0) {
                long now = System.nanoTime();
                if (now >= failureDeadline || (!failing && now >= primaryDeadline)) {
                    timedOut = true;
                    break;
                }
            }

            PathNode current = openSet.removeLowest();
            considered++;
            if (goal.isInGoal(current.position.x(), current.position.y(), current.position.z())) {
                if (cancelled.getAsBoolean()) return empty(SearchResult.Status.CANCELLED,considered,startTime);
                return result(SearchResult.Status.GOAL, current, considered, startTime);
            }

            List<Move> moves = Objects.requireNonNull(world.moves(current.position), "WorldView.moves result");
            for (Move move : moves) {
                if (cancelled.getAsBoolean()) {
                    return empty(SearchResult.Status.CANCELLED, considered, startTime);
                }
                validateMove(move);
                BlockPos destination = move.destination();
                if (!world.isLoaded(destination.x(), destination.y(), destination.z())) {
                    continue;
                }
                if (move.cost() >= COST_INF) {
                    continue;
                }
                PathNode neighbor = nodeAt(nodes, destination);
                double tentativeCost = current.cost + move.cost();
                if (neighbor.cost - tentativeCost > MIN_IMPROVEMENT) {
                    neighbor.previous = current;
                    neighbor.previousMoveKind = move.kind();
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > MIN_IMPROVEMENT) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && distanceFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        if (cancelled.getAsBoolean()) {
            return empty(SearchResult.Status.CANCELLED, considered, startTime);
        }
        PathNode partial = bestPartial(bestSoFar);
        if (partial != null) {
            return result(timedOut ? SearchResult.Status.TIMEOUT : SearchResult.Status.PARTIAL,
                    partial, considered, startTime);
        }
        return empty(timedOut ? SearchResult.Status.TIMEOUT : SearchResult.Status.UNREACHABLE,
                considered, startTime);
    }

    private PathNode nodeAt(Map<BlockPos, PathNode> nodes, BlockPos position) {
        PathNode node = nodes.get(position);
        if (node == null) {
            node = new PathNode(position, goal);
            nodes.put(position, node);
        }
        return node;
    }

    private void validateMove(Move move) {
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(move.destination(), "move.destination");
        Objects.requireNonNull(move.kind(), "move.kind");
        if (!Double.isFinite(move.cost()) || move.cost() <= 0.0D) {
            throw new IllegalArgumentException("Move has non-positive or non-finite cost: " + move);
        }
    }

    private PathNode bestPartial(PathNode[] bestSoFar) {
        for (PathNode candidate : bestSoFar) {
            if (candidate != null && distanceFromStartSq(candidate) > MIN_DIST_PATH * MIN_DIST_PATH) {
                return candidate;
            }
        }
        return null;
    }

    private double distanceFromStartSq(PathNode node) {
        long x = (long) node.position.x() - start.x();
        long y = (long) node.position.y() - start.y();
        long z = (long) node.position.z() - start.z();
        return x * x + y * y + z * z;
    }

    private static long deadline(long startTime, long timeoutMs) {
        long duration = timeoutMs > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : timeoutMs * 1_000_000L;
        return startTime > Long.MAX_VALUE - duration ? Long.MAX_VALUE : startTime + duration;
    }

    private SearchResult result(SearchResult.Status status, PathNode end, int considered, long startTime) {
        List<BlockPos> reversePositions = new ArrayList<>();
        List<String> reverseMoves = new ArrayList<>();
        for (PathNode current = end; current != null; current = current.previous) {
            reversePositions.add(current.position);
            if (current.previousMoveKind != null) {
                reverseMoves.add(current.previousMoveKind);
            }
        }
        Collections.reverse(reversePositions);
        Collections.reverse(reverseMoves);
        return new SearchResult(status, reversePositions, reverseMoves, end.cost, considered, elapsedMs(startTime));
    }

    private SearchResult empty(SearchResult.Status status, int considered, long startTime) {
        return new SearchResult(status, List.of(), List.of(), Double.POSITIVE_INFINITY, considered, elapsedMs(startTime));
    }

    private static long elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000L;
    }
}
