// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import java.util.function.Predicate;
import org.junit.Test;
import static org.junit.Assert.*;

/** Route-level coverage for costed construction edges, independent of Minecraft adapters. */
public class ConstructionRoutingTest {
    private static final int WIDTH = 9, HEIGHT = 9, DEPTH = 5;
    private final byte[] cells = new byte[WIDTH * HEIGHT * DEPTH];
    private final Map<BlockPos,Double> breakCosts = new HashMap<>();

    private int index(int x, int y, int z) { return (x * DEPTH + z) * HEIGHT + y + 1; }
    private void set(int x, int y, int z, byte cell) { cells[index(x, y, z)] = cell; }
    private void floor() {
        Arrays.fill(cells, TerrainGrid.CLEAR);
        for (int x = 0; x < WIDTH; x++) for (int z = 0; z < DEPTH; z++) set(x, 0, z, TerrainGrid.SUPPORT);
        breakCosts.clear();
    }
    private TerrainGrid terrain() { return new TerrainGrid(0, -1, 0, WIDTH, HEIGHT, DEPTH, cells); }
    private static WorkWorld.Construction construction(double placement, double breakMultiplier) {
        return new WorkWorld.Construction() {
            @Override public double placementCost(BlockPos p) { return placement; }
            @Override public double breakMultiplier(BlockPos p) { return breakMultiplier; }
        };
    }
    private WorkWorld world(int placements, Predicate<BlockPos> allowed, WorkWorld.Construction construction) {
        return new WorkWorld(terrain(), breakCosts, placements, allowed, construction);
    }
    private SearchResult route(WorkWorld world, BlockPos goal) {
        return new AStarPathFinder(world, new BlockPos(1, 1, 2), new GoalBlock(goal.x(), goal.y(), goal.z()))
                .calculate(2_000, 2_000, 20_000, () -> false);
    }

    @Test public void zeroCostStructureSupportBeatsTemporaryExpensiveDetour() {
        floor(); set(4, 0, 2, TerrainGrid.CLEAR);
        SearchResult structure = route(world(8, p -> true, construction(0, 1)), new BlockPos(7, 1, 2));
        assertEquals(SearchResult.Status.GOAL, structure.status());
        assertTrue("planned support crossing", structure.path().contains(new BlockPos(4, 1, 2)));
        assertTrue(structure.moves().contains("WORK_WALK"));

        SearchResult temporary = route(world(8, p -> true, construction(100, 1)), new BlockPos(7, 1, 2));
        assertEquals(SearchResult.Status.GOAL, temporary.status());
        assertFalse("expensive temporary floor must yield to the ordinary detour", temporary.path().contains(new BlockPos(4, 1, 2)));
        assertFalse(temporary.moves().contains("WORK_WALK"));
    }

    @Test public void correctBlockBreakPenaltyChangesTheChosenRoute() {
        floor();
        set(4, 1, 2, TerrainGrid.BLOCKED); set(4, 2, 2, TerrainGrid.BLOCKED);
        breakCosts.put(new BlockPos(4, 1, 2), 1.0); breakCosts.put(new BlockPos(4, 2, 2), 1.0);

        SearchResult freeCorrectBreak = route(world(0, p -> true, construction(30, 0)), new BlockPos(7, 1, 2));
        assertEquals(SearchResult.Status.GOAL, freeCorrectBreak.status());
        assertTrue(freeCorrectBreak.path().contains(new BlockPos(4, 1, 2)));
        assertTrue(freeCorrectBreak.moves().contains("WORK_WALK"));

        SearchResult penalizedCorrectBreak = route(world(0, p -> true, construction(30, 100)), new BlockPos(7, 1, 2));
        assertEquals(SearchResult.Status.GOAL, penalizedCorrectBreak.status());
        assertFalse(penalizedCorrectBreak.path().contains(new BlockPos(4, 1, 2)));
        assertFalse(penalizedCorrectBreak.moves().contains("WORK_WALK"));
    }

    @Test public void pillarRequiresAPlacementBudgetAndSupportsAnActualRoute() {
        floor();
        BlockPos start = new BlockPos(1, 1, 2), above = new BlockPos(1, 2, 2);
        WorkWorld none = world(0, p -> true, construction(0, 1));
        assertFalse(none.moves(start).stream().anyMatch(m -> m.destination().equals(above) && m.kind().equals("WORK_PILLAR")));
        assertNotEquals(SearchResult.Status.GOAL, route(none, above).status());

        WorkWorld supplied = world(1, p -> true, construction(0, 1));
        assertTrue(supplied.moves(start).stream().anyMatch(m -> m.destination().equals(above) && m.kind().equals("WORK_PILLAR")));
        SearchResult route = route(supplied, above);
        assertEquals(SearchResult.Status.GOAL, route.status());
        assertTrue(route.moves().stream().anyMatch(kind -> kind.startsWith("WORK_")));
        assertTrue(supplied.placementsRequired(route.path()) >= 1);
    }

    @Test public void horizontalAscendNeedsEditableSourceHeadroom() {
        floor();
        BlockPos from = new BlockPos(2, 1, 2), to = new BlockPos(3, 2, 2), sourceHead = new BlockPos(2, 3, 2);
        set(3, 1, 2, TerrainGrid.SUPPORT); // landing floor for feet y=2
        set(2, 3, 2, TerrainGrid.BLOCKED); // ascent sweep headroom, not target column

        WorkWorld blocked = world(0, p -> true, construction(0, 1));
        assertFalse(blocked.moves(from).stream().anyMatch(m -> m.destination().equals(to) && m.kind().equals("WORK_ASCEND")));

        breakCosts.put(sourceHead, 2.0);
        WorkWorld cleared = world(0, p -> true, construction(0, 1));
        WorkWorld.Work work = cleared.workForEdge(from, to);
        assertNotNull(work);
        assertTrue(work.breakBlocks().contains(sourceHead));
        assertTrue(cleared.moves(from).stream().anyMatch(m -> m.destination().equals(to) && m.kind().equals("WORK_ASCEND")));
    }

    @Test public void explicitEditPredicateCannotEscapeAcrossProtectedGap() {
        floor();
        for (int z = 0; z < DEPTH; z++) set(4, 0, z, TerrainGrid.CLEAR);
        Predicate<BlockPos> forbidGap = p -> p.x() != 4;
        WorkWorld protectedWorld = world(64, forbidGap, construction(0, 1));
        SearchResult result = route(protectedWorld, new BlockPos(7, 1, 2));
        assertNotEquals(SearchResult.Status.GOAL, result.status());
        assertFalse(protectedWorld.moves(new BlockPos(3, 1, 2)).stream()
                .anyMatch(m -> m.destination().equals(new BlockPos(4, 1, 2)) && m.kind().startsWith("WORK_")));
    }
}
