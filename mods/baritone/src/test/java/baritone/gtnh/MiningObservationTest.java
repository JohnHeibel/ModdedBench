// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.WorkSpec;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class MiningObservationTest {
    @Test public void afterTheFirstPassAScanTickReadsASmallSlice(){
        // A thousand cells and no selectors: nothing is asked of the world.
        var scan=new MiningObservation(null,WorkSpec.bounds(Map.of("min",List.of(0,0,0),"max",List.of(9,9,9))),List.of(),List.of());
        for(int t=0;t<1000&&scan.passes==0;t++)scan.tick();
        assertEquals(1,scan.passes);assertEquals(0,scan.cursor);
        scan.tick();
        assertEquals(1,scan.passes);assertTrue(""+scan.cursor,scan.cursor<=256);
    }

    @Test public void miningIsOfferedTheNearestMatchesAndMoreOnlyWhenAsked(){
        List<BlockPos> all=new ArrayList<>();
        for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++)all.add(new BlockPos(x,64,z));
        Collections.shuffle(all,new Random(7));
        var centre=new BlockPos(3,64,-2);
        var near=MiningObservation.nearest(all,centre,9);
        assertEquals(9,near.size());assertEquals(centre,near.get(0));
        for(var p:near)assertTrue(p.toString(),Math.abs(p.getX()-3)<=1&&Math.abs(p.getZ()+2)<=1);
        for(int i=1;i<near.size();i++)assertTrue(centre.distanceSq(near.get(i-1))<=centre.distanceSq(near.get(i)));
        // Fewer than asked for: all of them.
        assertEquals(new HashSet<>(all),new HashSet<>(MiningObservation.nearest(all,centre,5000)));
        // An observation with nothing published has nothing more to offer; one without a centre offers everything already.
        var bounds=WorkSpec.bounds(Map.of("min",List.of(0,0,0),"max",List.of(1,1,1)));
        assertFalse(new MiningObservation(null,bounds,List.of(),List.of(),()->centre).widen());
        assertFalse(new MiningObservation(null,bounds,List.of(),List.of()).widen());
    }

    @Test public void aTargetBeyondReachPlusOneIsNeverOneARayCouldReach(){
        // reachable() tries 27 points of the block and takes those within reach-.1 of the eye. near() must hold wherever
        // one of them is, so skipping on it changes nothing but the work.
        var random=new Random(11);double reach=4.5;var target=new BlockPos(10,60,-5);
        for(int i=0;i<200000;i++){
            var eye=net.minecraft.util.Vec3.createVectorHelper(10.5+(random.nextDouble()-.5)*14,60.5+(random.nextDouble()-.5)*14,-4.5+(random.nextDouble()-.5)*14);
            boolean some=false;
            for(double y:new double[]{.95,.5,.05})for(double x:new double[]{.5,.15,.85})for(double z:new double[]{.5,.15,.85})
                some|=eye.distanceTo(net.minecraft.util.Vec3.createVectorHelper(10+x,60+y,-5+z))<=reach-.1;
            if(some)assertTrue(MiningJob.near(eye,target,reach));
        }
        assertFalse(MiningJob.near(net.minecraft.util.Vec3.createVectorHelper(10.5,60.5,1.1),target,reach));
    }
}
