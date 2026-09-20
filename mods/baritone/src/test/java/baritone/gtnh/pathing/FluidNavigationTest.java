// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fluid observation behind baritone.inspect_terrain and inspect_fluid; route search itself is upstream. */
public class FluidNavigationTest {
    private final byte[] cells=new byte[9*9*5];
    private final float[] flow=new float[cells.length*3];
    private int index(int x,int y,int z) {return (x*5+z)*9+y;}
    private void set(int x,int y,int z,byte value) {cells[index(x,y,z)]=value;}
    private TerrainGrid grid() {return new TerrainGrid(0,0,0,9,9,5,cells,flow);}
    private void pool() {
        Arrays.fill(cells,TerrainGrid.CLEAR);
        for(int x=0;x<9;x++) for(int z=0;z<5;z++) set(x,0,z,TerrainGrid.SUPPORT);
        for(int x=2;x<=6;x++) for(int y=1;y<=3;y++) set(x,y,2,TerrainGrid.WATER);
        for(int x:new int[]{1,7}) for(int y=1;y<=3;y++) set(x,y,2,TerrainGrid.SUPPORT);
    }
    @Test public void refusesHazardousMixedFluidBoundaryAndUnloadedNeighbors() {
        pool();set(4,3,3,TerrainGrid.HAZARD);
        assertFalse(grid().swimmable(4,3,2));
        set(4,3,3,TerrainGrid.UNKNOWN);assertFalse(grid().swimmable(4,3,2));
        set(4,3,3,TerrainGrid.SUPPORT);assertTrue(grid().swimmable(4,3,2));
    }
    @Test public void fallingCurrentNeedsFloorSupport() {
        pool();flow[index(4,3,2)*3+1]=-.98f;
        assertFalse(grid().swimmable(4,3,2));
        set(4,2,2,TerrainGrid.SUPPORT);assertTrue(grid().swimmable(4,3,2));
    }
    @Test public void swimRequiresHeadroomAndSnapshotOwnsFlowData() {
        pool();set(4,5,2,TerrainGrid.BLOCKED);assertFalse(grid().swimmable(4,3,2));
        set(4,5,2,TerrainGrid.CLEAR);flow[index(4,3,2)*3]=1;
        TerrainGrid captured=grid();flow[index(4,3,2)*3]=-1;
        assertEquals(1,captured.flow(4,3,2,0),0);
    }
    @Test public void ambientTemperatureAndWaterMaterialAreNotProofOfSafety() {
        assertEquals("unverified_fluid",FluidPolicy.reason(false,false,300));
        assertEquals("unverified_fluid",FluidPolicy.reason(false,false,null));
        assertEquals("hot_fluid",FluidPolicy.reason(false,false,900));
        assertEquals("lava",FluidPolicy.reason(false,true,300));
        assertEquals("verified_water",FluidPolicy.reason(true,false,300));
    }
}
