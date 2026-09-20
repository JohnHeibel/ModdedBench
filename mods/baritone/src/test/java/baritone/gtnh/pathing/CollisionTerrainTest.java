// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Collision-derived footing behind baritone.inspect_terrain and construction work poses. */
public class CollisionTerrainTest {
    private final byte[] cells=new byte[9*10*7];
    private final Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
    private final Map<BlockPos,LadderFacing> ladders=new HashMap<>();
    private void set(int x,int y,int z,byte c) {cells[(x*7+z)*10+y]=c;}
    private TerrainGrid grid() {return new TerrainGrid(0,0,0,9,10,7,cells,new float[cells.length*3],shapes,ladders);}
    private void floor() {Arrays.fill(cells,TerrainGrid.CLEAR);for(int x=0;x<9;x++) for(int z=0;z<7;z++) set(x,0,z,TerrainGrid.SUPPORT);}
    private void shape(int x,int y,int z,CollisionBox... boxes) {set(x,y,z,TerrainGrid.PARTIAL);shapes.put(new BlockPos(x,y,z),new ArrayList<>(List.of(boxes)));}

    @Test public void lowerAndUpperSlabsHaveDifferentFeetCells() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        shape(4,1,3,new CollisionBox(4,1.5,3,5,2,4));
        assertEquals(1.5,grid().standingY(new BlockPos(3,1,3)),0);
        assertTrue(Double.isNaN(grid().standingY(new BlockPos(4,1,3))));
        assertEquals(2,grid().standingY(new BlockPos(4,2,3)),0);
    }
    @Test public void partialCeilingRejectsOtherwiseValidFooting() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.5,4));
        shape(3,3,3,new CollisionBox(3,3,3,4,3.5,4));
        assertTrue(Double.isNaN(grid().standingY(new BlockPos(3,1,3))));
    }
    @Test public void thinAndInsetModdedTopsKeepExactHeight() {
        floor();shape(3,1,3,new CollisionBox(3,1,3,4,1.0625,4));
        shape(4,1,3,new CollisionBox(4.0625,1,3.0625,4.9375,1.4375,3.9375));
        assertEquals(1.0625,grid().standingY(new BlockPos(3,1,3)),0);
        assertEquals(1.4375,grid().standingY(new BlockPos(4,1,3)),0);
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
    @Test public void ladderNeedsAnAttachmentAndASafeBodyVolume() {
        floor();ladder(3,1,3,LadderFacing.EAST);ladder(3,2,3,LadderFacing.WEST);
        assertTrue(grid().climbable(new BlockPos(3,1,3)));
        ladders.remove(new BlockPos(3,2,3));set(3,2,3,TerrainGrid.HAZARD);
        assertFalse(grid().climbable(new BlockPos(3,1,3)));
    }
}
