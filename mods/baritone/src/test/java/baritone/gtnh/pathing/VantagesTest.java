// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import baritone.gtnh.pathing.ClickSpec.Vec;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class VantagesTest {
    private static Vantages.Target place(BlockPos into,Integer face,Vec hit,ClickSpec.Look look){return new Vantages.Target(Vantages.placing(into,face),hit,look,into);}
    @Test public void placingOntoOpenGroundFindsANearbyVantageAndItsLook() {
        ClickSpace s=new Spaces(-6,63,-6,6,70,6,false).floor(63).floor(64).build();
        var t=place(p(0,65,0),1,null,ClickSpec.Look.ANY);
        var v=Vantages.search(s,t,BODY,3);
        assertEquals(3,v.size());
        for(var x:v){assertEquals(65,x.feet().getY());assertNotEquals(p(0,65,0),x.feet());assertTrue(x.pitch()>0);assertEquals(new Vantages.Click(p(0,64,0),1),x.click());}
        // The look it reports satisfies a toward requirement it is asked for.
        var north=Vantages.search(s,place(p(0,65,0),1,null,new ClickSpec.Look(2,null,null)),BODY,1);
        assertEquals(1,north.size());assertTrue(new ClickSpec.Look(2,null,null).accepts(north.get(0).yaw(),north.get(0).pitch()));
        assertTrue(north.get(0).feet().getZ()>0);
    }
    @Test public void aGivenHitIsAimedAtOnTheFacePlane() {
        ClickSpace s=new Spaces(-6,63,-6,6,70,6,false).floor(63).floor(64).build();
        var v=Vantages.search(s,place(p(0,65,0),1,new Vec(.25,.3,.75),ClickSpec.Look.ANY),BODY,1).get(0);
        assertEquals(new Vec(.25,65,.75),v.point());
    }
    /** The run's hopper: a hopper into the cell under a forge hammer, facing the barrel south of it, with stone floor around. */
    private static Spaces hopperWorld() {
        return new Spaces(50,63,-97,66,71,-81,false).floor(63).floor(64).floor(65).floor(66)
            .tile(58,66,-88,"gregtech:barrel").air(58,66,-89).tile(58,67,-89,"gregtech:hammer");
    }
    private static final BlockPos HOPPER=p(58,66,-89),HAMMER=p(58,67,-89);
    private static Vantages.Target hopper(){return place(HOPPER,2,null,ClickSpec.Look.ANY);}
    @Test public void hopperUnderHammerHasNoVantageAndNamesTheHammer() {
        ClickSpace s=hopperWorld().build();
        var tally=new Vantages.Tally();
        assertTrue(Vantages.search(s,hopper(),BODY,1,tally).isEmpty());
        assertEquals("no_vantage",Vantages.problem(s,hopper(),tally));
        assertTrue(tally.occluders.containsKey(HAMMER));
        var top=tally.occluders.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        assertEquals(HAMMER,top);
    }
    @Test public void hopperOpeningsNeverTakeTheHammerAndRespectTheTileEntitySafeguard() {
        ClickSpace s=hopperWorld().build();
        Access off=Access.parse(Map.of("allow",true));
        var none=Vantages.openings(s,hopper(),BODY,q->off.refusal(s,q,Set.of(HOPPER),x->false)==null,null,5);
        assertTrue("every cell next to the hammer is within the default tile distance",none.isEmpty());
        Access inBounds=Access.parse(Map.of("allow",true,"bounds",List.of(Map.of("min",List.of(56,66,-91),"max",List.of(60,66,-87)))));
        var open=Vantages.openings(s,hopper(),BODY,q->inBounds.refusal(s,q,Set.of(HOPPER),x->false)==null,null,5);
        assertFalse(open.isEmpty());
        for(var o:open){assertFalse(o.remove().contains(HAMMER));assertFalse(o.remove().contains(p(58,66,-88)));}
        assertEquals(open.toString(),1,open.get(0).remove().size());
        BlockPos first=open.get(0).remove().get(0);
        assertTrue(first.toString(),first.equals(p(59,66,-89))||first.equals(p(57,66,-89)));
        // Standing in the cell directly north of the hopper is not an opening: from there every line of sight to the barrel face crosses the hammer.
        assertTrue(open.toString(),open.stream().noneMatch(o->o.feet().equals(p(58,66,-90))));
        // With that cell removed for real, the search agrees there is now a vantage.
        ClickSpace opened=s.with(first,ClickSpace.Voxel.air());
        assertFalse(Vantages.search(opened,hopper(),BODY,1).isEmpty());
    }
    @Test public void withoutTheHammerTheHopperIsPlacedFromTheNorth() {
        ClickSpace s=hopperWorld().air(58,67,-89).build();
        var v=Vantages.search(s,hopper(),BODY,1);
        assertEquals(1,v.size());assertTrue(v.get(0).eye().z()<-88);
    }
    @Test public void reportsLookUnreachableSupportMissingAndHitNotOnFace() {
        ClickSpace s=new Spaces(-6,63,-6,6,70,6,false).floor(63).floor(64).slab(2,65,0).build();
        var tally=new Vantages.Tally();
        var up=place(p(0,65,0),1,null,new ClickSpec.Look(1,null,null));
        assertTrue(Vantages.search(s,up,BODY,1,tally).isEmpty());assertEquals("look_unreachable",Vantages.problem(s,up,tally));
        var floating=place(p(0,67,0),1,null,ClickSpec.Look.ANY);
        assertEquals("support_missing",Vantages.problem(s,floating,new Vantages.Tally()));
        var high=new Vantages.Target(Vantages.using(p(2,65,0),5),new Vec(1,.8,.5),ClickSpec.Look.ANY,null);
        assertTrue(Vantages.search(s,high,BODY,1).isEmpty());assertEquals("hit_not_on_face",Vantages.problem(s,high,new Vantages.Tally()));
        var low=new Vantages.Target(Vantages.using(p(2,65,0),5),new Vec(1,.25,.5),ClickSpec.Look.ANY,null);
        assertFalse(Vantages.search(s,low,BODY,1).isEmpty());
    }
    @Test public void clicksOnlyFromTheOuterSideOfTheFace() {
        ClickSpace s=new Spaces(-6,63,-6,6,70,6,false).floor(63).floor(64).solid(0,65,0).build();
        var v=Vantages.search(s,new Vantages.Target(Vantages.using(p(0,65,0),5),null,ClickSpec.Look.ANY,null),BODY,20);
        assertFalse(v.isEmpty());
        for(var x:v)assertTrue(x.eye().x()>1);
    }
}
