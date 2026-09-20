/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

import java.util.List;

/** Immutable outcome of one non-reusable A* calculation. */
public record SearchResult(Status status, List<BlockPos> path, List<String> moves,
                           double cost, int nodes, long elapsedMs) {
    public SearchResult {
        path = List.copyOf(path);
        moves = List.copyOf(moves);
    }

    public enum Status {
        GOAL,
        PARTIAL,
        UNREACHABLE,
        CANCELLED,
        TIMEOUT
    }
}
