// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.Planning;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** The search and the route it hands back use the moves they are given, and a source is asked which moves a body has. */
@org.junit.runner.RunWith(baritone.ForgePlanningTestRunner.class)
public class MoveRegistryTest {
    @BeforeClass public static void bootstrap(){Planning.bootstrap();}
    @After public void clear(){MoveRegistry.unregister("leap");}

    /** A made-up move: eight cells east in two ticks, from standing to standing. */
    private static final class Leap implements Move {
        public int xOffset(){return 8;} public int yOffset(){return 0;} public int zOffset(){return 0;}
        public boolean dynamicXZ(){return false;} public boolean dynamicY(){return false;}
        static double cost(CalculationContext c,int x,int y,int z){
            return MovementHelper.canWalkOn(c,x+8,y-1,z)&&MovementHelper.canWalkThrough(c,x+8,y,z)&&MovementHelper.canWalkThrough(c,x+8,y+1,z)?2:ActionCosts.COST_INF;
        }
        public void apply(CalculationContext c,int x,int y,int z,MutableMoveResult r){r.x=x+8;r.y=y;r.z=z;r.cost=cost(c,x,y,z);}
        public Movement apply0(CalculationContext c,BetterBlockPos src){return new Leaping(c.getBaritone(),src);}
    }
    private static final class Leaping extends Movement {
        Leaping(IBaritone b,BetterBlockPos src){super(b,src,new BetterBlockPos(src.x+8,src.y,src.z),new BetterBlockPos[0]);}
        @Override public double calculateCost(CalculationContext c){return Leap.cost(c,src.x,src.y,src.z);}
        @Override protected Set<BetterBlockPos> calculateValidPositions(){return Set.of(src,dest);}
    }

    @Test public void theSearchTakesAnAddedMoveAndTheRouteIsMadeOfIt(){
        var route=Planning.path(Planning.context(new Planning.Terrain(),new Leap()),new BetterBlockPos(0,64,0),new GoalBlock(16,64,0));
        assertEquals(2,route.movements().size());
        assertTrue(route.movements().toString(),route.movements().stream().allMatch(m->m instanceof Leaping));
        assertEquals(4,route.movements().stream().mapToDouble(m->((Movement)m).getCost()).sum(),1e-9);
    }
    @Test public void anAddedMoveThatCannotBeTakenLeavesTheWalkersOwnRoute(){
        var terrain=new Planning.Terrain();
        var walked=Planning.path(Planning.context(terrain),new BetterBlockPos(0,64,0),new GoalBlock(3,64,0));
        var offered=Planning.path(Planning.context(terrain,new Leap()),new BetterBlockPos(0,64,0),new GoalBlock(3,64,0));
        assertEquals(walked.positions(),offered.positions());
        assertTrue(offered.movements().stream().noneMatch(m->m instanceof Leaping));
    }
    @Test public void aSourceIsAskedWhichMovesTheBodyHasNow(){
        assertArrayEquals(Moves.values(),MoveRegistry.capture(null));
        boolean[] worn={false};
        MoveRegistry.register("leap",player->worn[0]?List.of(new Leap()):List.of());
        assertEquals(List.of("leap"),MoveRegistry.names());
        assertArrayEquals(Moves.values(),MoveRegistry.capture(null));
        worn[0]=true;
        var moves=MoveRegistry.capture(null);
        assertEquals(Moves.values().length+1,moves.length);assertTrue(moves[moves.length-1] instanceof Leap);
        MoveRegistry.unregister("leap");
        assertArrayEquals(Moves.values(),MoveRegistry.capture(null));
    }
}
