// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalCompositeTest {
    @Test public void choosesReachableWorkWhenNearestPositionIsBlocked() {
        BlockPos start=new BlockPos(0,0,0),blocked=new BlockPos(1,0,0),step=new BlockPos(0,0,1),work=new BlockPos(0,0,2);
        var edges=Map.of(start,List.of(new Move(step,5,"walk")),step,List.of(new Move(work,5,"walk")));
        WorldView world=new WorldView() {
            public boolean isLoaded(int x,int y,int z){return true;}
            public List<Move> moves(BlockPos p){return edges.getOrDefault(p,List.of());}
        };
        Goal goal=new GoalComposite(List.of(new GoalBlock(blocked.x(),blocked.y(),blocked.z()),new GoalBlock(work.x(),work.y(),work.z())));
        SearchResult result=new AStarPathFinder(world,start,goal).calculate(1000,1000,100,()->false);
        assertEquals(SearchResult.Status.GOAL,result.status());
        assertEquals(List.of(start,step,work),result.path());
    }
    @Test public void selectsCheapestAvailableWorkInsteadOfFirstGoal() {
        BlockPos start=new BlockPos(0,0,0),expensive=new BlockPos(1,0,0),cheap=new BlockPos(0,0,1);
        WorldView world=new WorldView() {
            public boolean isLoaded(int x,int y,int z){return true;}
            public List<Move> moves(BlockPos p){return p.equals(start)?List.of(new Move(expensive,20,"detour"),new Move(cheap,5,"walk")):List.of();}
        };
        Goal a=new GoalBlock(1,0,0),b=new GoalBlock(0,0,1),goal=new GoalComposite(List.of(a,b));
        assertEquals(Math.min(a.heuristic(5,1,7),b.heuristic(5,1,7)),goal.heuristic(5,1,7),0);
        assertFalse(goal.isInGoal(0,0,0));
        SearchResult result=new AStarPathFinder(world,start,goal).calculate(1000,1000,100,()->false);
        assertEquals(List.of(start,cheap),result.path());
        assertEquals(5,result.cost(),0);
    }
}
