// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkWorldTest {
    @Test public void descentClearsWholeCrossingColumnInUpstreamOrder() {
        floor();
        for(int y=1;y<=3;y++){set(3,y,2,TerrainGrid.SUPPORT);costs.put(new BlockPos(3,y,2),2.0);}
        set(2,1,2,TerrainGrid.SUPPORT);
        BlockPos from=new BlockPos(2,2,2),to=new BlockPos(3,1,2);
        WorkWorld w=world(0);
        assertEquals(List.of(new BlockPos(3,3,2),new BlockPos(3,2,2),new BlockPos(3,1,2)),w.workForEdge(from,to).breakBlocks());
        assertTrue(w.moves(from).stream().anyMatch(m->m.destination().equals(to)&&m.kind().equals("WORK_DESCEND")));
        assertFalse(w.workForEdge(from,to).breakBlocks().contains(new BlockPos(2,1,2)));
        costs.remove(new BlockPos(3,3,2));assertNull(world(0).workForEdge(from,to));
    }
    @Test public void descentRequiresSafeExistingLandingAndProtectedHeadroom() {
        floor();set(2,1,2,TerrainGrid.SUPPORT);set(3,3,2,TerrainGrid.SUPPORT);costs.put(new BlockPos(3,3,2),2.0);
        BlockPos from=new BlockPos(2,2,2),to=new BlockPos(3,1,2);
        WorkWorld protectedHead=new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,64,p->!p.equals(new BlockPos(3,3,2)));
        assertNull(protectedHead.workForEdge(from,to));
        set(4,3,2,TerrainGrid.WATER);assertNull(world(64).workForEdge(from,to));
        set(4,3,2,TerrainGrid.CLEAR);set(3,0,2,TerrainGrid.CLEAR);assertNull(world(64).workForEdge(from,to));
    }
    final byte[] cells=new byte[9*8*5];
    final Map<BlockPos,Double> costs=new HashMap<>();
    void set(int x,int y,int z,byte c) {cells[(x*5+z)*8+y+1]=c;}
    void floor() {Arrays.fill(cells,TerrainGrid.CLEAR);for(int x=0;x<9;x++) for(int z=0;z<5;z++) set(x,0,z,TerrainGrid.SUPPORT);}
    WorkWorld world(int blocks) {return new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,blocks);}
    SearchResult route(WorkWorld w) {return new AStarPathFinder(w,new BlockPos(1,1,2),new GoalBlock(7,1,2)).calculate(2000,2000,10000,()->false);}
    void wall() {for(int z=0;z<5;z++) for(int y=1;y<7;y++) set(4,y,z,TerrainGrid.SUPPORT);}
    @Test public void excavationIsOptInAndOpensSuccessiveFeetAndHeadCells() {
        floor();wall();assertNotEquals(SearchResult.Status.GOAL,route(world(0)).status());
        costs.put(new BlockPos(4,1,2),10.0);costs.put(new BlockPos(4,2,2),10.0);
        SearchResult r=route(world(0));assertEquals(SearchResult.Status.GOAL,r.status());assertTrue(r.moves().contains("WORK_WALK"));
        assertEquals(List.of(new BlockPos(4,2,2),new BlockPos(4,1,2)),world(0).workAt(new BlockPos(4,1,2)).breakBlocks());
    }
    @Test public void cheapDetourBeatsExpensiveMining() {
        floor();set(4,1,2,TerrainGrid.SUPPORT);set(4,2,2,TerrainGrid.SUPPORT);
        costs.put(new BlockPos(4,1,2),100.0);costs.put(new BlockPos(4,2,2),100.0);
        assertFalse(route(world(0)).moves().contains("WORK_WALK"));
    }
    @Test public void fluidsAndUnknownNeighborsPreventExcavation() {
        floor();wall();costs.put(new BlockPos(4,1,2),10.0);costs.put(new BlockPos(4,2,2),10.0);
        for(byte hazard:new byte[]{TerrainGrid.WATER,TerrainGrid.HAZARD,TerrainGrid.UNKNOWN}) {
            set(5,1,2,hazard);assertNull(world(0).workAt(new BlockPos(4,1,2)));
        }
    }
    @Test public void missingFloorRequiresMaterialsAndIsCountedAcrossRoute() {
        floor();for(int x=3;x<=5;x++) for(int z=0;z<5;z++) set(x,0,z,TerrainGrid.CLEAR);
        assertNotEquals(SearchResult.Status.GOAL,route(world(0)).status());
        WorkWorld w=world(3);SearchResult r=route(w);assertEquals(SearchResult.Status.GOAL,r.status());
        assertEquals(3,w.placementsRequired(r.path()));
    }
    @Test public void noWorkRouteCanMineItsOwnSupportOrReplaceLiquid() {
        floor();costs.put(new BlockPos(4,0,2),1.0);assertNull(world(0).workAt(new BlockPos(4,0,2)));
        set(4,0,2,TerrainGrid.WATER);assertNull(world(64).workAt(new BlockPos(4,1,2)));
    }
    @Test public void protectedWallBlocksExcavationButNotWalkingOnProtectedFloor() {
        floor();assertEquals(SearchResult.Status.GOAL,route(new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,0,p->false)).status());
        wall();costs.put(new BlockPos(4,1,2),1.0);costs.put(new BlockPos(4,2,2),1.0);
        assertEquals(SearchResult.Status.GOAL,route(world(0)).status());
        WorkWorld protectedWorld=new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,0,p->p.x()!=4);
        assertNotEquals(SearchResult.Status.GOAL,route(protectedWorld).status());
        assertNull(protectedWorld.workAt(new BlockPos(4,1,2)));
    }
    @Test public void protectionCoversPlacementDestinationAndHeadClearance() {
        floor();set(4,0,2,TerrainGrid.CLEAR);
        WorkWorld protectedFloor=new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,64,p->!p.equals(new BlockPos(4,0,2)));
        assertNotNull(world(64).workAt(new BlockPos(4,1,2)));
        assertNull(protectedFloor.workAt(new BlockPos(4,1,2)));
        floor();wall();costs.put(new BlockPos(4,1,2),1.0);costs.put(new BlockPos(4,2,2),1.0);
        WorkWorld protectedHead=new WorkWorld(new TerrainGrid(0,-1,0,9,8,5,cells),costs,0,p->!p.equals(new BlockPos(4,2,2)));
        assertNull(protectedHead.workAt(new BlockPos(4,1,2)));
    }
    @Test public void blockedSavedCorridorCannotEscapeToAnUnrelatedRoute() {
        floor();
        for(int z=1;z<=3;z++) for(int y=1;y<7;y++) set(4,y,z,TerrainGrid.SUPPORT);
        WorkWorld open=world(0);assertEquals(SearchResult.Status.GOAL,route(open).status());
        CorridorWorld corridor=new CorridorWorld(open,new BlockPos(1,1,2),new BlockPos(7,1,2),1);
        SearchResult blocked=new AStarPathFinder(corridor,new BlockPos(1,1,2),new GoalBlock(7,1,2)).calculate(2000,2000,10000,()->false);
        assertNotEquals(SearchResult.Status.GOAL,blocked.status());
        assertTrue(blocked.path().stream().allMatch(corridor::contains));
    }
    @Test public void routeCorridorIncludesElevationAndEndpointCaps() {
        floor();CorridorWorld corridor=new CorridorWorld(world(0),new BlockPos(1,1,2),new BlockPos(7,7,2),1);
        assertTrue(corridor.contains(new BlockPos(4,4,2)));
        assertTrue(corridor.contains(new BlockPos(4,4,3)));
        assertFalse(corridor.contains(new BlockPos(4,1,2)));
        assertTrue(corridor.contains(new BlockPos(1,1,1)));
        assertFalse(corridor.contains(new BlockPos(0,0,2)));
    }
}
