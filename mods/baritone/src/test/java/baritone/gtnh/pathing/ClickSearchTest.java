// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class ClickSearchTest {
    private static StepPlan.Step place(int x,int y,int z,Map<String,Object> click){
        return StepPlan.places(WorkSpec.cells(Map.of("cells",List.of(Map.of("pos",List.of(x,y,z),"id","mod:machine","click",click))))).get(0);
    }
    private static ClickSearch.Found look(ClickSpace w,StepPlan.Step s){return ClickSearch.look(w,s,null,s.target(),BODY,Set.of(),null,null);}
    /** A finished 3x3x4 box of full blocks (x 0..2, y 65..68, z 0..2) on open ground at y 64, hollow in the middle unless filled. */
    private static Spaces box(boolean filled){
        Spaces s=new Spaces(-8,62,-8,10,74,10,false).floor(63).floor(64);
        for(int x=0;x<3;x++)for(int y=65;y<=68;y++)for(int z=0;z<3;z++)if(filled||x!=1||z!=1||y==65||y==68)s.solid(x,y,z);
        return s;
    }
    @Test public void aBlockForAHoleInAFinishedWallIsClickedFromOutsideAgainstAnyNeighbourOfTheHole(){
        // The hole is in the north wall; the placer is to face south, so the block's front faces out.
        var hatch=place(1,66,0,Map.of("look",Map.of("toward","south")));
        for(boolean filled:new boolean[]{false,true}){
            var found=look(box(filled).air(1,66,0).build(),hatch);
            assertNull(found.problem());assertFalse(found.vantages().isEmpty());
            Set<Vantages.Click> clicked=new HashSet<>();
            for(var v:found.vantages()){
                assertTrue("stands outside, north of the wall: "+v.feet(),v.feet().getZ()<0);
                assertTrue(hatch.click().look().accepts(v.yaw(),v.pitch()));clicked.add(v.click());
            }
            // The block below (its top), the one above (its underside) and the wall beside the hole (the face inside the hole) all do.
            var all=ClickSearch.look(box(filled).air(1,66,0).build(),hatch,null,new Vantages.Target(Vantages.placing(p(1,66,0),null),null,hatch.click().look(),p(1,66,0)),BODY,Set.of(),null,null);
            assertFalse(all.vantages().isEmpty());
            for(int face:new int[]{1,5}){
                var one=new Vantages.Target(Vantages.placing(p(1,66,0),face),null,hatch.click().look(),p(1,66,0));
                assertFalse(ClickSpec.NAMES[face],Vantages.search(box(filled).air(1,66,0).build(),one,BODY,1).isEmpty());
            }
            // What is behind the hole is clicked only when something stands there.
            var behind=new Vantages.Target(Vantages.placing(p(1,66,0),2),null,hatch.click().look(),p(1,66,0));
            assertEquals(filled,!Vantages.search(box(filled).air(1,66,0).build(),behind,BODY,1).isEmpty());
        }
    }
    @Test public void aBlockPlacedLookingDownIntoTheRoofIsClickedFromOnTopOfTheRoofNeverFromTheGround(){
        var hatch=place(1,68,1,Map.of("look",Map.of("toward","down")));
        // Hollow below: nothing under the cell, so it goes against the side of a roof block beside it.
        var hollow=look(box(false).air(1,68,1).build(),hatch);
        assertFalse(hollow.vantages().isEmpty());
        for(var v:hollow.vantages()){
            assertEquals("on the roof: "+v.feet(),69,v.feet().getY());assertTrue(v.pitch()>=45);
            assertEquals(68,v.click().block().getY());assertTrue("a side face of a roof block",v.click().face()>=2);
        }
        // Filled below: the top of the block under it does as well, still only from the roof.
        var filled=look(box(true).air(1,68,1).build(),hatch);
        assertTrue(filled.vantages().stream().allMatch(v->v.feet().getY()==69));
        assertTrue(filled.vantages().stream().anyMatch(v->v.click().equals(new Vantages.Click(p(1,67,1),1))));
        // With the roof cell's neighbours missing too and nothing below, there is nothing to click against.
        Spaces bare=box(false);for(int x=0;x<3;x++)for(int z=0;z<3;z++)bare.air(x,68,z);
        assertEquals("support_missing",look(bare.build(),hatch).problem());
    }
    @Test public void aBlockToBeRemovedIsNotStoodOnUnlessThereIsFootingUnderIt(){
        var onGround=new Spaces(-6,62,-6,6,70,6,false).floor(63).floor(64).solid(0,65,0).build();
        var target=new Vantages.Target(Vantages.using(p(0,65,0),null),null,ClickSpec.Look.ANY,null);
        // Every stance, nearest first: on top of a block that rests on the ground is one of them.
        assertTrue(Vantages.search(onGround,target,BODY,400).stream().anyMatch(v->v.feet().equals(p(0,66,0))));
        // Over a drop (a bridge block), the stance on top is left out however many others there are.
        var bridge=new Spaces(-6,60,-6,6,70,6,false).floor(63).floor(64).air(0,64,0).air(0,63,0).air(0,62,0).air(0,61,0).air(0,60,0).solid(0,65,0).build();
        for(int tried=0;tried<400;tried+=40){
            Set<BlockPos> seen=new HashSet<>();for(var v:Vantages.search(bridge,target,BODY,tried))seen.add(v.feet());seen.remove(p(0,66,0));
            for(var v:ClickSearch.look(bridge,null,null,target,BODY,seen,p(0,65,0),null).vantages())assertNotEquals(p(0,66,0),v.feet());
        }
        assertTrue("the block on the ground may be stood on",ClickSearch.look(onGround,null,null,target,BODY,Set.of(),p(0,65,0),null).vantages().size()>0);
    }
    @Test public void cellsToRemoveForAViewAreOfferedOnlyToAJobThatMayBreakAndNeverMoreThanThree(){
        // Solid rock with a two-high tunnel x 0..2 at y 66..67; the west face of (4,66,0) is one block further in.
        Spaces rock=new Spaces(-3,62,-3,7,70,3,true);for(int x=0;x<3;x++){rock.air(x,66,0);rock.air(x,67,0);}
        ClickSpace w=rock.build();
        var use=StepPlan.uses(Map.of("uses",List.of(Map.of("pos",List.of(4,66,0),"item",Map.of("empty",true),"click",Map.of("face","west"))))).get(0);
        var closed=ClickSearch.look(w,use,null,use.target(),BODY,Set.of(),null,null);
        assertTrue(closed.vantages().isEmpty());assertEquals("no_vantage",closed.problem());assertTrue(closed.openings().isEmpty());
        @SuppressWarnings("unchecked") var blocking=(List<Map<String,Object>>)closed.diagnosis().get("blocking");
        assertFalse("says what is in the way",blocking.isEmpty());assertEquals("minecraft:stone",blocking.get(0).get("id"));
        var open=ClickSearch.look(w,use,null,use.target(),BODY,Set.of(),null,p->true);
        assertFalse(open.openings().isEmpty());
        for(var o:open.openings()){assertTrue(o.remove().size()<=Access.MAX_CELLS);assertFalse("never the clicked block",o.remove().contains(p(4,66,0)));}
        assertTrue("a job whose rule keeps every cell is offered none",ClickSearch.look(w,use,null,use.target(),BODY,Set.of(),null,p->false).openings().isEmpty());
    }
    @Test public void aRememberedWayThatIsGoneIsForgottenAndThePickMadeAgain(){
        ClickSpace w=new Spaces(-6,62,-6,6,70,6,false).floor(63).floor(64).build();
        // The first cell hangs in the air with nothing to click against; the second lies on the ground.
        var steps=StepPlan.places(WorkSpec.cells(Map.of("cells",List.of(Map.of("pos",List.of(0,68,0),"id","mod:pipe","click",Map.of()),Map.of("pos",List.of(2,65,0),"id","mod:pipe","click",Map.of())))));
        StepPlan.Step air=steps.stream().filter(s->s.pos().getY()==68).findFirst().orElseThrow(),ground=steps.stream().filter(s->s.pos().getY()==65).findFirst().orElseThrow();
        Map<String,List<Vantages.Vantage>> ways=new HashMap<>();
        ways.put(air.key(),List.of(new Vantages.Vantage(p(0,65,2),65,new ClickSpec.Vec(.5,66.62,2.5),new Vantages.Click(p(0,67,0),1),new ClickSpec.Vec(.5,68,.5),180,-30)));
        var found=ClickSearch.pick(List.of(air,ground),w,BODY,BODY,ways,null);
        assertEquals(ground,found.step());assertFalse(found.vantages().isEmpty());assertTrue(ways.get(air.key()).isEmpty());
        // With nothing that can be made, the first is handed back with what is wrong with it.
        var none=ClickSearch.pick(List.of(air),w,BODY,BODY,new HashMap<>(),null);
        assertEquals(air,none.step());assertEquals("support_missing",none.problem());
    }
}
