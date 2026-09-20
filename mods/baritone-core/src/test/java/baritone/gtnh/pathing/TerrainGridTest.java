// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerrainGridTest {
    @Test
    public void pathsAroundBarrier() {
        GridBuilder grid = new GridBuilder(0, 0, 0, 3, 5, 3).flatFloor(0);
        grid.set(1, 1, 1, TerrainGrid.BLOCKED);
        TerrainGrid view = grid.build();
        BlockPos start = new BlockPos(0, 1, 1);
        BlockPos goal = new BlockPos(2, 1, 1);

        SearchResult result = new AStarPathFinder(view, start, new GoalBlock(goal.x(), goal.y(), goal.z()))
                .calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.GOAL, result.status());
        assertEquals(goal, result.path().get(result.path().size() - 1));
        assertFalse(result.path().contains(new BlockPos(1, 1, 1)));
        assertTrue(result.path().size() > 3);
    }

    @Test
    public void producesOneBlockAscendAndDescendWithActionCosts() {
        GridBuilder ascendGrid = new GridBuilder(0, 0, 0, 2, 6, 1).fill(TerrainGrid.CLEAR);
        ascendGrid.set(0, 0, 0, TerrainGrid.SUPPORT);
        ascendGrid.set(1, 1, 0, TerrainGrid.SUPPORT);
        TerrainGrid ascend = ascendGrid.build();

        Move up = onlyMoveTo(ascend.moves(new BlockPos(0, 1, 0)), new BlockPos(1, 2, 0));
        assertEquals("ASCEND", up.kind());
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST, up.cost(), 0.0D);

        GridBuilder descendGrid = new GridBuilder(0, 0, 0, 2, 6, 1).fill(TerrainGrid.CLEAR);
        descendGrid.set(0, 1, 0, TerrainGrid.SUPPORT);
        descendGrid.set(1, 0, 0, TerrainGrid.SUPPORT);
        TerrainGrid descend = descendGrid.build();

        Move down = onlyMoveTo(descend.moves(new BlockPos(0, 2, 0)), new BlockPos(1, 1, 0));
        assertEquals("DESCEND", down.kind());
        assertEquals(ActionCosts.WALK_OFF_BLOCK_COST + ActionCosts.FALL_N_BLOCKS_COST[1]
                + ActionCosts.CENTER_AFTER_FALL_COST, down.cost(), 0.0D);
    }

    @Test
    public void rejectsGapsOverThreeBlocksHazardsAndUnknownCells() {
        GridBuilder longDrop = new GridBuilder(0, 0, 0, 2, 8, 1).fill(TerrainGrid.CLEAR);
        longDrop.set(0, 4, 0, TerrainGrid.SUPPORT);
        longDrop.set(1, 0, 0, TerrainGrid.SUPPORT);
        assertTrue(longDrop.build().moves(new BlockPos(0, 5, 0)).isEmpty());

        GridBuilder hazard = new GridBuilder(0, 0, 0, 2, 4, 1).flatFloor(0);
        hazard.set(1, 1, 0, TerrainGrid.HAZARD);
        assertTrue(hazard.build().moves(new BlockPos(0, 1, 0)).isEmpty());

        GridBuilder unknown = new GridBuilder(0, 0, 0, 2, 4, 1).flatFloor(0);
        unknown.set(1, 1, 0, TerrainGrid.UNKNOWN);
        assertTrue(unknown.build().moves(new BlockPos(0, 1, 0)).isEmpty());
    }

    @Test
    public void blockedHeadroomRejectsAscend() {
        GridBuilder grid = new GridBuilder(0, 0, 0, 2, 6, 1).fill(TerrainGrid.CLEAR);
        grid.set(0, 0, 0, TerrainGrid.SUPPORT);
        grid.set(1, 1, 0, TerrainGrid.SUPPORT);
        grid.set(0, 3, 0, TerrainGrid.BLOCKED);

        List<Move> moves = grid.build().moves(new BlockPos(0, 1, 0));

        assertFalse(moves.stream().anyMatch(move -> move.kind().equals("ASCEND")));
    }

    @Test
    public void entersRaisedTwoHighDoorwayWithoutBreakingLintel() {
        GridBuilder grid = new GridBuilder(0, 0, 0, 3, 6, 1).flatFloor(0);
        grid.set(1, 1, 0, TerrainGrid.SUPPORT); // raised threshold
        grid.set(2, 1, 0, TerrainGrid.SUPPORT); // interior floor
        grid.set(1, 4, 0, TerrainGrid.SUPPORT); // two-high doorway lintel
        TerrainGrid terrain = grid.build();
        SearchResult result = new AStarPathFinder(terrain, new BlockPos(0, 1, 0), new GoalBlock(2, 2, 0))
                .calculate(1_000, 1_000, 100, () -> false);
        assertEquals(SearchResult.Status.GOAL, result.status());
        assertEquals(List.of("ASCEND", "WALK"), result.moves());

        // A real obstruction in the doorway body must still prevent entry.
        grid.set(1, 3, 0, TerrainGrid.SUPPORT);
        assertTrue(grid.build().moves(new BlockPos(0, 1, 0)).isEmpty());
    }

    @Test
    public void goalBlockHeuristicIsBelowCardinalMoveCosts() {
        GoalBlock horizontalGoal = new GoalBlock(1, 1, 0);
        assertTrue(horizontalGoal.heuristic(0, 1, 0) <= ActionCosts.WALK_ONE_BLOCK_COST);

        GoalBlock ascendGoal = new GoalBlock(1, 2, 0);
        assertTrue(ascendGoal.heuristic(0, 1, 0)
                <= ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST);

        GoalBlock descendGoal = new GoalBlock(1, 1, 0);
        assertTrue(descendGoal.heuristic(0, 2, 0)
                <= ActionCosts.WALK_OFF_BLOCK_COST + ActionCosts.FALL_N_BLOCKS_COST[1]
                + ActionCosts.CENTER_AFTER_FALL_COST);
    }

    private static Move onlyMoveTo(List<Move> moves, BlockPos destination) {
        return moves.stream().filter(move -> move.destination().equals(destination)).findFirst()
                .orElseThrow(() -> new AssertionError("Expected move to " + destination + ", got " + moves));
    }

    private static final class GridBuilder {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int width;
        private final int height;
        private final int depth;
        private final byte[] cells;

        private GridBuilder(int minX, int minY, int minZ, int width, int height, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.cells = new byte[width * height * depth];
        }

        private GridBuilder fill(byte value) {
            Arrays.fill(cells, value);
            return this;
        }

        private GridBuilder flatFloor(int y) {
            fill(TerrainGrid.CLEAR);
            for (int x = minX; x < minX + width; x++) {
                for (int z = minZ; z < minZ + depth; z++) {
                    set(x, y, z, TerrainGrid.SUPPORT);
                }
            }
            return this;
        }

        private void set(int x, int y, int z, byte value) {
            int relativeX = x - minX;
            int relativeY = y - minY;
            int relativeZ = z - minZ;
            cells[(relativeX * depth + relativeZ) * height + relativeY] = value;
        }

        private TerrainGrid build() {
            return new TerrainGrid(minX, minY, minZ, width, height, depth, cells);
        }
    }
}
