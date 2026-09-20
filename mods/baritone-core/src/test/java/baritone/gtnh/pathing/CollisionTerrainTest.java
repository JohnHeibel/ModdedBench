// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollisionTerrainTest {
    private final byte[] cells=new byte[9*10*7];
    private final Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
    private final Map<BlockPos,LadderFacing> ladders=new HashMap<>();
    private void set(int x,int y,int z,byte c) {cells[(x*7+z)*10+y]=c;}
    private TerrainGrid grid() {return grid(.5);}
    private TerrainGrid grid(double step) {return new TerrainGrid(0,0,0,9,10,7,cells,new float[cells.length*3],shapes,ladders,step);}
    private void floor() {Arrays.fill(cells,TerrainGrid.CLEAR);for(int x=0;x<9;x++) for(int z=0;z<7;z++) set(x,0,z,TerrainGrid.SUPPORT);}
    private void shape(int x,int y,int z,CollisionBox... boxes) {set(x,y,z,TerrainGrid.PARTIAL);shapes.put(new BlockPos(x,y,z),new ArrayList<>(List.of(boxes)));}
    private Move to(TerrainGrid g,BlockPos from,BlockPos to) {return g.moves(from).stream().filter(m->m.destination().equals(to)).findFirst().orElseThrow();}

    @Test public void lowerAndUpperSlabsHaveDifferentFeetCells() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        shape(4,1,3,new CollisionBox(4,1.5,3,5,2,4));
        assertEquals(1.5,grid().standingY(new BlockPos(3,1,3)),0);
        assertTrue(Double.isNaN(grid().standingY(new BlockPos(4,1,3))));
        assertEquals(2,grid().standingY(new BlockPos(4,2,3)),0);
        assertEquals("STEP_UP",to(grid(),new BlockPos(2,1,3),new BlockPos(3,1,3)).kind());
        assertEquals("STEP_DOWN",to(grid(),new BlockPos(3,1,3),new BlockPos(2,1,3)).kind());
    }
    @Test public void stairsSweepBothHalfStepsInBothDirections() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4),new CollisionBox(3.5,1.5,3,4,2,4));
        assertEquals("STEP_UP",to(grid(),new BlockPos(2,1,3),new BlockPos(3,2,3)).kind());
        assertEquals("STEP_DOWN",to(grid(),new BlockPos(3,2,3),new BlockPos(2,1,3)).kind());
        // Approaching the solid back of the stair requires a jump.
        assertEquals("ASCEND",to(grid(),new BlockPos(4,1,3),new BlockPos(3,2,3)).kind());
    }
    @Test public void rotatedStairsUseCollisionBoxesRatherThanBlockNames() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4),new CollisionBox(3,1.5,3.5,4,2,4));
        assertEquals("STEP_UP",to(grid(),new BlockPos(3,1,2),new BlockPos(3,2,3)).kind());
    }
    @Test public void partialCeilingRejectsOtherwiseValidFooting() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        shape(3,3,3,new CollisionBox(3,3,3,4,3.5,4));
        assertTrue(Double.isNaN(grid().standingY(new BlockPos(3,1,3))));
    }
    @Test public void raisedPlayerHeadCannotPassThroughOverhangDuringStep() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        // Enough space over the destination but not over the old footprint while stepping up.
        shape(2,3,3,new CollisionBox(2,3,3,3,3.5,4));
        assertNull(grid().groundMove(new BlockPos(2,1,3),new BlockPos(3,1,3)));
    }
    @Test public void thinAndInsetModdedTopsKeepExactHeight() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.0625,4));
        shape(4,1,3,new CollisionBox(4.0625,1,3.0625,4.9375,1.4375,3.9375));
        assertEquals(1.0625,grid().standingY(new BlockPos(3,1,3)),0);
        assertEquals(1.4375,grid().standingY(new BlockPos(4,1,3)),0);
        assertNotNull(grid().groundMove(new BlockPos(3,1,3),new BlockPos(4,1,3)));
    }
    @Test public void stepHeightComesFromPlayerCapability() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        assertEquals("ASCEND",to(grid(0),new BlockPos(2,1,3),new BlockPos(3,1,3)).kind());
    }
    @Test public void immutableShapesAndUnknownCellsCannotInventSupport() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));TerrainGrid captured=grid();
        shapes.get(new BlockPos(3,1,3)).clear();set(3,1,3,TerrainGrid.UNKNOWN);
        assertEquals(1.5,captured.standingY(new BlockPos(3,1,3)),0);
        assertTrue(Double.isNaN(grid().standingY(new BlockPos(3,1,3))));
    }
    private void ladder(int x,int y,int z,LadderFacing face) {
        set(x,y,z,TerrainGrid.LADDER);ladders.put(new BlockPos(x,y,z),face);
        shapes.put(new BlockPos(x,y,z),List.of(new CollisionBox(x+.875,y,z,x+1,y+1,z+1)));
    }
    @Test public void continuousLadderConnectsGroundAndPlatformBothWays() {
        floor();for(int y=1;y<=5;y++) {ladder(3,y,3,LadderFacing.EAST);set(4,y,3,TerrainGrid.SUPPORT);}
        TerrainGrid g=grid();
        SearchResult up=new AStarPathFinder(g,new BlockPos(2,1,3),new GoalBlock(4,6,3)).calculate(2000,2000,10000,()->false);
        assertEquals(SearchResult.Status.GOAL,up.status());assertTrue(up.moves().contains("CLIMB_UP"));assertTrue(up.moves().contains("CLIMB_EXIT"));
        SearchResult down=new AStarPathFinder(g,new BlockPos(4,6,3),new GoalBlock(2,1,3)).calculate(2000,2000,10000,()->false);
        assertEquals(SearchResult.Status.GOAL,down.status());assertTrue(down.moves().contains("CLIMB_DOWN"));
    }
    @Test public void brokenOrChangedAttachmentCannotBeClimbedThrough() {
        floor();ladder(3,1,3,LadderFacing.EAST);ladder(3,2,3,LadderFacing.WEST);
        assertFalse(grid().moves(new BlockPos(3,1,3)).stream().anyMatch(m->m.kind().equals("CLIMB_UP")));
        ladders.remove(new BlockPos(3,2,3));set(3,2,3,TerrainGrid.HAZARD);
        assertFalse(grid().climbable(new BlockPos(3,1,3)));
    }
    @Test public void stepAndLadderCostsRespectRetainedGoalHeuristic() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4),new CollisionBox(3.5,1.5,3,4,2,4));
        BlockPos start=new BlockPos(2,1,3);
        for(Move m:grid().moves(start)) assertTrue(m.cost()>=new GoalBlock(m.destination().x(),m.destination().y(),m.destination().z()).heuristic(start.x(),start.y(),start.z()));
    }
}
