// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.moves;

import baritone.Planning;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.compat.Blocks;
import baritone.pathing.movement.Movement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** A glide is planned where the wings can fly it and nowhere else, and a route across a drop is made of one. */
@org.junit.runner.RunWith(baritone.ForgePlanningTestRunner.class)
public class ElytraGlideTest {
    @BeforeClass public static void bootstrap(){Planning.bootstrap();}

    static final int LONG=100000;
    /** Level ground at y 64 and a tower to stand on, twenty up, its east edge at x. */
    static Planning.Terrain tower(int x){
        var terrain=new Planning.Terrain();
        for(int tx=x-1;tx<=x;tx++)for(int z=-1;z<=1;z++)for(int y=64;y<84;y++)terrain.set(tx,y,z,Blocks.STONE,0);
        return terrain;
    }
    static ElytraGlide east(float pitch){return new ElytraGlide(1,0,pitch,LONG);}
    static ElytraGlide.Flight plan(Planning.Terrain terrain,ElytraGlide glide,int x){return glide.plan(Planning.context(terrain),x,84,0);}

    @Test public void aSteeperGlideComesDownSoonerAndAllOfThemOnTheGround(){
        var terrain=tower(-40);
        int last=Integer.MAX_VALUE;
        for(float pitch:ElytraGlide.PITCHES){
            var flight=plan(terrain,east(pitch),-40);
            assertNotNull("pitch "+pitch,flight);
            assertEquals(64,flight.dest().y);assertEquals(0,flight.dest().z);
            assertEquals(flight.touchdown().x+ElytraGlide.SLIDE,flight.dest().x);
            assertTrue(pitch+" lands at "+flight.dest().x,flight.dest().x<last&&flight.dest().x>-30);
            assertTrue(flight.cost()>0&&flight.cost()<ActionCosts.COST_INF);
            last=flight.dest().x;
        }
    }
    @Test public void aGlideIsOnlyFromALedge(){
        var terrain=tower(-40);
        assertNull("level ground",plan(new Planning.Terrain(),east(20),0));
        assertNull("the tower's middle",plan(terrain,east(20),-41));
        assertNotNull("the west edge, going west",new ElytraGlide(-1,0,30,LONG).plan(Planning.context(tower(20)),19,84,0));
    }
    @Test public void aWallOrAPillarBesideTheFlightStopsIt(){
        var open=plan(tower(-40),east(20),-40);
        var walled=tower(-40);
        for(int y=64;y<90;y++)walled.set(-20,y,0,Blocks.STONE,0);
        assertNull(plan(walled,east(20),-40));
        var beside=tower(-40);
        for(int y=64;y<90;y++)beside.set(-20,y,1,Blocks.STONE,0);
        assertNull(plan(beside,east(20),-40));
        var clear=tower(-40);
        for(int y=64;y<90;y++)clear.set(-20,y,3,Blocks.STONE,0);
        assertEquals(open.dest(),plan(clear,east(20),-40).dest());
    }
    @Test public void aGlideDoesNotEndInWaterOrPastWhatIsLoadedOrHarderThanTheBodyTakes(){
        var dry=plan(tower(-40),east(20),-40);
        var wet=tower(-40);
        for(int x=dry.touchdown().x-3;x<=dry.dest().x+3;x++)for(int z=-1;z<=1;z++)wet.set(x,63,z,net.minecraft.init.Blocks.water,0);
        assertNull(plan(wet,east(20),-40));
        assertNull("the flight leaves the loaded world",plan(tower(40),east(10),40));
        assertNull("too steep to land",plan(tower(-40),east(45),-40));
    }
    @Test public void wingsThatWouldWearOutInTheAirAreNotFlown(){
        assertNull(plan(tower(-40),new ElytraGlide(1,0,20,40),-40));
        assertNotNull(plan(tower(-40),new ElytraGlide(1,0,20,200),-40));
    }
    @Test public void aRouteAcrossTheDropIsAGlideAndWithoutTheWingsThatGlideCostsTheSearchNothing(){
        var terrain=tower(-40);
        var glides=new ElytraGlide[]{east(10),east(20),east(30)};
        var route=Planning.path(Planning.context(terrain,glides),new BetterBlockPos(-40,84,0),new GoalBlock(30,64,0));
        assertEquals(route.movements().toString(),1,route.movements().stream().filter(m->m instanceof ElytraGlide.Gliding).count());
        var gliding=(Movement)route.movements().stream().filter(m->m instanceof ElytraGlide.Gliding).findFirst().orElseThrow();
        assertTrue(gliding.getValidPositions().contains(gliding.getSrc())&&gliding.getValidPositions().contains(gliding.getDest()));
        assertEquals(gliding.getCost(),gliding.calculateCost(Planning.context(terrain,glides)),1e-9);
        assertEquals("the wings came off",ActionCosts.COST_INF,gliding.calculateCost(Planning.context(terrain)),0);
    }
    /** The arithmetic is the game's: these are a flight measured in the pack (harness/smoke/glide_course.py prints them). */
    @Test public void aTickOfFlightIsTheGamesOwn(){
        double[] v={0.2,-0.2,0};
        ElytraGlide.fly(v,-90,20);
        assertEquals(0.21586,v[0],1e-5);assertEquals(-0.20283,v[1],1e-5);assertEquals(0,v[2],1e-9);
    }
}
