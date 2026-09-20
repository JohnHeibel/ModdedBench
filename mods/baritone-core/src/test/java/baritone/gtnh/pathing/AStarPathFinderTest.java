// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AStarPathFinderTest {
    @Test public void reopensAnAlreadyExpandedNodeWhenACheaperRouteArrives() {
        BlockPos s=new BlockPos(0,0,0), a=new BlockPos(1,0,0), b=new BlockPos(0,0,1), c=new BlockPos(2,0,0), g=new BlockPos(3,0,0);
        Map<BlockPos,List<Move>> edges=Map.of(s,List.of(new Move(a,3,"a"),new Move(b,1,"b")),
            a,List.of(new Move(c,1,"c")),b,List.of(new Move(a,1,"shortcut")),c,List.of(new Move(g,10,"finish")));
        Map<BlockPos,Integer> expanded=new HashMap<>();
        WorldView view=new WorldView() {
            public boolean isLoaded(int x,int y,int z){return true;}
            public List<Move> moves(BlockPos p){expanded.merge(p,1,Integer::sum);return edges.getOrDefault(p,List.of());}
        };
        Goal goal=new Goal() {
            public boolean isInGoal(int x,int y,int z){return new BlockPos(x,y,z).equals(g);}
            // Admissible but inconsistent: forces A to be expanded before the shortcut via B.
            public double heuristic(int x,int y,int z){return new BlockPos(x,y,z).equals(b)?3:0;}
        };
        SearchResult result=new AStarPathFinder(view,s,goal).calculate(1000,1000,100,()->false);
        assertEquals(List.of(s,b,a,c,g),result.path());
        assertEquals(13,result.cost(),0);
        assertEquals(Integer.valueOf(2),expanded.get(a));
    }
    @Test public void rejectsUnloadedStartEvenWhenItMatchesTheGoal() {
        WorldView view=new WorldView() {
            public boolean isLoaded(int x,int y,int z){return false;}
            public List<Move> moves(BlockPos p){throw new AssertionError("must not query unknown start");}
        };
        SearchResult result=new AStarPathFinder(view,new BlockPos(0,0,0),new GoalBlock(0,0,0)).calculate(1000,1000,10,()->false);
        assertEquals(SearchResult.Status.UNREACHABLE,result.status());
    }
    @Test
    public void heapDecreaseKeyAndReopenUseCheaperRoute() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos expensive = new BlockPos(1, 0, 0);
        BlockPos detour = new BlockPos(0, 0, 1);
        BlockPos goal = new BlockPos(2, 0, 0);
        WorldView world = graph(Map.of(
                start, List.of(new Move(expensive, 10, "expensive"), new Move(detour, 1, "detour")),
                detour, List.of(new Move(expensive, 1, "cheaper")),
                expensive, List.of(new Move(goal, 1, "finish"))));

        SearchResult result = new AStarPathFinder(world, start, new GoalBlock(2, 0, 0))
                .calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.GOAL, result.status());
        assertEquals(List.of(start, detour, expensive, goal), result.path());
        assertEquals(3.0D, result.cost(), 0.0D);
    }

    @Test
    public void findsNontrivialDetour() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos blocked = new BlockPos(1, 0, 0);
        BlockPos north = new BlockPos(0, 0, 1);
        BlockPos northEast = new BlockPos(1, 0, 1);
        BlockPos goal = new BlockPos(2, 0, 0);
        WorldView world = graph(Map.of(
                start, List.of(new Move(blocked, 1, "blocked-route"), new Move(north, 1, "north")),
                blocked, List.of(),
                north, List.of(new Move(northEast, 1, "east")),
                northEast, List.of(new Move(goal, 1, "south-east"))));

        SearchResult result = new AStarPathFinder(world, start, new GoalBlock(2, 0, 0))
                .calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.GOAL, result.status());
        assertEquals(List.of(start, north, northEast, goal), result.path());
        assertEquals(List.of("north", "east", "south-east"), result.moves());
    }

    @Test
    public void rejectsUnloadedDestination() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos unloadedGoal = new BlockPos(6, 0, 0);
        WorldView world = new WorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return x != 6;
            }

            @Override
            public List<Move> moves(BlockPos from) {
                return from.equals(start) ? List.of(new Move(unloadedGoal, 1, "unloaded")) : List.of();
            }
        };

        SearchResult result = new AStarPathFinder(world, start, new GoalBlock(6, 0, 0))
                .calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.UNREACHABLE, result.status());
        assertTrue(result.path().isEmpty());
    }

    @Test
    public void cancellationDiscardsPartialPath() {
        AtomicBoolean cancel = new AtomicBoolean(false);
        BlockPos start = new BlockPos(0, 0, 0);
        WorldView world = cancellingLineWorld(20, cancel);

        SearchResult result = new AStarPathFinder(world, start, new GoalBlock(100, 0, 0))
                .calculate(1_000, 1_000, 100, cancel::get);

        assertEquals(SearchResult.Status.CANCELLED, result.status());
        assertTrue(result.path().isEmpty());
        assertTrue(result.moves().isEmpty());
    }

    @Test
    public void reportsUnreachableWhenNoProgressExists() {
        SearchResult result = new AStarPathFinder(graph(Map.of()), new BlockPos(0, 0, 0), new GoalBlock(10, 0, 0))
                .calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.UNREACHABLE, result.status());
        assertTrue(result.path().isEmpty());
    }

    @Test
    public void returnsPartialOnlyAfterMoreThanFiveBlocks() {
        BlockPos start = new BlockPos(0, 0, 0);
        SearchResult result = new AStarPathFinder(lineWorld(7), start,
                new GoalBlock(100, 0, 0)).calculate(1_000, 1_000, 100, () -> false);

        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertEquals(new BlockPos(7, 0, 0), result.path().get(result.path().size() - 1));
        assertEquals(7, result.moves().size());
    }

    @Test
    public void maxNodeBudgetReturnsTimedOutBestPartial() {
        BlockPos start = new BlockPos(0, 0, 0);
        SearchResult result = new AStarPathFinder(lineWorld(20), start,
                new GoalBlock(100, 0, 0)).calculate(1_000, 1_000, 7, () -> false);

        assertEquals(SearchResult.Status.TIMEOUT, result.status());
        assertFalse(result.path().isEmpty());
        assertEquals(new BlockPos(7, 0, 0), result.path().get(result.path().size() - 1));
        assertEquals(7, result.nodes());
    }

    private static WorldView lineWorld(int end) {
        return new WorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return true;
            }

            @Override
            public List<Move> moves(BlockPos from) {
                if (from.x() >= end) {
                    return List.of();
                }
                return List.of(new Move(new BlockPos(from.x() + 1, 0, 0), 1, "east"));
            }
        };
    }

    private static WorldView cancellingLineWorld(int end, AtomicBoolean cancel) {
        WorldView line = lineWorld(end);
        return new WorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return line.isLoaded(x, y, z);
            }

            @Override
            public List<Move> moves(BlockPos from) {
                if (from.x() == 6) {
                    cancel.set(true);
                }
                return line.moves(from);
            }
        };
    }

    private static WorldView graph(Map<BlockPos, List<Move>> edges) {
        Map<BlockPos, List<Move>> snapshot = new HashMap<>(edges);
        return new WorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return true;
            }

            @Override
            public List<Move> moves(BlockPos from) {
                return snapshot.getOrDefault(from, List.of());
            }
        };
    }
}
