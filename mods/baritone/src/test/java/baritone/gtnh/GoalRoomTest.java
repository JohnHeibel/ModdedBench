// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.ForgePlanningTestRunner;
import baritone.api.pathing.goals.*;
import baritone.compat.BlockPos;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** A goal no body fits in is refused before any search, by the shapes the game gives; anything uncertain is searched. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class GoalRoomTest {
    /** A floor of stone at y 63 under air, chunks loaded within |x|,|z| < 16, and whatever a test sets. */
    private static final class World implements GoalRoom.Terrain {
        final Map<List<Integer>,List<double[]>> shapes=new HashMap<>();
        final Map<List<Integer>,String> names=new HashMap<>();
        final Set<List<Integer>> clears=new HashSet<>(),holds=new HashSet<>(),kept=new HashSet<>();
        int asked;
        World set(int x,int y,int z,String name,double[]... boxes){
            var k=List.of(x,y,z);names.put(k,name);
            List<double[]> at=new ArrayList<>();for(double[] b:boxes)at.add(new double[]{x+b[0],y+b[1],z+b[2],x+b[3],y+b[4],z+b[5]});
            shapes.put(k,at);return this;
        }
        World cube(int x,int y,int z,String name){return set(x,y,z,name,CUBE);}
        @Override public boolean loaded(int x,int y,int z){return Math.abs(x)<16&&Math.abs(z)<16;}
        @Override public List<double[]> boxes(int x,int y,int z){
            asked++;var k=List.of(x,y,z);
            if(shapes.containsKey(k))return shapes.get(k);
            return y==63?List.of(new double[]{x,y,z,x+1,y+1,z+1}):List.of();
        }
        @Override public boolean clears(int x,int y,int z){return clears.contains(List.of(x,y,z));}
        @Override public boolean holds(int x,int y,int z){return holds.contains(List.of(x,y,z));}
        @Override public boolean enters(int x,int y,int z){return !kept.contains(List.of(x,y,z));}
        @Override public Map<String,Object> block(int x,int y,int z){return Map.of("block",names.getOrDefault(List.of(x,y,z),y==63?"minecraft:stone":"minecraft:air"));}
    }
    private static final double[] CUBE={0,0,0,1,1,1};
    // 1.7.10's cauldron: a floor and four walls an eighth thick, a block high.
    private static final double[][] CAULDRON={{0,0,0,1,.3125,1},{0,0,0,.125,1,1},{0,0,0,1,1,.125},{.875,0,0,1,1,1},{0,0,.875,1,1,1}};
    private static GoalBlock at(int x,int y,int z){return new GoalBlock(x,y,z);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> obstructions(Map<String,Object> r){return (List<Map<String,Object>>)r.get("obstructions");}

    @Test public void aGoalInsideAWallIsRefusedWithTheObstructionNamed(){
        var world=new World().cube(0,64,0,"minecraft:cobblestone").cube(0,65,0,"minecraft:log");
        long t=System.nanoTime();var refused=GoalRoom.refusal(at(0,64,0),world,false);long ns=System.nanoTime()-t;
        assertNotNull(refused);assertEquals("no_room_for_the_body",refused.get("why"));
        assertEquals(List.of(0,64,0),refused.get("cell"));
        assertEquals(List.of(0,64,0),obstructions(refused).get(0).get("pos"));assertEquals("minecraft:cobblestone",obstructions(refused).get(0).get("block"));
        assertEquals(List.of(0,65,0),obstructions(refused).get(1).get("pos"));assertEquals("minecraft:log",obstructions(refused).get(1).get("block"));
        assertTrue("a few dozen shapes, not a search: "+world.asked,world.asked<=36);assertTrue(ns<50_000_000L);
        // A goal whose head is in the wall: the body is 1.8 high.
        assertNotNull(GoalRoom.refusal(at(0,63,0),new World().set(0,63,0,"minecraft:air").cube(0,64,0,"minecraft:cobblestone"),false));
    }

    @Test public void aBoxlessCellTheModelKeepsTheBodyOutOfIsRefused(){
        // Flowing oil (2026-10-01, the base pond): no collision box, but the movement model never puts the body in it, so a
        // goal there flooded 340k nodes from one block away. The fluid is named, marked unpathable.
        var oil=new World().set(0,64,0,"BuildCraft|Energy:blockOil");oil.holds.add(List.of(0,64,0));oil.kept.add(List.of(0,64,0));
        var refused=GoalRoom.refusal(at(0,64,0),oil,true);
        assertNotNull(refused);assertEquals("no_room_for_the_body",refused.get("why"));
        assertEquals(List.of(0,64,0),obstructions(refused).get(0).get("pos"));assertEquals(false,obstructions(refused).get(0).get("pathable"));
        // In the head cell as well; and a box-less cell the model does enter (air, still water) is room.
        var head=new World();head.kept.add(List.of(0,65,0));assertNotNull(GoalRoom.refusal(at(0,64,0),head,false));
        var water=new World().set(0,64,0,"minecraft:water");water.holds.add(List.of(0,64,0));assertNull(GoalRoom.refusal(at(0,64,0),water,false));
        // One cell of a near goal the model keeps out of does not refuse the goal.
        var near=new World();near.kept.add(List.of(0,64,0));assertNull(GoalRoom.refusal(new GoalComposite(at(0,64,0),at(2,64,0)),near,false));
    }

    @Test public void aCauldronIsRoomUnlessSomethingSitsOnIt(){
        // The body (0.6 wide) fits in its 0.75 well, feet on its floor: a goal in an open cauldron is not refused ...
        var open=new World().set(0,64,0,"minecraft:cauldron",CAULDRON);
        assertNull(GoalRoom.refusal(at(0,64,0),open,false));
        // ... a roofed one is, by the game's shapes, naming the cauldron first.
        var roofed=new World().set(0,64,0,"minecraft:cauldron",CAULDRON).cube(0,65,0,"minecraft:planks");
        var refused=GoalRoom.refusal(at(0,64,0),roofed,false);
        assertNotNull(refused);assertEquals("minecraft:cauldron",obstructions(refused).get(0).get("block"));
    }

    @Test public void standableGoalsAreNotRefused(){
        assertNull(GoalRoom.refusal(at(3,64,3),new World(),false));
        // Walled in on all four sides, roofed above the head: room, though perhaps no route (that is the search's to say).
        var walled=new World();for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})walled.cube(d[0],64,d[1],"minecraft:cobblestone").cube(d[0],65,d[1],"minecraft:cobblestone");
        walled.cube(0,66,0,"minecraft:cobblestone");
        assertNull(GoalRoom.refusal(at(0,64,0),walled,false));
        // A bottom slab in the cell: the feet stand on it, half a block up.
        assertNull(GoalRoom.refusal(at(0,64,0),new World().set(0,64,0,"minecraft:stone_slab",new double[]{0,0,0,1,.5,1}),false));
        // A fence post in the cell leaves room beside it in the same cell.
        assertNull(GoalRoom.refusal(at(0,64,0),new World().set(0,64,0,"minecraft:fence",new double[]{.375,0,.375,.625,1.5,.625}),false));
        // A two-blocks goal (its cell holds the feet or the head) fits with the feet one lower when the cell above is full;
        // with its own cell full, neither does.
        assertNull(GoalRoom.refusal(new GoalTwoBlocks(0,65,0),new World().cube(0,66,0,"minecraft:cobblestone"),false));
        assertNotNull(GoalRoom.refusal(new GoalTwoBlocks(0,65,0),new World().cube(0,65,0,"minecraft:cobblestone"),false));
    }

    @Test public void aGoalWithNothingUnderItIsRefusedUnlessSomethingHoldsThePlayer(){
        // In mid-air: the body fits, nothing holds it up.
        var refused=GoalRoom.refusal(at(0,66,0),new World(),false);
        assertNotNull(refused);assertEquals("nothing_to_stand_on",refused.get("why"));
        assertEquals(Map.of("pos",List.of(0,65,0),"block","minecraft:air"),refused.get("below"));
        // A job that may place blocks can make a floor.
        assertNull(GoalRoom.refusal(at(0,66,0),new World(),true));
        // A fluid or something climbable in the column holds the body, as the game says.
        var water=new World();water.holds.add(List.of(0,65,0));assertNull(GoalRoom.refusal(at(0,66,0),water,false));
        var ladder=new World();ladder.holds.add(List.of(0,66,0));assertNull(GoalRoom.refusal(at(0,66,0),ladder,false));
        // A floor the job could break still holds the feet; so do a fence post's top and a neighbour's edge.
        var dirt=new World().cube(0,65,0,"minecraft:dirt");dirt.clears.add(List.of(0,65,0));assertNull(GoalRoom.refusal(at(0,66,0),dirt,false));
        assertNull(GoalRoom.refusal(at(0,65,0),new World().set(0,64,0,"minecraft:fence",new double[]{.375,0,.375,.625,1.5,.625}),false));
        assertNull(GoalRoom.refusal(at(0,66,0),new World().cube(1,65,0,"minecraft:planks"),false));
    }

    @Test public void aBreakableObstructionWithBreakingAllowedIsNotRefused(){
        var world=new World().cube(0,64,0,"minecraft:cobblestone").cube(0,65,0,"minecraft:log");
        world.clears.add(List.of(0,64,0));
        // The log still fills the head's cell.
        var refused=GoalRoom.refusal(at(0,64,0),world,false);
        assertNotNull(refused);assertEquals(List.of(0,65,0),obstructions(refused).get(0).get("pos"));
        world.clears.add(List.of(0,65,0));
        assertNull(GoalRoom.refusal(at(0,64,0),world,false));
    }

    @Test public void aGoalInOrBesideUnloadedChunksIsNeverRefused(){
        // Solid rock at the goal, but its chunk is not loaded: the search walks toward it in segments, as before.
        var world=new World();for(int y=60;y<70;y++)world.cube(40,y,0,"minecraft:stone").cube(1000,y,1000,"minecraft:stone");
        assertNull(GoalRoom.refusal(at(40,64,0),world,false));
        assertNull(GoalRoom.refusal(at(1000,64,1000),world,false));
        // A full cell on the edge of the loaded chunks: one of its neighbours is unknown, so nothing is certain.
        var edge=new World().cube(15,64,0,"minecraft:stone").cube(15,65,0,"minecraft:stone");
        assertNull(GoalRoom.refusal(at(15,64,0),edge,false));
        assertNotNull(GoalRoom.refusal(at(14,64,0),new World().cube(14,64,0,"minecraft:stone").cube(14,65,0,"minecraft:stone"),false));
        // Any cell of a composite out of reach of what is loaded leaves the whole goal to the search.
        assertNull(GoalRoom.refusal(new GoalComposite(at(0,64,0),at(40,64,0)),new World().cube(0,64,0,"minecraft:stone"),false));
        assertEquals(Boolean.FALSE,GoalRoom.loaded(new GoalComposite(at(0,64,0),at(40,64,0)),new World()));
        assertEquals(Boolean.TRUE,GoalRoom.loaded(at(0,64,0),new World()));
    }

    @Test public void regionGoalsAreJudgedOnlyWhenTheirCellsAreFewAndKnown(){
        assertEquals(33,GoalRoom.cells(new GoalNear(new BlockPos(0,64,0),2)).size());
        assertEquals(123,GoalRoom.cells(new GoalNear(new BlockPos(0,64,0),3)).size());
        assertNull(GoalRoom.cells(new GoalNear(new BlockPos(0,64,0),4)));
        assertEquals(2,GoalRoom.cells(new GoalTwoBlocks(0,64,0)).size());
        assertNull(GoalRoom.cells(new GoalXZ(0,0)));assertNull(GoalRoom.cells(new GoalYLevel(64)));
        assertNull(GoalRoom.cells(new GoalGetToBlock(new BlockPos(0,64,0))));
        assertNull(GoalRoom.cells(new GoalComposite(at(0,64,0),new GoalXZ(0,0))));
        assertNull(GoalRoom.refusal(new GoalXZ(0,0),new World(),false));
        // A near goal in solid rock is refused; one free cell in it is enough not to be.
        var rock=new World();for(int x=-4;x<=4;x++)for(int y=60;y<=70;y++)for(int z=-4;z<=4;z++)rock.cube(x,y,z,"minecraft:stone");
        var refused=GoalRoom.refusal(new GoalNear(new BlockPos(0,64,0),1),rock,false);
        assertNotNull(refused);assertEquals(7,refused.get("goalCells"));assertEquals(List.of(0,64,0),refused.get("cell"));
        rock.set(1,64,0,"minecraft:air").set(1,65,0,"minecraft:air");
        assertNull(GoalRoom.refusal(new GoalNear(new BlockPos(0,64,0),1),rock,false));
    }

    @Test public void aFailedSearchIsNamedForHowItEnded(){
        assertEquals("no_route_to_goal",PathFailure.searchEnded("exhausted",null));
        // The search was refused an edit in a protected region: the reason says which.
        assertEquals("no_route_to_goal; the search was refused edits in protected_region:base,shed",PathFailure.refusedBy("no_route_to_goal",java.util.List.of("base","shed")));
        assertEquals("no_route_to_goal",PathFailure.refusedBy("no_route_to_goal",null));
        assertEquals("no_route_to_goal",PathFailure.refusedBy("no_route_to_goal",java.util.List.of()));
        // The frontier reached unloaded chunks; the goal is not there.
        assertEquals("no_route_in_loaded_chunks",PathFailure.searchEnded("unloaded_chunks",true));
        // The goal is in unloaded chunks, or its cells are not known: as before.
        assertEquals("search_failed_unloaded_chunks",PathFailure.searchEnded("unloaded_chunks",false));
        assertEquals("search_failed_unloaded_chunks",PathFailure.searchEnded("unloaded_chunks",null));
        assertEquals("search_failed_timeout",PathFailure.searchEnded("timeout",true));
        assertEquals("search_failed_unknown",PathFailure.searchEnded(null,null));
    }

    @Test public void aSnagTheJobGotPastIsEvidenceNotTheCause(){
        // An edge banned early, then a search that failed far from it: the search names the end, and both are kept.
        var snags=new baritone.gtnh.pathing.Snags();
        snags.failed(new baritone.gtnh.pathing.Snags.Edge(3,64,0,4,64,0),0,Map.of("kind","snagged","at",List.of(3,64,0)));
        Map<String,Object> last=Map.of("type","FAILURE"),detail=new LinkedHashMap<>();
        assertNull(PathFailure.before(snags,last,detail));
        assertSame(last,detail.get("lastCalculation"));assertNotNull(detail.get("snags"));
        // The snag that ended the job is the cause, with the last search still beside it.
        snags.fail("snagged_at_3,64,0");detail.clear();
        assertEquals("snagged_at_3,64,0",PathFailure.before(snags,last,detail));
        assertSame(last,detail.get("lastCalculation"));assertNotNull(detail.get("snags"));
        // No search ran and nothing snagged.
        detail.clear();assertEquals("stopped_before_searching",PathFailure.before(new baritone.gtnh.pathing.Snags(),null,detail));
        assertTrue(detail.isEmpty());
    }
}
